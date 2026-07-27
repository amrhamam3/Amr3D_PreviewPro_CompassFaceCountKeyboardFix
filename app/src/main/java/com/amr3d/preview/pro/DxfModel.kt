package com.amr3d.preview.pro

/**
 * تمثيل حقيقي ثنائي الأبعاد لعناصر DXF (خطوط / دوائر / أقواس / بوليلاين)
 * بدون أي تحويل وهمي إلى شبكة ثلاثية الأبعاد — بيتعرض بكانفاس 2D حقيقي
 * زي شاشة الرسم في الأوتوكاد بالظبط.
 *
 * color: قيمة RGB جاهزة (0xAARRGGBB) — مستخرجة من لون الطبقة (Layer) أو لون العنصر نفسه (ACI)
 */
data class DxfLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int, val layer: String = "0")
data class DxfArc(val cx: Float, val cy: Float, val r: Float, val startDeg: Float, val endDeg: Float, val color: Int, val layer: String = "0")
data class DxfCircle(val cx: Float, val cy: Float, val r: Float, val color: Int, val layer: String = "0")

data class DxfModel(
    val lines: List<DxfLine>,
    val arcs: List<DxfArc>,
    val circles: List<DxfCircle>,
    val minX: Float, val minY: Float,
    val maxX: Float, val maxY: Float,
    val entityCount: Int,
    /** أسماء/مفاتيح كل الطبقات (أو مجموعات الألوان) الموجودة في الملف، بترتيب ظهورها أول مرة */
    val layers: List<String> = listOf("0"),
    /** لو التجميع حصل حسب اللون بدل اسم الطبقة، هنا الألوان الفعلية بنفس ترتيب layers
     * (فاضية لو التجميع حصل حسب اسم الطبقة الحقيقي) */
    val colorGroupPalette: List<Int> = emptyList()
)

/** جدول ألوان الأوتوكاد القياسي (AutoCAD Color Index) — أهم الألوان المستخدمة فعلياً */
object AciColors {
    private val exactColors = mapOf(
        1 to 0xFFFF0000.toInt(),  // أحمر
        2 to 0xFFFFFF00.toInt(),  // أصفر
        3 to 0xFF00FF00.toInt(),  // أخضر
        4 to 0xFF00FFFF.toInt(),  // سماوي
        5 to 0xFF0000FF.toInt(),  // أزرق
        6 to 0xFFFF00FF.toInt(),  // ماجنتا
        7 to 0xFFFFFFFF.toInt(),  // أبيض/أسود (حسب الخلفية)
        8 to 0xFF808080.toInt(),  // رمادي غامق
        9 to 0xFFC0C0C0.toInt()   // رمادي فاتح
    )

    /** بيحوّل رقم لون الأوتوكاد (ACI 0-255) لقيمة RGB حقيقية تتعرض على خلفية سودا */
    fun toColor(aci: Int): Int {
        exactColors[aci]?.let { return it }
        if (aci <= 0) return 0xFFFFFFFF.toInt() // BYBLOCK/غير معروف -> أبيض
        // لباقي الفهرس (10-255) بنولّد لون متمايز ثابت بناءً على قيمة الفهرس نفسه
        val hue = (aci * 47) % 360
        return android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.65f, 0.95f))
    }
}
