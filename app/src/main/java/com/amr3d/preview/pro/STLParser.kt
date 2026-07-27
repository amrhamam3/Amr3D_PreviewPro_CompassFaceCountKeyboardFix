package com.amr3d.preview.pro

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Represents the parsed geometry of an STL file.
 * vertices: flat array of x,y,z per vertex
 * normals: flat array of nx,ny,nz per vertex (one normal per triangle, repeated for each of its 3 vertices)
 * triangleCount: number of triangles
 */
data class STLModel(
    val vertices: FloatArray,
    val normals: FloatArray,
    val triangleCount: Int,
    val minBounds: FloatArray, // [minX, minY, minZ]
    val maxBounds: FloatArray, // [maxX, maxY, maxZ]
    val isWatertightHint: Boolean // basic heuristic, not a full manifold check
)

class STLParseException(message: String) : Exception(message)

object STLParser {

    private const val MAX_FILE_SIZE = 2_000_000_000L // 2 GB limit
    private const val CHUNK_SIZE = 4_000_000 // Read 4MB chunks
    private const val HEADER_SIZE = 80
    private const val TRIANGLE_BYTE_SIZE = 50
    private const val BINARY_HEADER_TOTAL = 84

    private val triangleBuffer = ByteBuffer.allocateDirect(TRIANGLE_BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private val floatTemp = FloatArray(3)

    private fun safeTriangleCap(): Int {
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val budgetBytes = (maxHeapBytes * 0.22).toLong() // زودناها شوية
        val bytesPerTriangle = 72L
        val cap = budgetBytes / bytesPerTriangle
        return cap.coerceIn(500_000L, 8_000_000L).toInt() // حد اعلى اكبر عشان ميكسرش الموديل
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var remaining = length
        var pos = offset
        while (remaining > 0) {
            val read = input.read(buffer, pos, remaining)
            if (read == -1) throw STLParseException("Unexpected EOF")
            remaining -= read
            pos += read
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                val discard = ByteArray(minOf(8192, remaining).toInt())
                val read = input.read(discard)
                if (read == -1) throw STLParseException("Unexpected EOF while skipping")
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
    }

    fun parse(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): STLModel {
        val resolver = context.contentResolver

        val fileSize: Long = resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 &&!cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } else -1L
        }?: -1L

        val actualSize = if (fileSize > 0) fileSize else
            resolver.openInputStream(uri)?.use { stream ->
                var count = 0L
                val buf = ByteArray(8192)
                var n = stream.read(buf)
                while (n >= 0) { count += n; n = stream.read(buf) }
                count
            }?: throw STLParseException(context.getString(R.string.error_stl_open_failed))

        if (actualSize == 0L) throw STLParseException(context.getString(R.string.error_stl_empty))
        if (actualSize > MAX_FILE_SIZE) throw STLParseException(context.getString(R.string.error_stl_too_large))

        val headerProbe = ByteArray(minOf(2048, actualSize.toInt()))
        resolver.openInputStream(uri)?.use { stream ->
            readFully(stream, headerProbe, 0, headerProbe.size)
        }?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        return if (isAsciiSTLProfessional(headerProbe, actualSize)) {
            parseAsciiStreaming(context, uri, actualSize, onProgress)
        } else {
            parseBinaryOptimized(context, uri, actualSize, onProgress)
        }
    }

    private fun isAsciiSTLProfessional(headerBytes: ByteArray, fileSize: Long): Boolean {
        if (fileSize < BINARY_HEADER_TOTAL) return true
        try {
            val triCount = ByteBuffer.wrap(headerBytes, HEADER_SIZE, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (triCount > 0) {
                val expected = BINARY_HEADER_TOTAL.toLong() + triCount.toLong() * TRIANGLE_BYTE_SIZE
                if (expected == fileSize) return false
            }
        } catch (_: Exception) {}
        val sampleLen = minOf(headerBytes.size, 2048)
        var nullCount = 0
        for (i in 0 until sampleLen) if (headerBytes[i].toInt() == 0) nullCount++
        if (nullCount.toDouble() / sampleLen > 0.1) return false
        val sample = String(headerBytes, 0, sampleLen, Charsets.US_ASCII).lowercase()
        return sample.startsWith("solid") && sample.contains("facet normal")
    }

    private fun parseBinaryOptimized(context: Context, uri: Uri, fileSize: Long, onProgress: (Int) -> Unit): STLModel {
        if (fileSize < BINARY_HEADER_TOTAL) throw STLParseException(context.getString(R.string.error_stl_binary_corrupt))
        val resolver = context.contentResolver
        val rawHeader = ByteArray(BINARY_HEADER_TOTAL)
        resolver.openInputStream(uri)?.use { stream -> readFully(stream, rawHeader, 0, BINARY_HEADER_TOTAL) }
          ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        val triangleCount = ByteBuffer.wrap(rawHeader, HEADER_SIZE, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (triangleCount <= 0) throw STLParseException(context.getString(R.string.error_stl_no_valid_triangles))
        val expectedSize = BINARY_HEADER_TOTAL.toLong() + triangleCount.toLong() * TRIANGLE_BYTE_SIZE
        if (expectedSize > fileSize) throw STLParseException(context.getString(R.string.error_stl_triangle_mismatch, triangleCount))

        val maxTriangles = safeTriangleCap()
        val stride = if (triangleCount > maxTriangles) kotlin.math.ceil(triangleCount.toDouble() / maxTriangles).toInt() else 1
        val keptCapacity = (triangleCount + stride - 1) / stride
        val vertices = FloatArray(keptCapacity * 9)
        val normals = FloatArray(keptCapacity * 9)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var vIdx = 0; var keptTriangles = 0
        val progressStep = maxOf(triangleCount / 100, 500)
        var lastReportedPercent = -1

        resolver.openInputStream(uri)?.use { rawStream ->
            val stream = BufferedInputStream(rawStream, CHUNK_SIZE)
            skipFully(stream, HEADER_SIZE.toLong())
            for (t in 0 until triangleCount) {
                readFully(stream, triangleBuffer.array(), 0, TRIANGLE_BYTE_SIZE)
                triangleBuffer.position(0)
                val nx = triangleBuffer.float
                val ny = triangleBuffer.float
                val nz = triangleBuffer.float
                val keepThis = (t % stride == 0) && keptTriangles < keptCapacity
                for (v in 0 until 3) {
                    val x = triangleBuffer.float
                    val y = triangleBuffer.float
                    val z = triangleBuffer.float
                    if (keepThis) {
                        vertices[vIdx] = x; vertices[vIdx + 1] = y; vertices[vIdx + 2] = z
                        normals[vIdx] = nx; normals[vIdx + 1] = ny; normals[vIdx + 2] = nz
                        vIdx += 3
                    }
                    if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                    if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
                }
                triangleBuffer.short
                if (keepThis) keptTriangles++
                if (t % progressStep == 0 || t == triangleCount - 1) {
                    val percent = (((t + 1).toLong() * 100L) / triangleCount).toInt().coerceIn(0, 100)
                    if (percent!= lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
                }
            }
        }?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        return STLModel(
            vertices = if (keptTriangles == keptCapacity) vertices else vertices.copyOf(keptTriangles * 9),
            normals = if (keptTriangles == keptCapacity) normals else normals.copyOf(keptTriangles * 9),
            triangleCount = keptTriangles,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (keptTriangles % 2 == 0)
        )
    }

    private fun parseAsciiStreaming(context: Context, uri: Uri, fileSize: Long, onProgress: (Int) -> Unit): STLModel {
        val resolver = context.contentResolver
        val maxTriangles = safeTriangleCap()
        val estimatedTriangleCount = maxOf(1L, fileSize / 220L)
        val stride = if (estimatedTriangleCount > maxTriangles) kotlin.math.ceil(estimatedTriangleCount.toDouble() / maxTriangles).toInt() else 1
        val vertexList = ArrayList<Float>(minOf(1_000_000, maxTriangles * 9))
        val normalList = ArrayList<Float>(minOf(1_000_000, maxTriangles * 9))
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var curNx = 0f; var curNy = 0f; var curNz = 0f
        var triangleCount = 0; var keptTriangleCount = 0; var facetIndex = -1
        var storeCurrentFacet = true; var vertsInCurrentFacet = 0

        resolver.openInputStream(uri)?.use { rawStream ->
            var bytesRead = 0L; var lastReportedPercent = -1
            val countingStream = object : InputStream() {
                override fun read(): Int {
                    val r = rawStream.read()
                    if (r >= 0) bytesRead++
                    return r
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = rawStream.read(b, off, len)
                    if (n > 0) {
                        bytesRead += n
                        if (fileSize > 0) {
                            val percent = ((bytesRead * 100L) / fileSize).toInt().coerceIn(0, 100)
                            if (percent!= lastReportedPercent) { lastReportedPercent = percent; onProgress(percent) }
                        }
                    }
                    return n
                }
            }
            val bufferedStream = BufferedInputStream(countingStream, CHUNK_SIZE)
            bufferedStream.bufferedReader().use { lineReader ->
                lineReader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (line.startsWith("facet", ignoreCase = true)) {
                        parseFacetNormal(line, floatTemp)
                        curNx = floatTemp[0]; curNy = floatTemp[1]; curNz = floatTemp[2]
                        vertsInCurrentFacet = 0; facetIndex++
                        storeCurrentFacet = (facetIndex % stride == 0) && keptTriangleCount < maxTriangles
                    } else if (line.startsWith("vertex", ignoreCase = true)) {
                        parseVertex(line, floatTemp)
                        val x = floatTemp[0]; val y = floatTemp[1]; val z = floatTemp[2]
                        if (storeCurrentFacet) {
                            vertexList.add(x); vertexList.add(y); vertexList.add(z)
                            normalList.add(curNx); normalList.add(curNy); normalList.add(curNz)
                        }
                        if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                        if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
                        vertsInCurrentFacet++
                    } else if (line.startsWith("endfacet", ignoreCase = true)) {
                        if (vertsInCurrentFacet == 3) {
                            triangleCount++
                            if (storeCurrentFacet) keptTriangleCount++
                        }
                    }
                }
            }
        }?: throw STLParseException(context.getString(R.string.error_stl_read_failed))
        if (triangleCount == 0) throw STLParseException(context.getString(R.string.error_stl_ascii_no_triangles))
        onProgress(100)
        return STLModel(
            vertices = vertexList.toFloatArray(),
            normals = normalList.toFloatArray(),
            triangleCount = keptTriangleCount,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (keptTriangleCount % 2 == 0)
        )
    }

    private fun parseFacetNormal(line: String, out: FloatArray) {
        var idx = 0; var tokenStart = -1; var tokenCount = 0
        while (idx <= line.length) {
            val c = if (idx < line.length) line[idx] else ' '
            if (c > ' ') { if (tokenStart == -1) tokenStart = idx }
            else {
                if (tokenStart!= -1) {
                    val token = line.substring(tokenStart, idx)
                    if (tokenCount >= 2) out[tokenCount - 2] = token.toFloatOrNull()?: 0f
                    tokenCount++; tokenStart = -1
                    if (tokenCount >= 5) break
                }
            }
            idx++
        }
    }

    private fun parseVertex(line: String, out: FloatArray) {
        var idx = 0; var tokenStart = -1; var tokenCount = 0
        while (idx <= line.length) {
            val c = if (idx < line.length) line[idx] else ' '
            if (c > ' ') { if (tokenStart == -1) tokenStart = idx }
            else {
                if (tokenStart!= -1) {
                    val token = line.substring(tokenStart, idx)
                    if (tokenCount >= 1) out[tokenCount - 1] = token.toFloatOrNull()?: 0f
                    tokenCount++; tokenStart = -1
                    if (tokenCount >= 4) break
                }
            }
            idx++
        }
    }
}