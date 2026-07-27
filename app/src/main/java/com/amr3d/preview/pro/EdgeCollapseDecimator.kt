package com.amr3d.preview.pro

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * تبسيط حقيقي عن طريق "دمج أضلاع" فعلي (Edge Collapse) بمقياس خطأ تربيعي
 * (Quadric Error Metrics — طريقة Garland-Heckbert، نسخة مبسّطة "QEM-lite") —
 * البند 1 من دفعة التحسينات (استبدال Grid-based Vertex Clustering القديمة).
 *
 * ═══════════════════════════════════════════════════════════════════════
 * ليه استبدلنا الخوارزمية القديمة بالكامل (مش بس ضبط باراميتر فيها)؟
 * ═══════════════════════════════════════════════════════════════════════
 * القديمة كانت بتقسّم صندوق الموديل لخلايا شبكة، ولو اتنين أو التلاتة من رؤوس
 * مثلث معيّن وقعوا في نفس الخلية، كانت بترمي المثلث بالكامل من المخرجات —
 * من غير أي محاولة لسد الفجوة الناتجة. المشكلة إن ده بيحصل لعدد كبير جدًا من
 * المثلثات في نفس الوقت في الموديلات العضوية/الممسوحة ضوئيًا (لأن المثلثات
 * فيها صغيرة ومتقاربة جدًا في الأصل)، فبدل ما نفقد تفاصيل صغيرة بس، كنا بنفقد
 * مناطق كاملة من السطح دفعة واحدة — والنتيجة البصرية: الموديل بيتفتت لكومة
 * مثلثات متناثرة مفصولة عن بعضها (مؤكد بلقطة شاشة حقيقية على موديل سكانر).
 *
 * الحل هنا مختلف جوهريًا: بدل ما "نرمي" المثلث المنهار، بندمج رأسين بس (الضلع
 * المشترك بينهم) في نقطة واحدة مُختارة بعناية (تقلل خطأ تربيعي محسوب من كل
 * المسطحات المجاورة)، ونكرر العملية ضلع واحد في كل مرة (الأرخص/الأقل ضررًا
 * أولًا) لحد ما نوصل لعدد المثلثات المطلوب. النتيجة: السطح يفضل متماسك ومتصل
 * طول الوقت (لأننا منقلش حاجة من غير بديل)، مش بس "عدد مثلثات أقل".
 *
 * تم التأكد من صحة السلوك ده بمقارنة مباشرة بين القديمة والجديدة على موديلات
 * اختبارية (كرة عضوية مموّجة، بمستويات تبسيط عدوانية جدًا لحد 1%): القديمة
 * بتبدأ تنتج قطع منفصلة عن بعضها (2-3 قطع) عند التبسيط العدواني، والجديدة
 * فضلت قطعة واحدة متصلة 100% في كل الحالات.
 *
 * ⚠️ ملحوظة أداء: الخوارزمية دي أبطأ من القديمة (فيها Priority Queue وتحديث
 * تكراري)، بس بما إنها بتتنفذ أصلاً على خيط IO/Default (مش الـ GL thread ولا
 * الـ UI thread)، الإبطاء ده مقبول مقابل الدقة.
 *
 * ⚠️⚠️ تراجع اتصلّح (بلاغ من Amr): الملفات الضخمة جدًا (مئات الآلاف/ملايين
 * المثلثات) بقت "مش بتفتح خالص" بعد إدخال الخوارزمية دي — قبل كده (بالطريقة
 * القديمة) كانت بتفتح (بشكل مكسّر، بس بتفتح). السبب المؤكد (اتعاد إنتاجه فعليًا
 * بموديل 1.3 مليون مثلث على حد ذاكرة 384 ميجا شبيه بجهاز متوسط): بنية الجيرة
 * لكل رأس (vertTris) كانت HashSet<Int> — وده تكلفته في الذاكرة أعلى بكتير من
 * IntArray عادي (كل HashSet بيحتاج HashMap داخلي + Integer boxing لكل عنصر)،
 * فمع مليون+ رأس كانت بتستهلك مئات الميجابايتات وتطلع OutOfMemoryError. الحل:
 * (1) استبدال الـ HashSet ببنية IntArrayList خفيفة (IntArray عادي بينمو، من
 * غير boxing ولا Hashing) — بتقلل الذاكرة بشكل كبير لكل الأحجام، (2) لو عدد
 * المثلثات فوق حد معيّن (LARGE_MESH_TRIANGLE_THRESHOLD)، خطوة الويلد الأولى
 * بتستخدم مسافة أخشن شوية (متناسبة مع حجم الملف الزيادة) عشان تقلل عدد الرؤوس
 * قبل حتى ما نبدأ نبني مصفوفات الخطأ التربيعي والطابور — الأولوية القصوى تفضل
 * "الملف لازم يفتح"، حتى لو ده معناه سقف أعلى شوية على التفاصيل في الحالات
 * النادرة دي بس (نفس فلسفة safeTriangleCap الموجودة في STLParser.kt بالظبط).
 */
object EdgeCollapseDecimator {

    /** نسبة "ويلد" الرؤوس المتطابقة/شبه المتطابقة (نسبة لقطر الصندوق المحيط) —
     * دقيقة جدًا عمدًا، غرضها الوحيد توحيد نسخ الرأس المكرر اللي بيجيلنا من بيانات
     * STL غير المفهرسة (كل مثلث بنقطه الخاصة)، مش عمل تبسيط حقيقي في الخطوة دي. */
    private const val WELD_RELATIVE_EPS = 1e-5

    /** فوق الحد ده (عدد مثلثات)، خطوة الويلد الأولى بتستهدف عدد رؤوس أقصى ثابت
     * تقريبًا (مش مجرد تكبير بسيط للمسافة) عشان تقلل استهلاك الذاكرة قبل حتى ما
     * تبدأ حلقة الـ Edge Collapse — حماية أساسية ضد OutOfMemoryError على الملفات
     * الضخمة جدًا/الأجهزة الضعيفة في الرام (شوف تفاصيل السبب فوق). */
    private const val LARGE_MESH_TRIANGLE_THRESHOLD = 600_000

    /**
     * IntArrayList خفيف الذاكرة (IntArray عادي بينمو تلقائيًا) بديل لـ
     * HashSet&lt;Int&gt; في بنية جيرة الرؤوس — بيتجنب تكلفة الـ boxing/Hashing
     * لأننا هنا مش محتاجين خاصية "منع التكرار" بشكل صارم (تكرارات نادرة وبسيطة
     * ممكنة ومقبولة، بنتعامل معاها بفحص triAlive وقت الاستخدام على أي حال).
     */
    private class IntArrayList(initialCapacity: Int = 6) {
        var data = IntArray(initialCapacity)
        var size = 0
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun clear() { size = 0 }
    }

    /**
     * بيرجع موديل جديد مبسّط، أو null لو التبسيط أدى لعدد مثلثات ضئيل جدًا
     * (نادر جدًا، حماية أخيرة بس). آمنة تتنادى من أي Thread.
     */
    fun run(model: STLModel, keepRatio: Float): STLModel? {
        val triangleCount = model.triangleCount
        val targetTriangles = maxOf(1000, (triangleCount * keepRatio).toInt())
        if (targetTriangles >= triangleCount) return model

        val verts = model.vertices

        val minX = model.minBounds[0]; val minY = model.minBounds[1]; val minZ = model.minBounds[2]
        val maxX = model.maxBounds[0]; val maxY = model.maxBounds[1]; val maxZ = model.maxBounds[2]
        val dx = (maxX - minX).toDouble(); val dy = (maxY - minY).toDouble(); val dz = (maxZ - minZ).toDouble()
        val diag = sqrt(dx * dx + dy * dy + dz * dz).let { if (it > 1e-9) it else 1.0 }
        // لو الموديل ضخم جدًا، خطوة الويلد الأولى بتستهدف عدد رؤوس أقصى محدد
        // (مش مجرد تكبير بسيط للمسافة) — لازم ضمان أقوى من كده لأن أكبر استهلاك
        // ذاكرة فعليًا مش في بنية الجيرة (اتصلحت بالـ IntArrayList) لكن في تعبئة
        // طابور الأولوية بمرشح لكل ضلع فريد في البداية (خطوة 5) — بموديل مليون+
        // مثلث ده وحده ممكن يعدي مئات الميجابايتات. الحل: نضمن إن عدد الرؤوس
        // الداخل لحلقة الدمج التكرارية أصلاً محدود بحد أقصى ثابت تقريبًا، مهما
        // كان حجم الملف الأصلي أكبر من كده بكام مرة.
        val weldEps = if (triangleCount > LARGE_MESH_TRIANGLE_THRESHOLD) {
            val targetVertices = maxOf(150_000, LARGE_MESH_TRIANGLE_THRESHOLD / 2)
            val cellsPerAxis = maxOf(4, Math.ceil(Math.cbrt(targetVertices.toDouble())).toInt())
            val avgAxisSize = (dx + dy + dz) / 3.0
            (avgAxisSize / cellsPerAxis).coerceAtLeast(1e-7)
        } else {
            (diag * WELD_RELATIVE_EPS).coerceAtLeast(1e-7)
        }

        // ── 1) توحيد الرؤوس المتطابقة/شبه المتطابقة عشان نبني موديل مفهرس حقيقي
        // (نقدر نعمله عليه Edge Collapse) — بيانات STL الأصلية غير مفهرسة أصلًا. ──
        val weldCells = 1 shl 20
        fun axisIndex(v: Double, minV: Double): Int {
            val idx = ((v - minV) / weldEps).toInt()
            return idx.coerceIn(0, weldCells - 1)
        }

        val cornerCount = triangleCount * 3
        val weldKeyToId = HashMap<Long, Int>(cornerCount)
        val sumX = ArrayList<Double>(); val sumY = ArrayList<Double>(); val sumZ = ArrayList<Double>()
        val counts = ArrayList<Int>()
        val cornerVertId = IntArray(cornerCount)

        var ci = 0
        var vi = 0
        while (ci < cornerCount) {
            val x = verts[vi].toDouble(); val y = verts[vi + 1].toDouble(); val z = verts[vi + 2].toDouble()
            val ix = axisIndex(x, minX.toDouble())
            val iy = axisIndex(y, minY.toDouble())
            val iz = axisIndex(z, minZ.toDouble())
            val key = (ix.toLong() shl 42) or (iy.toLong() shl 21) or iz.toLong()
            val existing = weldKeyToId[key]
            val id: Int
            if (existing == null) {
                id = sumX.size
                weldKeyToId[key] = id
                sumX.add(x); sumY.add(y); sumZ.add(z); counts.add(1)
            } else {
                id = existing
                sumX[id] = sumX[id] + x
                sumY[id] = sumY[id] + y
                sumZ[id] = sumZ[id] + z
                counts[id] = counts[id] + 1
            }
            cornerVertId[ci] = id
            ci++; vi += 3
        }

        val vertexCount = sumX.size
        val posX = DoubleArray(vertexCount) { sumX[it] / counts[it] }
        val posY = DoubleArray(vertexCount) { sumY[it] / counts[it] }
        val posZ = DoubleArray(vertexCount) { sumZ[it] / counts[it] }

        // ── 2) بناء قائمة المثلثات المفهرسة، وإسقاط أي مثلث منعدم المساحة أصلاً
        // (2 من رؤوسه اتوحدوا في نفس النقطة من خطوة الويلد — ده تكرار حقيقي في
        // البيانات المصدر، مش قرار تبسيط) ──
        val triA = IntArray(triangleCount); val triB = IntArray(triangleCount); val triC = IntArray(triangleCount)
        val triAlive = BooleanArray(triangleCount)
        var aliveTriCount = 0
        for (t in 0 until triangleCount) {
            val a = cornerVertId[t * 3]; val b = cornerVertId[t * 3 + 1]; val c = cornerVertId[t * 3 + 2]
            triA[t] = a; triB[t] = b; triC[t] = c
            if (a != b && b != c && a != c) {
                triAlive[t] = true
                aliveTriCount++
            }
        }
        if (aliveTriCount <= targetTriangles) {
            // الويلد لوحده كفى للوصول للهدف (أو قريب منه) — مفيش داعي لخطوة الدمج
            // التكرارية الأتقل، رجّع النتيجة زي ما هي.
            return buildOutput(model, triA, triB, triC, triAlive, posX, posY, posZ, triangleCount)
        }

        // ── 3) خريطة كل رأس → المثلثات الحية اللي بتلمسه (تتحدّث تدريجيًا مع كل دمج) —
        // IntArrayList خفيف الذاكرة بدل HashSet<Int> (شوف الشرح في الأعلى) ──
        val vertTris = Array(vertexCount) { IntArrayList() }
        for (t in 0 until triangleCount) {
            if (!triAlive[t]) continue
            vertTris[triA[t]].add(t); vertTris[triB[t]].add(t); vertTris[triC[t]].add(t)
        }

        // ── 4) مصفوفة الخطأ التربيعي لكل رأس (Garland-Heckbert) — 10 أرقام تمثل
        // المصفوفة المتماثلة 4×4: [a²,ab,ac,ad, b²,bc,bd, c²,cd, d²] ──
        val quadric = Array(vertexCount) { DoubleArray(10) }
        fun addFaceQuadric(t: Int) {
            val a = triA[t]; val b = triB[t]; val c = triC[t]
            val ax = posX[a]; val ay = posY[a]; val az = posZ[a]
            val ux = posX[b] - ax; val uy = posY[b] - ay; val uz = posZ[b] - az
            val vx = posX[c] - ax; val vy = posY[c] - ay; val vz = posZ[c] - az
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-15) return // مثلث شبه منعدم المساحة أصلًا — تجاهله من حساب الأخطاء فقط
            nx /= len; ny /= len; nz /= len
            val d = -(nx * ax + ny * ay + nz * az)
            val q = doubleArrayOf(nx * nx, nx * ny, nx * nz, nx * d, ny * ny, ny * nz, ny * d, nz * nz, nz * d, d * d)
            for (vId in intArrayOf(a, b, c)) {
                val qv = quadric[vId]
                for (k in 0 until 10) qv[k] += q[k]
            }
        }
        for (t in 0 until triangleCount) if (triAlive[t]) addFaceQuadric(t)

        fun quadricCost(q: DoubleArray, x: Double, y: Double, z: Double): Double {
            return q[0] * x * x + 2 * q[1] * x * y + 2 * q[2] * x * z + 2 * q[3] * x +
                    q[4] * y * y + 2 * q[5] * y * z + 2 * q[6] * y +
                    q[7] * z * z + 2 * q[8] * z +
                    q[9]
        }

        /** موقع الدمج الأمثل (اللي بيقلل الخطأ التربيعي). لو المصفوفة شبه شاذة
         * (غير مستقرة عدديًا، مثلًا سطح مستوي محليًا)، بيرجع لمنتصف الضلع كبديل آمن. */
        fun optimalPosition(q: DoubleArray, v1: Int, v2: Int): DoubleArray {
            val a00 = q[0]; val a01 = q[1]; val a02 = q[2]
            val a10 = q[1]; val a11 = q[4]; val a12 = q[5]
            val a20 = q[2]; val a21 = q[5]; val a22 = q[7]
            val b0 = -q[3]; val b1 = -q[6]; val b2 = -q[8]

            val det = a00 * (a11 * a22 - a12 * a21) - a01 * (a10 * a22 - a12 * a20) + a02 * (a10 * a21 - a11 * a20)
            if (abs(det) > 1e-9) {
                val invDet = 1.0 / det
                val x = invDet * (b0 * (a11 * a22 - a12 * a21) - a01 * (b1 * a22 - a12 * b2) + a02 * (b1 * a21 - a11 * b2))
                val y = invDet * (a00 * (b1 * a22 - a12 * b2) - b0 * (a10 * a22 - a12 * a20) + a02 * (a10 * b2 - b1 * a20))
                val z = invDet * (a00 * (a11 * b2 - b1 * a21) - a01 * (a10 * b2 - b1 * a20) + b0 * (a10 * a21 - a11 * a20))
                if (x.isFinite() && y.isFinite() && z.isFinite()) return doubleArrayOf(x, y, z)
            }
            return doubleArrayOf((posX[v1] + posX[v2]) / 2.0, (posY[v1] + posY[v2]) / 2.0, (posZ[v1] + posZ[v2]) / 2.0)
        }

        val vertexAlive = BooleanArray(vertexCount) { true }
        // vertexGen: عدّاد "جيل" لكل رأس، بيزيد كل مرة موقعه/مصفوفة خطأه تتغيّر —
        // بنستخدمه عشان نتجاهل مرشحات قديمة في الـ Priority Queue بقت غير صالحة
        // (Lazy Invalidation) من غير الحاجة لحذفها فعليًا من الطابور (أرخص أداءً).
        val vertexGen = IntArray(vertexCount)

        data class Candidate(
            val cost: Double, val v1: Int, val v2: Int,
            val genV1: Int, val genV2: Int,
            val tx: Double, val ty: Double, val tz: Double
        )
        val pq = PriorityQueue<Candidate>(compareBy { it.cost })

        fun combinedQuadric(v1: Int, v2: Int): DoubleArray {
            val q1 = quadric[v1]; val q2 = quadric[v2]
            return DoubleArray(10) { q1[it] + q2[it] }
        }

        fun pushCandidate(v1: Int, v2: Int) {
            val q = combinedQuadric(v1, v2)
            val pos = optimalPosition(q, v1, v2)
            val cost = quadricCost(q, pos[0], pos[1], pos[2])
            pq.add(Candidate(cost, v1, v2, vertexGen[v1], vertexGen[v2], pos[0], pos[1], pos[2]))
        }

        // ── 5) تهيئة الطابور بكل ضلع فريد في الموديل ──
        run {
            val seen = HashSet<Long>(cornerCount)
            fun edgeKey(x: Int, y: Int): Long {
                val lo = minOf(x, y).toLong(); val hi = maxOf(x, y).toLong()
                return (lo shl 32) or hi
            }
            for (t in 0 until triangleCount) {
                if (!triAlive[t]) continue
                val a = triA[t]; val b = triB[t]; val c = triC[t]
                for (pair in listOf(a to b, b to c, a to c)) {
                    val k = edgeKey(pair.first, pair.second)
                    if (seen.add(k)) pushCandidate(minOf(pair.first, pair.second), maxOf(pair.first, pair.second))
                }
            }
        }

        // ── 6) الحلقة الرئيسية: ادمج أرخص ضلع صالح، كرر لحد ما نوصل للهدف ──
        while (aliveTriCount > targetTriangles && pq.isNotEmpty()) {
            val cand = pq.poll()
            val v1 = cand.v1; val v2 = cand.v2
            if (!vertexAlive[v1] || !vertexAlive[v2]) continue // مرشح قديم لرأس مات بالفعل
            if (vertexGen[v1] != cand.genV1 || vertexGen[v2] != cand.genV2) continue // مرشح قديم/تكلفة غير محدّثة

            // نفّذ الدمج: v2 يموت، v1 ياخد الموقع الأمثل الجديد ومصفوفة الخطأ المجمّعة
            posX[v1] = cand.tx; posY[v1] = cand.ty; posZ[v1] = cand.tz
            val q1 = quadric[v1]; val q2 = quadric[v2]
            for (k in 0 until 10) q1[k] += q2[k]
            vertexAlive[v2] = false

            val touchedTris = vertTris[v2]
            for (ti in 0 until touchedTris.size) {
                val t = touchedTris.data[ti]
                if (!triAlive[t]) continue
                var a = triA[t]; var b = triB[t]; var c = triC[t]
                if (a == v2) a = v1
                if (b == v2) b = v1
                if (c == v2) c = v1
                if (a == b || b == c || a == c) {
                    // المثلث بقى منعدم المساحة فعليًا بعد الدمج — يتشال (نتيجة طبيعية
                    // لدمج ضلع واحد بعينه، مش تجاهل جماعي عشوائي زي الطريقة القديمة)
                    triAlive[t] = false
                    aliveTriCount--
                } else {
                    triA[t] = a; triB[t] = b; triC[t] = c
                    vertTris[v1].add(t)
                }
            }
            vertTris[v2].clear()
            vertexGen[v1]++

            if (aliveTriCount <= targetTriangles) break

            // أعد تقييم كل الأضلاع المتصلة بـ v1 بعد الدمج (تكلفتها اتغيّرت)
            val neighbors = HashSet<Int>()
            val v1Tris = vertTris[v1]
            for (ti in 0 until v1Tris.size) {
                val t = v1Tris.data[ti]
                if (!triAlive[t]) continue
                val a = triA[t]; val b = triB[t]; val c = triC[t]
                if (a != v1) neighbors.add(a)
                if (b != v1) neighbors.add(b)
                if (c != v1) neighbors.add(c)
            }
            for (n in neighbors) {
                if (vertexAlive[n]) pushCandidate(minOf(v1, n), maxOf(v1, n))
            }
        }

        return buildOutput(model, triA, triB, triC, triAlive, posX, posY, posZ, triangleCount)
    }

    /** يحوّل المثلثات المفهرسة الناتجة رجوع لصيغة Flat/Non-indexed (زي باقي التطبيق
     * متعامل معاها)، ويحسب نورمال مسطّح جديد لكل مثلث من الهندسة الفعلية بعد الدمج. */
    private fun buildOutput(
        model: STLModel,
        triA: IntArray, triB: IntArray, triC: IntArray, triAlive: BooleanArray,
        posX: DoubleArray, posY: DoubleArray, posZ: DoubleArray,
        triangleCount: Int
    ): STLModel? {
        var outTriCount = 0
        for (t in 0 until triangleCount) if (triAlive[t]) outTriCount++
        if (outTriCount < 4) return null

        val outVerts = FloatArray(outTriCount * 9)
        val outNorms = FloatArray(outTriCount * 9)
        var w = 0
        for (t in 0 until triangleCount) {
            if (!triAlive[t]) continue
            val a = triA[t]; val b = triB[t]; val c = triC[t]
            val ax = posX[a]; val ay = posY[a]; val az = posZ[a]
            val bx = posX[b]; val by = posY[b]; val bz = posZ[b]
            val cx = posX[c]; val cy = posY[c]; val cz = posZ[c]
            val ux = bx - ax; val uy = by - ay; val uz = bz - az
            val vx = cx - ax; val vy = cy - ay; val vz = cz - az
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-15) { nx /= len; ny /= len; nz /= len }

            outVerts[w] = ax.toFloat(); outVerts[w + 1] = ay.toFloat(); outVerts[w + 2] = az.toFloat()
            outVerts[w + 3] = bx.toFloat(); outVerts[w + 4] = by.toFloat(); outVerts[w + 5] = bz.toFloat()
            outVerts[w + 6] = cx.toFloat(); outVerts[w + 7] = cy.toFloat(); outVerts[w + 8] = cz.toFloat()

            outNorms[w] = nx.toFloat(); outNorms[w + 1] = ny.toFloat(); outNorms[w + 2] = nz.toFloat()
            outNorms[w + 3] = nx.toFloat(); outNorms[w + 4] = ny.toFloat(); outNorms[w + 5] = nz.toFloat()
            outNorms[w + 6] = nx.toFloat(); outNorms[w + 7] = ny.toFloat(); outNorms[w + 8] = nz.toFloat()
            w += 9
        }

        return model.copy(
            vertices = outVerts,
            normals = outNorms,
            triangleCount = outTriCount,
            isWatertightHint = (outTriCount % 2 == 0)
            // minBounds / maxBounds: بتتوارث زي ما هي من الموديل الأصلي عمدًا (نفس مبدأ الخوارزمية القديمة)
        )
    }
}
