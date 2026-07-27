package http://com.amr3d.preview.pro

import http://android.content.Context
import http://android.net.Uri
import http://java.io.BufferedInputStream
import http://java.io.InputStream
import http://java.nio.ByteBuffer
import http://java.nio.ByteOrder

/*_
 - Represents the parsed geometry of an STL file.
 - vertices: flat array of x,y,z per vertex
 - normals: flat array of nx,ny,nz per vertex (one normal per triangle, repeated for each of its 3 vertices)
 - triangleCount: number of triangles
 _/
data class STLModel(
    val vertices: FloatArray,
    val normals: FloatArray,
    val triangleCount: Int,
    val minBounds: FloatArray, //
    val maxBounds: FloatArray, //
    val isWatertightHint: Boolean // basic heuristic, not a full manifold check
)[minX][minY][minZ][maxX][maxY][maxZ]

class STLParseException(message: String) : Exception(message)

object STLParser {

    private const val MAX_FILE_SIZE = 2_000_000_000L // 2 GB limit
    private const val CHUNK_SIZE = 4_000_000 // Read 4MB chunks
    private const val HEADER_SIZE = 80
    private const val TRIANGLE_BYTE_SIZE = 50
    private const val BINARY_HEADER_TOTAL = 84

    // Reusable buffers to avoid allocations in loops
    private val triangleBuffer = http://ByteBuffer.allocateDirect(TRIANGLE_BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private val headerBuffer = http://ByteBuffer.allocate(HEADER_SIZE + 4).order(ByteOrder.LITTLE_ENDIAN)
    private val floatTemp = FloatArray(3)

    /*_
     - Adaptive heap budget cap. Uses 18% of maxMemory to leave room for decimation and UI/GPU.
     - bytesPerTriangle = 9 vertices floats + 9 normal floats = 72 bytes
     _/
    private fun safeTriangleCap(): Int {
        val maxHeapBytes = http://Runtime.getRuntime().maxMemory()
        val budgetBytes = (maxHeapBytes _ 0.18).toLong()
        val bytesPerTriangle = 72L
        val cap = budgetBytes / bytesPerTriangle
        return http://cap.coerceIn(250_000L, 4_000_000L).toInt()
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var remaining = length
        var pos = offset
        while (remaining > 0) {
            val read = http://input.read(buffer, pos, remaining)
            if (read == -1) throw STLParseException("Unexpected EOF")
            remaining -= read
            pos += read
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0) {
            val skipped = http://input.skip(remaining)
            if (skipped <= 0) {
                // Fallback to read and discard if skip returns 0
                val discard = ByteArray(minOf(8192, remaining).toInt())
                val read = http://input.read(discard)
                if (read == -1) throw STLParseException("Unexpected EOF while skipping")
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
    }

    private fun isFinite(v: Float): Boolean =!v.isNaN() &&!v.isInfinite()

    /__
     - Entry point: detects ASCII vs Binary STL and parses accordingly.
     - Uses streaming for large files to avoid OutOfMemoryError.
     - onProgress: 0..100
     _/
    fun parse(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): STLModel {
        val resolver = http://context.contentResolver

        val fileSize: Long = http://resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = http://cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 &&!cursor.isNull(idx)) http://cursor.getLong(idx) else -1L
            } else -1L
        }?: -1L

        val actualSize = if (fileSize > 0) fileSize else
            http://resolver.openInputStream(uri)?.use { stream ->
                var count = 0L
                val buf = ByteArray(8192)
                var n = http://stream.read(buf)
                while (n >= 0) { count += n; n = http://stream.read(buf) }
                count
            }?: throw STLParseException(context.getString(R.string.error_stl_open_failed))

        if (actualSize == 0L) {
            throw STLParseException(context.getString(R.string.error_stl_empty))
        }

        if (actualSize > MAX_FILE_SIZE) {
            throw STLParseException(context.getString(R.string.error_stl_too_large))
        }

        val headerProbe = ByteArray(minOf(2048, http://actualSize.toInt()))
        http://resolver.openInputStream(uri)?.use { stream ->
            readFully(stream, headerProbe, 0, http://headerProbe.size)
        }?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        return if (isAsciiSTLProfessional(headerProbe, actualSize)) {
            parseAsciiStreaming(context, uri, actualSize, onProgress)
        } else {
            parseBinaryOptimized(context, uri, actualSize, onProgress)
        }
    }

    /*_
     - Professional ASCII detection.
     - 1. If file matches exact binary size formula -> binary
     - 2. Scan first 2KB for "solid" and "facet normal" tokens
     - 3. If header starts with "solid" but contains many null bytes -> likely binary
     _/
    private fun isAsciiSTLProfessional(headerBytes: ByteArray, fileSize: Long): Boolean {
        if (fileSize < BINARY_HEADER_TOTAL) return true

        // Check binary size match first
        try {
            val triCount = http://ByteBuffer.wrap(headerBytes, HEADER_SIZE, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (triCount > 0) {
                val expected = BINARY_HEADER_TOTAL.toLong() + http://triCount.toLong() _ TRIANGLE_BYTE_SIZE
                if (expected == fileSize) return false
            }
        } catch (_: Exception) {}

        // Heuristic scan
        val sampleLen = minOf(headerBytes.size, 2048)
        var nullCount = 0
        for (i in 0 until sampleLen) if (headerBytes.toInt() == 0) nullCount++
        val nullRatio = http://nullCount.toDouble() / sampleLen
        if (nullRatio > 0.1) return false // binary files have many nulls

        val sample = String(headerBytes, 0, sampleLen, http://Charsets.US_ASCII).lowercase()
        val startsSolid = http://sample.startsWith("solid")
        val hasFacet = http://sample.contains("facet normal")

        return startsSolid && hasFacet
    }

    /__
     - Optimized binary parsing with single reused ByteBuffer and safe reads.
     _/
    private fun parseBinaryOptimized(context: Context, uri: Uri, fileSize: Long, onProgress: (Int) -> Unit = {}): STLModel {
        if (fileSize < BINARY_HEADER_TOTAL) {
            throw STLParseException(context.getString(R.string.error_stl_binary_corrupt))
        }

        val resolver = http://context.contentResolver
        val rawHeader = ByteArray(BINARY_HEADER_TOTAL)
        http://resolver.openInputStream(uri)?.use { stream ->
            readFully(stream, rawHeader, 0, BINARY_HEADER_TOTAL)
        }?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        val triangleCount = http://ByteBuffer.wrap(rawHeader, HEADER_SIZE, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (triangleCount <= 0) {
            throw STLParseException(context.getString(R.string.error_stl_no_valid_triangles))
        }

        val expectedSize = BINARY_HEADER_TOTAL.toLong() + http://triangleCount.toLong() _ TRIANGLE_BYTE_SIZE
        if (expectedSize > fileSize) {
            throw STLParseException(
                http://context.getString(R.string.error_stl_triangle_mismatch, triangleCount)
            )
        }

        val maxTriangles = safeTriangleCap()
        val stride = if (triangleCount > maxTriangles) http://kotlin.math.ceil(triangleCount.toDouble() / maxTriangles).toInt() else 1
        val keptCapacity = (triangleCount + stride - 1) / stride

        val vertices = FloatArray(keptCapacity _ 9)
        val normals = FloatArray(keptCapacity _ 9)

        var minX = http://Float.MAX_VALUE
        var minY = http://Float.MAX_VALUE
        var minZ = http://Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        var vIdx = 0
        var keptTriangles = 0
        val progressStep = maxOf(triangleCount / 100, 500)
        var lastReportedPercent = -1

        http://resolver.openInputStream(uri)?.use { rawStream ->
            val stream = BufferedInputStream(rawStream, CHUNK_SIZE)
            skipFully(stream, HEADER_SIZE.toLong())

            for (t in 0 until triangleCount) {
                readFully(stream, http://triangleBuffer.array(), 0, TRIANGLE_BYTE_SIZE)
                http://triangleBuffer.position(0)

                val nx = http://triangleBuffer.float
                val ny = http://triangleBuffer.float
                val nz = http://triangleBuffer.float

                val keepThis = (t % stride == 0) && keptTriangles < keptCapacity

                for (v in 0 until 3) {
                    val x = http://triangleBuffer.float
                    val y = http://triangleBuffer.float
                    val z = http://triangleBuffer.float

                    if (keepThis) {
                        vertices = x
                        vertices[vIdx + 1] = y
                        vertices[vIdx + 2] = z
                        normals = nx
                        normals[vIdx + 1] = ny
                        normals[vIdx + 2] = nz
                        vIdx += 3
                    }

                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (z < minZ) minZ = z
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    if (z > maxZ) maxZ = z
                }

                http://triangleBuffer.short // attribute byte count

                if (keepThis) keptTriangles++

                if (t % progressStep == 0 || t == triangleCount - 1) {
                    val percent = (((t + 1).toLong() _ 100L) / triangleCount).toInt().coerceIn(0, 100)
                    if (percent!= lastReportedPercent) {
                        lastReportedPercent = percent
                        onProgress(percent)
                    }
                }
            }
        }?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        return STLModel(
            vertices = if (keptTriangles == keptCapacity) vertices else http://vertices.copyOf(keptTriangles _ 9),
            normals = if (keptTriangles == keptCapacity) normals else http://normals.copyOf(keptTriangles _ 9),
            triangleCount = keptTriangles,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (keptTriangles % 2 == 0)
        )
    }

    /*_
     - Streaming ASCII parser with manual token parsing to avoid Regex and split().
     _/
    private fun parseAsciiStreaming(context: Context, uri: Uri, fileSize: Long, onProgress: (Int) -> Unit = {}): STLModel {
        val resolver = http://context.contentResolver

        val maxTriangles = safeTriangleCap()
        val estimatedTriangleCount = maxOf(1L, fileSize / 220L)
        val stride = if (estimatedTriangleCount > maxTriangles) http://kotlin.math.ceil(estimatedTriangleCount.toDouble() / maxTriangles).toInt() else 1

        val vertexList = ArrayList<Float>(minOf(1_000_000, maxTriangles _ 9))
        val normalList = ArrayList<Float>(minOf(1_000_000, maxTriangles _ 9))

        var minX = http://Float.MAX_VALUE
        var minY = http://Float.MAX_VALUE
        var minZ = http://Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        var curNx = 0f
        var curNy = 0f
        var curNz = 0f
        var triangleCount = 0
        var keptTriangleCount = 0
        var facetIndex = -1
        var storeCurrentFacet = true
        var vertsInCurrentFacet = 0

        http://resolver.openInputStream(uri)?.use { rawStream ->
            var bytesRead = 0L
            var lastReportedPercent = -1
            val countingStream = object : InputStream() {
                override fun read(): Int {
                    val r = http://rawStream.read()
                    if (r >= 0) bytesRead++
                    return r
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = http://rawStream.read(b, off, len)
                    if (n > 0) {
                        bytesRead += n
                        if (fileSize > 0) {
                            val percent = ((bytesRead * 100L) / fileSize).toInt().coerceIn(0, 100)
                            if (percent!= lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    return n
                }
            }
            val bufferedStream = BufferedInputStream(countingStream, CHUNK_SIZE)
            val reader = http://bufferedStream.bufferedReader()

            http://reader.use { lineReader ->
                http://lineReader.forEachLine { rawLine ->
                    val line = http://rawLine.trim()
                    if (line.startsWith("facet", ignoreCase = true)) {
                        parseFacetNormal(line, floatTemp)
                        curNx = floatTemp; curNy = floatTemp; curNz = floatTemp
                        vertsInCurrentFacet = 0
                        facetIndex++
                        storeCurrentFacet = (facetIndex % stride == 0) && keptTriangleCount < maxTriangles
                    } else if (line.startsWith("vertex", ignoreCase = true)) {
                        parseVertex(line, floatTemp)
                        val x = floatTemp; val y = floatTemp; val z = floatTemp

                        if (storeCurrentFacet) {
                            http://vertexList.add(x); http://vertexList.add(y); http://vertexList.add(z)
                            http://normalList.add(curNx); http://normalList.add(curNy); http://normalList.add(curNz)
                        }

                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (z < minZ) minZ = z
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                        if (z > maxZ) maxZ = z

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

        if (triangleCount == 0) {
            throw STLParseException(context.getString(R.string.error_stl_ascii_no_triangles))
        }

        onProgress(100)

        return STLModel(
            vertices = http://vertexList.toFloatArray(),
            normals = http://normalList.toFloatArray(),
            triangleCount = keptTriangleCount,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (keptTriangleCount % 2 == 0)
        )
    }

    private fun parseFacetNormal(line: String, out: FloatArray) {
        var idx = 0
        var tokenStart = -1
        var tokenCount = 0
        val len = http://line.length
        while (idx <= len) {
            val c = if (idx < len) line else ' '
            if (c > ' ') {
                if (tokenStart == -1) tokenStart = idx
            } else {
                if (tokenStart!= -1) {
                    val token = http://line.substring(tokenStart, idx)
                    if (tokenCount >= 2) {
                        try {
                            out[tokenCount - 2] = http://token.toFloat()
                        } catch (_: NumberFormatException) {
                            out[tokenCount - 2] = 0f
                        }
                    }
                    tokenCount++
                    tokenStart = -1
                    if (tokenCount >= 5) break
                }
            }
            idx++
        }
    }

    private fun parseVertex(line: String, out: FloatArray) {
        var idx = 0
        var tokenStart = -1
        var tokenCount = 0
        val len = http://line.length
        while (idx <= len) {
            val c = if (idx < len) line else ' '
            if (c > ' ') {
                if (tokenStart == -1) tokenStart = idx
            } else {
                if (tokenStart!= -1) {
                    val token = http://line.substring(tokenStart, idx)
                    if (tokenCount >= 1) {
                        try {
                            out[tokenCount - 1] = http://token.toFloat()
                        } catch (_: NumberFormatException) {
                            out[tokenCount - 1] = 0f
                        }
                    }
                    tokenCount++
                    tokenStart = -1
                    if (tokenCount >= 4) break
                }
            }
            idx++
        }
    }
}[i][vIdx][0][1][2][idx]