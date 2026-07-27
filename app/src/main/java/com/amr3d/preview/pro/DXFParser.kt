package com.amr3d.preview.pro

import android.content.Context
import android.net.Uri

/**
 * قارئ DXF — بيطلّع عناصر 2D حقيقية (خطوط/دوائر/أقواس) بألوانها الحقيقية
 * عشان تتعرض بشاشة رسم ثنائية الأبعاد (DXF2DView) زي الأوتوكاد بالظبط،
 * مش بتحويلها لمثلثات وهمية وعرضها في الريندرر ثلاثي الأبعاد.
 */
object DXFParser {

    /** بادئة داخلية بنستخدمها لمّا نجمّع العناصر حسب اللون بدل اسم الطبقة (شوف
     * التعليق قبل بناء DxfModel في الآخر). القيمة دي مش مفروض تتعرض للمستخدم زي
     * ما هي — الشاشات اللي بتعرضها (DXF2DView / ViewerFragment) بتتعرف عليها
     * وتحوّلها للون فعلي + تسمية ودّية زي "🎨 لون 2". */
    const val COLOR_GROUP_PREFIX = "\u0007#COLORGROUP#"

    private data class DxfPair(val code: Int, val value: String)

    fun parse(context: Context, uri: Uri): DxfModel {
        // ⚠️ إصلاح أداء/ذاكرة مهم: النسخة القديمة كانت بتقرا الملف كله في String واحد
        // ضخم (readText())، وبعدين تقسّمه لقائمة كاملة بكل أسطر الملف (text.lines())
        // — يعني نسختين إضافيتين من كل بيانات الملف قاعدين في الذاكرة في نفس اللحظة
        // فوق الـ pairs نفسها. لملف DXF حقيقي 10 ميجا (اللي عادة فيه مئات الآلاف من
        // الأسطر بسبب صيغة DXF المطوّلة)، ده كان بيضاعف استهلاك الذاكرة أضعاف كتير
        // ويسبب OutOfMemoryError. دلوقتي بنقرا سطرين سطرين مباشرة من الـ stream (زي
        // BufferedReader.readLine() العادي) من غير ما نحتفظ بالملف كله أو بكل أسطره
        // كقائمة منفصلة — نفس منطق تجميع الـ pairs (code, value) بالظبط زي الأول.
        val pairs = mutableListOf<DxfPair>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val reader = stream.bufferedReader(Charsets.ISO_8859_1)
            while (true) {
                val codeLine = reader.readLine() ?: break
                val valueLine = reader.readLine() ?: break
                val code = codeLine.trim().toIntOrNull()
                if (code != null) pairs.add(DxfPair(code, valueLine.trim()))
            }
        } ?: throw STLParseException(context.getString(R.string.error_dxf_open_failed))

        // ══ 1) قراءة جدول الطبقات (LAYER table) — عشان نعرف لون كل طبقة ══
        val layerColors = mutableMapOf<String, Int>() // اسم الطبقة -> رقم لون ACI
        run {
            var tablesStart = -1
            var tablesEnd = pairs.size
            for (k in pairs.indices) {
                if (pairs[k].code == 2 && pairs[k].value == "TABLES") tablesStart = k
                if (tablesStart > 0 && pairs[k].code == 0 && pairs[k].value == "ENDSEC" && k > tablesStart) {
                    tablesEnd = k
                    break
                }
            }
            if (tablesStart > 0) {
                var p = tablesStart
                var curLayerName: String? = null
                while (p < tablesEnd) {
                    val pair = pairs[p]
                    if (pair.code == 0 && pair.value == "LAYER") {
                        p++
                        curLayerName = null
                        var curColor = 7
                        while (p < tablesEnd && pairs[p].code != 0) {
                            when (pairs[p].code) {
                                2 -> curLayerName = pairs[p].value
                                62 -> curColor = pairs[p].value.toIntOrNull() ?: 7
                            }
                            p++
                        }
                        curLayerName?.let { layerColors[it] = curColor }
                    } else {
                        p++
                    }
                }
            }
        }

        // ══ 2) إيجاد قسم ENTITIES ══
        var entStart = -1
        var entEnd = pairs.size
        for (k in pairs.indices) {
            if (pairs[k].code == 2 && pairs[k].value == "ENTITIES") entStart = k
            if (entStart > 0 && pairs[k].code == 0 && pairs[k].value == "ENDSEC" && k > entStart) {
                entEnd = k
                break
            }
        }
        if (entStart < 0) throw STLParseException(context.getString(R.string.error_dxf_no_entities))

        /** بيحدد لون العنصر: لو ليه لون صريح (code 62) بيستخدمه، وإلا بياخد لون الطبقة بتاعته (code 8) */
        fun resolveColor(explicitAci: Int?, layerName: String?): Int {
            val aci = explicitAci ?: layerName?.let { layerColors[it] } ?: 7
            return AciColors.toColor(aci)
        }

        // بيحتفظ بأسماء كل الطبقات اللي فعليًا اتستخدمت في عناصر مرسومة (مش بس المعرّفة
        // في جدول LAYER)، بترتيب ظهورها أول مرة — ده اللي هيتعرض في قائمة الطبقات للمستخدم
        val layerOrder = LinkedHashSet<String>()
        fun normalizeLayer(layerName: String?): String {
            val name = layerName ?: "0"
            layerOrder.add(name)
            return name
        }

        val lines = mutableListOf<DxfLine>()
        val arcs = mutableListOf<DxfArc>()
        val circles = mutableListOf<DxfCircle>()

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        fun grow(x: Float, y: Float) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }

        var pos = entStart
        while (pos < entEnd) {
            val pair = pairs[pos]
            when {
                pair.code == 0 && pair.value == "LINE" -> {
                    pos++
                    var x1 = 0f; var y1 = 0f; var x2 = 0f; var y2 = 0f
                    var layerName: String? = null; var aci: Int? = null
                    while (pos < entEnd && pairs[pos].code != 0) {
                        when (pairs[pos].code) {
                            8 -> layerName = pairs[pos].value
                            62 -> aci = pairs[pos].value.toIntOrNull()
                            10 -> x1 = pairs[pos].value.toFloatOrNull() ?: 0f
                            20 -> y1 = pairs[pos].value.toFloatOrNull() ?: 0f
                            11 -> x2 = pairs[pos].value.toFloatOrNull() ?: 0f
                            21 -> y2 = pairs[pos].value.toFloatOrNull() ?: 0f
                        }
                        pos++
                    }
                    lines.add(DxfLine(x1, y1, x2, y2, resolveColor(aci, layerName), normalizeLayer(layerName)))
                    grow(x1, y1); grow(x2, y2)
                }

                pair.code == 0 && pair.value == "LWPOLYLINE" -> {
                    pos++
                    val pts = mutableListOf<Pair<Float, Float>>()
                    var closed = false
                    var cx = 0f; var cy = 0f; var hasX = false
                    var layerName: String? = null; var aci: Int? = null
                    while (pos < entEnd && pairs[pos].code != 0) {
                        when (pairs[pos].code) {
                            8 -> layerName = pairs[pos].value
                            62 -> aci = pairs[pos].value.toIntOrNull()
                            70 -> closed = (pairs[pos].value.trim().toIntOrNull() ?: 0) and 1 != 0
                            10 -> { cx = pairs[pos].value.toFloatOrNull() ?: 0f; hasX = true }
                            20 -> {
                                cy = pairs[pos].value.toFloatOrNull() ?: 0f
                                if (hasX) { pts.add(Pair(cx, cy)); hasX = false }
                            }
                        }
                        pos++
                    }
                    val col = resolveColor(aci, layerName)
                    val lyr = normalizeLayer(layerName)
                    for (k in 0 until pts.size - 1) {
                        lines.add(DxfLine(pts[k].first, pts[k].second, pts[k + 1].first, pts[k + 1].second, col, lyr))
                        grow(pts[k].first, pts[k].second); grow(pts[k + 1].first, pts[k + 1].second)
                    }
                    if (closed && pts.size > 1) {
                        lines.add(DxfLine(pts.last().first, pts.last().second, pts.first().first, pts.first().second, col, lyr))
                    }
                }

                pair.code == 0 && pair.value == "POLYLINE" -> {
                    pos++
                    var closed = false
                    val pts = mutableListOf<Pair<Float, Float>>()
                    var layerName: String? = null; var aci: Int? = null
                    while (pos < entEnd && !(pairs[pos].code == 0 && pairs[pos].value == "SEQEND")) {
                        if (pairs[pos].code == 70) {
                            closed = (pairs[pos].value.trim().toIntOrNull() ?: 0) and 1 != 0
                            pos++
                        } else if (pairs[pos].code == 8) {
                            layerName = pairs[pos].value
                            pos++
                        } else if (pairs[pos].code == 62) {
                            aci = pairs[pos].value.toIntOrNull()
                            pos++
                        } else if (pairs[pos].code == 0 && pairs[pos].value == "VERTEX") {
                            pos++
                            var vx = 0f; var vy = 0f
                            while (pos < entEnd && pairs[pos].code != 0) {
                                when (pairs[pos].code) {
                                    10 -> vx = pairs[pos].value.toFloatOrNull() ?: 0f
                                    20 -> vy = pairs[pos].value.toFloatOrNull() ?: 0f
                                }
                                pos++
                            }
                            pts.add(Pair(vx, vy))
                        } else {
                            pos++
                        }
                    }
                    val col = resolveColor(aci, layerName)
                    val lyr = normalizeLayer(layerName)
                    for (k in 0 until pts.size - 1) {
                        lines.add(DxfLine(pts[k].first, pts[k].second, pts[k + 1].first, pts[k + 1].second, col, lyr))
                        grow(pts[k].first, pts[k].second); grow(pts[k + 1].first, pts[k + 1].second)
                    }
                    if (closed && pts.size > 1) {
                        lines.add(DxfLine(pts.last().first, pts.last().second, pts.first().first, pts.first().second, col, lyr))
                    }
                    if (pos < entEnd) pos++ // تخطي SEQEND
                }

                pair.code == 0 && pair.value == "CIRCLE" -> {
                    pos++
                    var cx = 0f; var cy = 0f; var r = 0f
                    var layerName: String? = null; var aci: Int? = null
                    while (pos < entEnd && pairs[pos].code != 0) {
                        when (pairs[pos].code) {
                            8 -> layerName = pairs[pos].value
                            62 -> aci = pairs[pos].value.toIntOrNull()
                            10 -> cx = pairs[pos].value.toFloatOrNull() ?: 0f
                            20 -> cy = pairs[pos].value.toFloatOrNull() ?: 0f
                            40 -> r = pairs[pos].value.toFloatOrNull() ?: 0f
                        }
                        pos++
                    }
                    if (r > 0f) {
                        circles.add(DxfCircle(cx, cy, r, resolveColor(aci, layerName), normalizeLayer(layerName)))
                        grow(cx - r, cy - r); grow(cx + r, cy + r)
                    }
                }

                pair.code == 0 && pair.value == "ARC" -> {
                    pos++
                    var cx = 0f; var cy = 0f; var r = 0f; var startA = 0f; var endA = 360f
                    var layerName: String? = null; var aci: Int? = null
                    while (pos < entEnd && pairs[pos].code != 0) {
                        when (pairs[pos].code) {
                            8 -> layerName = pairs[pos].value
                            62 -> aci = pairs[pos].value.toIntOrNull()
                            10 -> cx = pairs[pos].value.toFloatOrNull() ?: 0f
                            20 -> cy = pairs[pos].value.toFloatOrNull() ?: 0f
                            40 -> r = pairs[pos].value.toFloatOrNull() ?: 0f
                            50 -> startA = pairs[pos].value.toFloatOrNull() ?: 0f
                            51 -> endA = pairs[pos].value.toFloatOrNull() ?: 360f
                        }
                        pos++
                    }
                    if (r > 0f) {
                        arcs.add(DxfArc(cx, cy, r, startA, endA, resolveColor(aci, layerName), normalizeLayer(layerName)))
                        grow(cx - r, cy - r); grow(cx + r, cy + r)
                    }
                }

                else -> pos++
            }
        }

        val totalEntities = lines.size + arcs.size + circles.size
        if (totalEntities == 0) throw STLParseException(context.getString(R.string.error_dxf_no_displayable))

        // ══ تجميع العناصر لأغراض قائمة "الطبقات" في الواجهة ══
        // لو الملف فيه أكتر من طبقة CAD حقيقية (code 8) بالفعل، بنستخدمها زي ما هي.
        // لكن كتير من ملفات الـ CNC (خصوصًا الصادرة من 3ds Max) بتحط كل حاجة على
        // طبقة واحدة ("0") وتفرّق بين أنواع التشغيل (قص/حفر/نقش) بالألوان بس — في
        // الحالة دي مفيش أي فايدة من قائمة طبقات فيها عنصر واحد، فبنجمّع العناصر
        // حسب اللون الفعلي بدل اسم الطبقة عشان الإخفاء/الإظهار يبقى مفيد فعلًا.
        val distinctColors = LinkedHashSet<Int>()
        for (l in lines) distinctColors.add(l.color)
        for (a in arcs) distinctColors.add(a.color)
        for (c in circles) distinctColors.add(c.color)

        val finalLines: List<DxfLine>
        val finalArcs: List<DxfArc>
        val finalCircles: List<DxfCircle>
        val finalLayers: List<String>
        val colorPalette: List<Int>

        if (layerOrder.size > 1) {
            finalLines = lines; finalArcs = arcs; finalCircles = circles
            finalLayers = layerOrder.toList()
            colorPalette = emptyList()
        } else if (distinctColors.size > 1) {
            val colorOrder = distinctColors.toList()
            fun keyFor(color: Int) = COLOR_GROUP_PREFIX + colorOrder.indexOf(color)
            finalLines = lines.map { it.copy(layer = keyFor(it.color)) }
            finalArcs = arcs.map { it.copy(layer = keyFor(it.color)) }
            finalCircles = circles.map { it.copy(layer = keyFor(it.color)) }
            finalLayers = colorOrder.indices.map { COLOR_GROUP_PREFIX + it }
            colorPalette = colorOrder
        } else {
            finalLines = lines; finalArcs = arcs; finalCircles = circles
            finalLayers = layerOrder.toList().ifEmpty { listOf("0") }
            colorPalette = emptyList()
        }

        return DxfModel(
            lines = finalLines,
            arcs = finalArcs,
            circles = finalCircles,
            minX = if (minX == Float.MAX_VALUE) 0f else minX,
            minY = if (minY == Float.MAX_VALUE) 0f else minY,
            maxX = if (maxX == -Float.MAX_VALUE) 1f else maxX,
            maxY = if (maxY == -Float.MAX_VALUE) 1f else maxY,
            entityCount = totalEntities,
            layers = finalLayers,
            colorGroupPalette = colorPalette
        )
    }
}
