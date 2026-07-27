package com.amr3d.preview.pro

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
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
    // لا حد ثابت للـ ASCII — يعتمد على RAM الجهاز

    /** ⚠️ حد أمان على عدد المثلثات اللي بتترفع فعليًا في الذاكرة وقت القراءة نفسها
     * — بغض النظر عن إعداد الجودة أو موافقة المستخدم على التبسيط. المشكلة اللي
     * بيحلها: قبل كده كان التبسيط (MeshDecimator) بيتنفذ بعد ما القراءة الخام
     * تخلص بالكامل — يعني ملف ضخم جدًا كان ممكن يعمل OutOfMemoryError أثناء
     * التخصيص/القراءة الخام نفسها، قبل ما التبسيط ياخد فرصته أصلاً. دلوقتي القراءة
     * نفسها بتوقف عن تخزين كل مثلث لما العدد يتجاوز الحد ده (بتاخد عينة بانتظام
     * Stride Sampling بدل ما تخزن الكل) — فالذاكرة المحجوزة أصلاً بتفضل محدودة
     * دايمًا، مهما كان حجم الملف. الحدود الخارجية (Bounding Box) بتتحسب من كل
     * مثلث في الملف بالكامل (حتى المتجاهل من التخزين) عشان الأبعاد الحقيقية
     * للقطعة تفضل دقيقة 100% حتى مع العينة دي.
     *
     * ⚠️ الحد ده مش رقم ثابت — بيتحسب حسب أقصى ذاكرة فعليًا متاحة للتطبيق على
     * الجهاز نفسه (Runtime.getRuntime().maxMemory())، مش تخمين واحد يفترض نفس
     * القيمة لكل الأجهزة. بناخد جزء صغير بس منها (18%) كهامش أمان كبير، عشان
     * يفضل فاضي مساحة كافية للنسخ المؤقتة أثناء تصحيح المحور والتبسيط الإضافي،
     * وذاكرة الواجهة والـ GPU. الأولوية المطلقة: الملف لازم يفتح، حتى لو بتفاصيل
     * أقل من كده على الأجهزة الضعيفة في الرام. */
    private fun safeTriangleCap(): Int {
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val budgetBytes = (maxHeapBytes * 0.18).toLong()
        val bytesPerTriangle = 72L // 9 floats vertices + 9 floats normals × 4 بايت لكل float
        val cap = (budgetBytes / bytesPerTriangle)
        return cap.coerceIn(250_000L, 4_000_000L).toInt()
    }

    /**
     * Entry point: detects ASCII vs Binary STL and parses accordingly.
     * Uses streaming for large files to avoid OutOfMemoryError.
     * onProgress: 0..100 — نسبة تقدم القراءة الفعلية (بيتنادى من خيط IO، لازم تحدّث الـ UI على الـ main thread)
     */
    fun parse(context: Context, uri: Uri, onProgress: (Int) -> Unit = {}): STLModel {
        val resolver = context.contentResolver

        // ✅ إصلاح: استخدام ContentResolver.query للحصول على الحجم الحقيقي
        val fileSize: Long = resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } else -1L
        } ?: -1L

        val actualSize = if (fileSize > 0) fileSize else
            resolver.openInputStream(uri)?.use { stream ->
                var count = 0L; val buf = ByteArray(8192)
                var n = stream.read(buf)
                while (n >= 0) { count += n; n = stream.read(buf) }
                count
            } ?: throw STLParseException(context.getString(R.string.error_stl_open_failed))

        if (actualSize == 0L) {
            throw STLParseException(context.getString(R.string.error_stl_empty))
        }

        if (actualSize > MAX_FILE_SIZE) {
            throw STLParseException(context.getString(R.string.error_stl_too_large))
        }

        // Read header only (512 bytes) to detect format
        val headerBytes = ByteArray(minOf(512, actualSize.toInt()))
        resolver.openInputStream(uri)?.use { stream ->
            stream.read(headerBytes)
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        return if (isAsciiSTL(headerBytes, actualSize)) {
            parseAsciiStreaming(context, uri, actualSize, onProgress)
        } else {
            parseBinaryOptimized(context, uri, actualSize, onProgress)
        }
    }

    /**
     * Heuristic: ASCII STL files start with "solid" (case-insensitive) AND contain "facet"
     * shortly after. Some binary files also start with "solid" in their header by mistake,
     * so we double check for the "facet normal" token, and also validate via expected
     * binary size as a fallback.
     */
    private fun isAsciiSTL(headerBytes: ByteArray, fileSize: Long): Boolean {
        val header = String(headerBytes, Charsets.US_ASCII).trim()

        if (!header.lowercase().startsWith("solid")) {
            return false
        }

        // Check if the binary-size formula matches; if it matches well, treat as binary
        if (fileSize >= 84) {
            try {
                val triCountFromHeader = ByteBuffer.wrap(headerBytes, 80, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                val expectedBinarySize = 84L + (triCountFromHeader.toLong() * 50L)
                if (expectedBinarySize == fileSize) {
                    return false
                }
            } catch (e: Exception) {
                // If parsing fails, assume ASCII
            }
        }

        // Look for "facet" within the header to confirm ASCII structure
        val sample = String(headerBytes, Charsets.US_ASCII)
        return sample.contains("facet", ignoreCase = true)
    }

    /**
     * Optimized binary parsing with memory-efficient chunk processing.
     */
    private fun parseBinaryOptimized(context: Context, uri: Uri, fileSize: Long, onProgress: (Int) -> Unit = {}): STLModel {
        if (fileSize < 84) {
            throw STLParseException(context.getString(R.string.error_stl_binary_corrupt))
        }

        val resolver = context.contentResolver
        val headerBuffer = ByteArray(84)

        resolver.openInputStream(uri)?.use { stream ->
            stream.read(headerBuffer)
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        val triangleCount = ByteBuffer.wrap(headerBuffer, 80, 4)
            .order(ByteOrder.LITTLE_ENDIAN).int

        val expectedSize = 84L + (triangleCount.toLong() * 50L)
        if (expectedSize > fileSize) {
            throw STLParseException(
                context.getString(R.string.error_stl_triangle_mismatch, triangleCount)
            )
        }
        if (triangleCount <= 0) {
            throw STLParseException(context.getString(R.string.error_stl_no_valid_triangles))
        }

        // ── حد الأمان: لو عدد المثلثات هيتجاوز الحد الأقصى المسموح نخزنه في الذاكرة
        // دفعة واحدة (محسوب حسب رام الجهاز نفسه)، بناخد عينة بانتظام (Stride) بدل
        // ما نخزن كل مثلث — كده حجم المصفوفات المحجوزة يفضل محدود دايمًا ──
        val maxTriangles = safeTriangleCap()
        val stride = if (triangleCount > maxTriangles)
            Math.ceil(triangleCount.toDouble() / maxTriangles).toInt()
        else 1
        val keptCapacity = (triangleCount + stride - 1) / stride

        // Pre-allocate based on the (possibly-reduced) kept triangle count فقط، مش
        // العدد الخام الكامل — ده اللي فعليًا بيمنع الـ OutOfMemoryError
        val vertices = FloatArray(keptCapacity * 3 * 3)
        val normals = FloatArray(keptCapacity * 3 * 3)

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        var vIdx = 0
        var keptTriangles = 0
        val triangleBytes = ByteArray(50) // 50 bytes per triangle
        // بنبعت تحديث تقدم كل 1% (أو كل 500 مثلث كحد أدنى) عشان منغرقش الـ UI thread بتحديثات كتير
        val progressStep = maxOf(triangleCount / 100, 500)
        var lastReportedPercent = -1

        resolver.openInputStream(uri)?.use { stream ->
            stream.skip(84) // Skip header

            for (t in 0 until triangleCount) {
                if (stream.read(triangleBytes) != 50) {
                    throw STLParseException(context.getString(R.string.error_stl_corrupt_triangle, t))
                }

                val buffer = ByteBuffer.wrap(triangleBytes).order(ByteOrder.LITTLE_ENDIAN)

                val nx = buffer.float
                val ny = buffer.float
                val nz = buffer.float

                // بنخزّن هذا المثلث بس لو جاله دوره في العينة (وقعنا لسه في حدود
                // المساحة المحجوزة)، لكن بنحسب حدوده (Bounds) دايمًا حتى لو مش
                // هيتخزن — عشان الأبعاد الخارجية الحقيقية للقطعة تفضل دقيقة 100%
                val keepThis = (t % stride == 0) && keptTriangles < keptCapacity

                // 3 vertices per triangle
                for (v in 0 until 3) {
                    val x = buffer.float
                    val y = buffer.float
                    val z = buffer.float

                    if (keepThis) {
                        vertices[vIdx] = x
                        vertices[vIdx + 1] = y
                        vertices[vIdx + 2] = z

                        normals[vIdx] = nx
                        normals[vIdx + 1] = ny
                        normals[vIdx + 2] = nz

                        vIdx += 3
                    }

                    // Update bounds — من كل مثلث في الملف، مش بس المخزّن
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (z < minZ) minZ = z
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    if (z > maxZ) maxZ = z
                }

                if (keepThis) keptTriangles++

                // Skip attribute byte count (2 bytes)
                buffer.short

                if (t % progressStep == 0 || t == triangleCount - 1) {
                    val percent = (((t + 1).toLong() * 90L) / triangleCount).toInt() // نحجز 0-90% للقراءة، والباقي للتجهيز
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        onProgress(percent)
                    }
                }
            }
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        return STLModel(
            vertices = if (keptTriangles == keptCapacity) vertices else vertices.copyOf(keptTriangles * 9),
            normals = if (keptTriangles == keptCapacity) normals else normals.copyOf(keptTriangles * 9),
            triangleCount = keptTriangles,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (keptTriangles % 2 == 0)
        )
    }

    /**
     * Streaming ASCII parser to handle large ASCII files without loading entire file into memory.
     */
    private fun parseAsciiStreaming(context: Context, uri: Uri, fileSize: Long, onProgress: (Int) -> Unit = {}): STLModel {
        val resolver = context.contentResolver
        // ── حد الأمان (نفس فكرة parseBinaryOptimized): بما إن عدد المثلثات في
        // ASCII مش معروف مقدمًا، بنقدّره تقريبيًا من حجم الملف (متوسط ~220 بايت
        // لكل مثلث في صياغة ASCII القياسية) عشان نحسب Stride مناسب من الأول ──
        val maxTriangles = safeTriangleCap()
        val estimatedTriangleCount = maxOf(1L, fileSize / 220L)
        val stride = if (estimatedTriangleCount > maxTriangles)
            Math.ceil(estimatedTriangleCount.toDouble() / maxTriangles).toInt()
        else 1

        val vertexList = ArrayList<Float>(minOf(1_000_000, (maxTriangles * 9)))
        val normalList = ArrayList<Float>(minOf(4_000_000, (maxTriangles * 9)))

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
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

        resolver.openInputStream(uri)?.use { rawStream ->
            // Wrapper بيعدّ البايتات المقروءة فعلياً عشان نحسب نسبة التقدم الحقيقية من حجم الملف
            var bytesRead = 0L
            var lastReportedPercent = -1
            val countingStream = object : java.io.InputStream() {
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
                            val percent = ((bytesRead * 90L) / fileSize).toInt().coerceIn(0, 90)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    return n
                }
            }
            val bufferedStream = BufferedInputStream(countingStream, 8192)
            val reader = bufferedStream.bufferedReader()

            reader.use { lineReader ->
                lineReader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    when {
                        line.startsWith("facet normal", ignoreCase = true) -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 5) {
                                try {
                                    curNx = parts[2].toFloat()
                                    curNy = parts[3].toFloat()
                                    curNz = parts[4].toFloat()
                                } catch (e: NumberFormatException) {
                                    curNx = 0f
                                    curNy = 0f
                                    curNz = 0f
                                }
                            }
                            vertsInCurrentFacet = 0
                            facetIndex++
                            // ── حد الأمان: نقرر تخزين المثلث ده من عدمه بناءً على الـ
                            // Stride المحسوب سلفًا، وبرضو نوقف التخزين لو وصلنا لسقف
                            // الأمان الأقصى (maxTriangles، محسوب حسب رام الجهاز) حتى
                            // لو الملف فعليًا أكبر من التقدير المبدئي ──
                            storeCurrentFacet = (facetIndex % stride == 0) &&
                                keptTriangleCount < maxTriangles
                        }
                        line.startsWith("vertex", ignoreCase = true) -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 4) {
                                try {
                                    val x = parts[1].toFloat()
                                    val y = parts[2].toFloat()
                                    val z = parts[3].toFloat()

                                    if (storeCurrentFacet) {
                                        vertexList.add(x)
                                        vertexList.add(y)
                                        vertexList.add(z)
                                        normalList.add(curNx)
                                        normalList.add(curNy)
                                        normalList.add(curNz)
                                    }

                                    // الحدود الخارجية بتتحسب من كل مثلث في الملف (حتى
                                    // المتجاهل من التخزين) عشان الأبعاد الحقيقية تفضل دقيقة
                                    if (x < minX) minX = x
                                    if (y < minY) minY = y
                                    if (z < minZ) minZ = z
                                    if (x > maxX) maxX = x
                                    if (y > maxY) maxY = y
                                    if (z > maxZ) maxZ = z

                                    vertsInCurrentFacet++

                                } catch (e: NumberFormatException) {
                                    throw STLParseException(context.getString(R.string.error_stl_invalid_value, line))
                                }
                            }
                        }
                        line.startsWith("endfacet", ignoreCase = true) -> {
                            if (vertsInCurrentFacet == 3) {
                                triangleCount++
                                if (storeCurrentFacet) keptTriangleCount++
                            }
                        }
                    }
                }
            }
        } ?: throw STLParseException(context.getString(R.string.error_stl_read_failed))

        if (triangleCount == 0) {
            throw STLParseException(context.getString(R.string.error_stl_ascii_no_triangles))
        }

        return STLModel(
            vertices = vertexList.toFloatArray(),
            normals = normalList.toFloatArray(),
            triangleCount = keptTriangleCount,
            minBounds = floatArrayOf(minX, minY, minZ),
            maxBounds = floatArrayOf(maxX, maxY, maxZ),
            isWatertightHint = (keptTriangleCount % 2 == 0)
        )
    }
}
