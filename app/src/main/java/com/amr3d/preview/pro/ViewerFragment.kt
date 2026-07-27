package com.amr3d.preview.pro

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.*
import android.view.animation.*
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * توسيع بسيط: خلّى أزرار الأيقونات (ImageButton) تتصرف زي زرار toggle عادي
 * (isChecked) من غير ما نغيّر كل الأماكن اللي بتستخدم .isChecked في الملف ده.
 * بيشتغل عن طريق isSelected المدمجة في أي View، اللي بتتماشى مع
 * android:state_selected في bg_toggle_button.xml.
 */
private var ImageButton.isChecked: Boolean
    get() = isSelected
    set(value) { isSelected = value }

class ViewerFragment : Fragment() {

    // ═══ Views ═══
    private lateinit var glViewerView: GLViewerView
    private lateinit var viewerContainer: FrameLayout
    private lateinit var modelStatsText: TextView
    private lateinit var particleField: ParticleFieldView
    private lateinit var pivotFeedbackView: PivotFeedbackView
    private lateinit var dxf2DView: DXF2DView
    private lateinit var displayToolbar: View
    private lateinit var bottomToolbar: View
    private lateinit var emptyStateText: TextView
    private lateinit var welcomeText: TextView
    private lateinit var welcomeOverlay: TextView
    private lateinit var btnToggleDxf: TextView
    private lateinit var btnWhatsapp: ImageButton
    private lateinit var btnMeasureTool: ImageButton
    private lateinit var btnAutoRotate: ImageButton
    private lateinit var btnInspect: ImageButton
    private lateinit var btnResetView: ImageButton
    private lateinit var btnBoundingBox: ImageButton
    private lateinit var btnWireframe: ImageButton
    private lateinit var btnMaterial: ImageButton
    private lateinit var btnUnit: Button
    private lateinit var btnExport: ImageButton
    private lateinit var btnLightToggle: ImageButton
    private lateinit var btnToggleToolbars: Button
    private var toolbarsVisible = true
    private lateinit var btnDirections: Button
    private lateinit var directionsPanel: View
    private lateinit var compassRing: android.widget.ImageView
    private lateinit var btnViewFront: Button
    private lateinit var btnViewBack: Button
    private lateinit var btnViewLeft: Button
    private lateinit var btnViewRight: Button
    private lateinit var btnViewTop: Button
    private lateinit var btnViewBottom: Button
    private lateinit var measurementCard: CardView
    private lateinit var measurementText: TextView
    private lateinit var inspectionCard: LinearLayout
    private lateinit var inspectionText: TextView
    private lateinit var lightDialOverlay: LightDialOverlayView
    private lateinit var btnCloseLightWheel: ImageButton
    private lateinit var loadingContainer: View
    private lateinit var loadingProgress: ProgressBar
    private lateinit var loadingText: TextView

    private var currentModel: STLModel? = null
    private var is2DMode = false
    private var measureModeOn = false
    private var currentUnit = MeasurementUnit.MM

    // ملف معلّق من MainActivity (قبل init الـ View)
    private var pendingUri: Uri? = null

    private val openDocumentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // ⚠️ إصلاح: لوحة المفاتيح بتاعة خانة البحث في مستعرض الملفات النظامي كانت
        // بتفضل "معلّقة" على الشاشة لما نرجع لتطبيقنا (Android مبيقفلهاش تلقائي
        // دايمًا لما ترجع من نشاط خارجي)، حتى لو مفيش أي حقل نص متفعّل عندنا خالص.
        // بنجبرها تقفل هنا صراحة فور الرجوع، بصرف النظر هل المستخدم اختار ملف ولا لأ.
        view?.let { v ->
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
        }
        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            loadFile(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_viewer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppTheme.applyThemeRecursively(view, requireContext())
        bindViews(view)
        applyViewerBackgroundForCurrentMode()
        loadSavedSettings()
        setupWelcome()
        wireUpListeners()
        animateToolbarEntrance()

        // تحميل أي ملف كان معلقاً قبل init الـ View
        pendingUri?.let {
            pendingUri = null
            loadFile(it)
        }
    }

    /** يقرأ الإعدادات المحفوظة من صفحة الإعدادات ويطبّقها فعلياً */
    private fun loadSavedSettings() {
        val prefs = requireContext().getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
        currentUnit = when (prefs.getString("unit", "MM")) {
            "CM"   -> MeasurementUnit.CM
            "INCH" -> MeasurementUnit.INCH
            else   -> MeasurementUnit.MM
        }
        if (::dxf2DView.isInitialized) dxf2DView.currentUnit = currentUnit
        val quality = when (prefs.getString("quality", "HIGH")) {
            "LOW"    -> 0
            "MEDIUM" -> 1
            else     -> 2
        }
        glViewerView.stlRenderer.qualityLevel = quality
        glViewerView.stlRenderer.zUpMode = prefs.getBoolean("zup_mode", true)
        glViewerView.stlRenderer.showReflection = prefs.getBoolean("reflection_enabled", true)
    }

    override fun onResume() {
        super.onResume()
        // إعادة قراءة الإعدادات عند الرجوع من صفحة الإعدادات
        if (::btnUnit.isInitialized) {
            loadSavedSettings()
            btnUnit.text = getString(currentUnit.labelRes)
            updateModelStatsOverlay(currentModel)
        }
        // ⚠️ مهم: onResume() هنا بيتنادى لما التطبيق كله يرجع من الخلفية (بعد تصغيره
        // بزرار الهوم مثلاً)، وده حالة مختلفة تمامًا عن onHiddenChanged (اللي بيتنادى
        // بس لما ننتقل بين تابات جوه التطبيق). قبل كده onPause() المقابلة ماكانتش
        // موجودة خالص، يعني خيط رندر الـ GL كان بيفضل شغال وهو التطبيق مصغّر ومفيش
        // Surface فعلي يرسم عليه — ولما ترجع تفتح التطبيق تاني، الرندرر بيكون في حالة
        // متضاربة (وده سبب الشكل الغريب اللي بيظهر). لازم نتأكد إن الفراجمنت مش
        // hidden أصلًا (لو hidden، الـ GL أصلاً متوقف عمدًا من onHiddenChanged ومينفعش
        // نرجّعه هنا فوقه).
        if (::glViewerView.isInitialized && !isHidden && !is2DMode) {
            glViewerView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        // نفس الملحوظة فوق بالظبط لكن في الاتجاه المعاكس: لازم نوقف خيط الرندر فورًا
        // لما التطبيق يروح للخلفية (تصغير)، مش بس لما ننتقل بين تابات جوه التطبيق.
        if (::glViewerView.isInitialized) {
            glViewerView.onPause()
        }
    }

    /**
     * ملحوظة مهمة: MainActivity بيستخدم hide()/show() للتنقل بين التابات (مش replace)،
     * و hide()/show() ما بيستدعوش onPause()/onResume() على الفراجمنت — بيستدعوا onHiddenChanged() بس.
     * من غيرها، أي إعداد تغيّره في شاشة الإعدادات (زي وحدة القياس أو جودة العرض) كان
     * هيفضل باين إنه اتغيّر في الإعدادات، لكن شاشة العارض نفسها كانت مش بتاخده إلا لو
     * التطبيق كله اتقفل وفتح تاني (onResume حقيقي). دلوقتي بنعيد القراءة برضه هنا.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ::btnUnit.isInitialized) {
            loadSavedSettings()
            btnUnit.text = getString(currentUnit.labelRes)
            // ⚠️ إصلاح: كانت ناقصة هنا تحديدًا — عداد Faces/Vertices كان بيتحدّث بس
            // في onResume() (اللي بيتنادى بس لما التطبيق كله يرجع من الخلفية)، مش
            // هنا في onHiddenChanged (اللي هو فعليًا المسؤول عن التنقل بين التابات
            // جوه التطبيق). فكان لازم تقفل وتفتح التطبيق كله عشان الإعداد ياخد مفعوله،
            // بدل ما يتطبق فورًا لما ترجع من تاب الإعدادات لتاب العارض.
            updateModelStatsOverlay(currentModel)
        }
        // MainActivity بيستخدم hide()/show() للتنقل بين التابات (مش replace)، يعني لما
        // المستخدم يروح لتاب تاني (الإعدادات مثلاً)، شاشة العارض دي مش بتتقفل فعليًا،
        // بس بتختفي — ومن غيرها، خيط رندر الـ GL (RENDERMODE_CONTINUOUSLY) كان بيفضل
        // شغال باستمرار في الخلفية حتى والمستخدم مش شايف الشاشة دي خالص، وده سبب حقيقي
        // للاج العام مش بس وقت تحميل الملفات. بنوقفه هنا ونشغّله بس لما نرجع فعليًا،
        // وبس لو مفيش عرض DXF شغال حاليًا (وقتها الـ GL أصلاً متوقف عمدًا).
        if (::glViewerView.isInitialized) {
            if (hidden) {
                glViewerView.onPause()
            } else if (!is2DMode) {
                glViewerView.onResume()
            }
        }
    }

    private var particleChoreographerCallback: android.view.Choreographer.FrameCallback? = null

    /** حلقة تحديث خفيفة جدًا (Choreographer) بس عشان الجسيمات الزخرفية تتحرك —
     * مالهاش علاقة بحلقة رندر الـ OpenGL نفسها (دي View عادية فوق الـ GLSurfaceView) */
    private fun startParticleLoop() {
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!isAdded) return
                particleField.update()
                particleField.invalidate()
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        particleChoreographerCallback = callback
        android.view.Choreographer.getInstance().postFrameCallback(callback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        particleChoreographerCallback?.let {
            android.view.Choreographer.getInstance().removeFrameCallback(it)
        }
        particleChoreographerCallback = null
    }

    private fun bindViews(v: View) {
        glViewerView        = v.findViewById(R.id.glViewerView)
        viewerContainer     = v.findViewById(R.id.viewerContainer)
        modelStatsText      = v.findViewById(R.id.modelStatsText)
        particleField       = v.findViewById(R.id.particleField)
        pivotFeedbackView   = v.findViewById(R.id.pivotFeedbackView)
        startParticleLoop()
        dxf2DView           = v.findViewById(R.id.dxf2DView)
        displayToolbar      = v.findViewById(R.id.displayToolbar)
        bottomToolbar       = v.findViewById(R.id.bottomToolbar)
        emptyStateText      = v.findViewById(R.id.emptyStateText)
        welcomeText         = v.findViewById(R.id.welcomeText)
        welcomeOverlay      = v.findViewById(R.id.welcomeOverlay)
        btnToggleDxf        = v.findViewById(R.id.btnToggleDxf)
        btnWhatsapp         = v.findViewById(R.id.btnWhatsapp)
        btnMeasureTool      = v.findViewById(R.id.btnMeasureTool)
        btnAutoRotate       = v.findViewById(R.id.btnAutoRotate)
        btnInspect          = v.findViewById(R.id.btnInspect)
        btnResetView        = v.findViewById(R.id.btnResetView)
        btnBoundingBox      = v.findViewById(R.id.btnBoundingBox)
        btnWireframe        = v.findViewById(R.id.btnWireframe)
        btnMaterial         = v.findViewById(R.id.btnMaterial)
        btnUnit             = v.findViewById(R.id.btnUnit)
        btnExport           = v.findViewById(R.id.btnExport)
        btnLightToggle      = v.findViewById(R.id.btnLightToggle)
        // نتأكد إن كل أزرار التبديل تبدأ مقفولة دايمًا، حتى لو أندرويد حاول
        // يرجّع حالة قديمة محفوظة من قبل (بيحصل أحيانًا لما الشاشة تتعاد إنشاؤها)
        btnWireframe.isChecked = false
        btnLightToggle.isChecked = false
        btnAutoRotate.isChecked = false
        btnMeasureTool.isChecked = false
        btnBoundingBox.isChecked = false
        btnToggleToolbars   = v.findViewById(R.id.btnToggleToolbars)
        btnDirections       = v.findViewById(R.id.btnDirections)
        directionsPanel     = v.findViewById(R.id.directionsPanel)
        compassRing         = v.findViewById(R.id.compassRing)
        // حلقة زخرفية بتلف ببطء طول الوقت — نفس تأثير compassRotate في المعاينة
        android.view.animation.RotateAnimation(
            0f, 360f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 30_000L
            repeatCount = android.view.animation.Animation.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }.also { compassRing.startAnimation(it) }
        btnViewFront        = v.findViewById(R.id.btnViewFront)
        btnViewBack         = v.findViewById(R.id.btnViewBack)
        btnViewLeft         = v.findViewById(R.id.btnViewLeft)
        btnViewRight        = v.findViewById(R.id.btnViewRight)
        btnViewTop          = v.findViewById(R.id.btnViewTop)
        btnViewBottom       = v.findViewById(R.id.btnViewBottom)
        measurementCard     = v.findViewById(R.id.measurementCard)
        measurementText     = v.findViewById(R.id.measurementText)
        inspectionCard      = v.findViewById(R.id.inspectionCard)
        inspectionText      = v.findViewById(R.id.inspectionText)
        lightDialOverlay    = v.findViewById(R.id.lightDialOverlay)
        btnCloseLightWheel  = v.findViewById(R.id.btnCloseLightWheel)
        loadingContainer    = v.findViewById(R.id.loadingContainer)
        loadingProgress     = v.findViewById(R.id.loadingProgress)
        loadingText         = v.findViewById(R.id.loadingText)
    }

    /** يفتح أداة الإضاءة (الحلقة الكاملة 360°) ويجمّد حركة الموديل بالكامل
     * (دوران/زووم/Pan) طول ما هي مفعّلة — بنفس فكرة قفل الإيماءات وقت وضع
     * القياس (measurementModeActive)، هنا عن طريق GLViewerView.lightModeActive. */
    private fun openLightTool() {
        lightDialOverlay.visibility = View.VISIBLE
        btnCloseLightWheel.visibility = View.VISIBLE
        glViewerView.lightModeActive = true
    }

    /** يقفل أداة الإضاءة ويرجّع حركة الموديل تاني */
    private fun closeLightTool() {
        lightDialOverlay.visibility = View.GONE
        btnCloseLightWheel.visibility = View.GONE
        glViewerView.lightModeActive = false
        btnLightToggle.isChecked = false
    }

    /** يطوي شريطي الأدوات (العلوي والسفلي) لتوفير مساحة أكبر للمعاينة */
    private fun hideToolbars() {
        listOf(displayToolbar, bottomToolbar).forEach { bar ->
            bar.animate()
                .translationY(bar.height.toFloat())
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { bar.visibility = View.GONE }
                .start()
        }
        btnToggleToolbars.animate().rotation(180f).setDuration(220).start()
    }

    /** يرجّع شريطي الأدوات للظهور تاني */
    private fun showToolbars() {
        // في وضع DXF (ثنائي الأبعاد) الشريط العلوي (خامة/شبكي/وحدة/تصدير/إضاءة)
        // المفروض يفضل مخفي دايمًا لأنه خاص بالـ 3D بس — نتجاهله هنا لو is2DMode
        val barsToShow = if (is2DMode) listOf(bottomToolbar) else listOf(displayToolbar, bottomToolbar)
        barsToShow.forEach { bar ->
            bar.visibility = View.VISIBLE
            bar.translationY = bar.height.toFloat()
            bar.alpha = 0f
            bar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        btnToggleToolbars.animate().rotation(0f).setDuration(220).start()
    }

    private fun setupWelcome() {
        val ctx = requireContext()
        val savedName = MainActivity.getUserName(ctx)
        if (savedName.isEmpty()) {
            showNameDialog(ctx)
        } else {
            welcomeText.text = getString(R.string.welcome_greeting, savedName)
            welcomeText.visibility = View.VISIBLE
        }
    }

    private fun showNameDialog(ctx: Context) {
        val input = EditText(ctx).apply {
            hint = getString(R.string.dialog_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setTextColor(0xFFF2F3F5.toInt())
            setHintTextColor(0xFF9CA3AF.toInt())
            setPadding(40, 24, 40, 24)
            textSize = 16f
        }
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.dialog_welcome_title))
            .setMessage(getString(R.string.dialog_welcome_message))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_start_btn)) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.default_friend_name) }
                MainActivity.saveUserName(ctx, name)
                welcomeText.text = getString(R.string.welcome_greeting, name)
                welcomeText.visibility = View.VISIBLE
                // Overlay ترحيبي
                welcomeOverlay.text = getString(R.string.welcome_overlay, name)
                welcomeOverlay.visibility = View.VISIBLE
                welcomeOverlay.alpha = 0f
                welcomeOverlay.animate().alpha(1f).setDuration(400).withEndAction {
                    welcomeOverlay.animate().alpha(0f).setStartDelay(1800).setDuration(500)
                        .withEndAction { welcomeOverlay.visibility = View.GONE }.start()
                }.start()
            }.show()
    }

    private fun animateToolbarEntrance() {
        listOf(
            view?.findViewById<View>(R.id.topBar),
            view?.findViewById<View>(R.id.displayToolbar),
            view?.findViewById<View>(R.id.bottomToolbar)
        ).forEachIndexed { i, v ->
            v ?: return@forEachIndexed
            val dir = if (i == 0) -180f else 180f
            v.translationY = dir; v.alpha = 0f
            v.animate().translationY(0f).alpha(1f).setDuration(350)
                .setStartDelay(i * 80L).setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    private fun wireUpListeners() {
        btnToggleDxf.setOnClickListener {
            animBtn(it)
            if (is2DMode) switchTo3DMode() else switchTo2DMode()
        }

        btnMeasureTool.setOnClickListener { btn ->
            btn.isSelected = !btn.isSelected
            val isChecked = btn.isSelected
            animBtn(btn)
            if (is2DMode) {
                dxf2DView.measureModeOn = isChecked
                if (isChecked) {
                    showCenterHint(getString(R.string.toast_tap_two_points))
                }
            } else {
                measureModeOn = isChecked
                glViewerView.measurementModeActive = isChecked
                glViewerView.stlRenderer.suppressIdleFloat = isChecked
                android.util.Log.d("Amr3D_MeasureDebug", "toggle -> measureModeOn=$measureModeOn measurementModeActive=${glViewerView.measurementModeActive} currentModel=${currentModel != null}")
                if (!isChecked) {
                    glViewerView.stlRenderer.clearMeasurementPoints()
                    measurementCard.visibility = View.GONE
                } else {
                    inspectionCard.visibility = View.GONE
                    showCenterHint(getString(R.string.toast_tap_two_points))
                }
            }
        }

        glViewerView.onMeasureDrag = { x, y -> if (measureModeOn) handleMeasurementDrag(x, y) }

        // نفس الزرار بيتبدّل دوره حسب الوضع: "دوران تلقائي" في عرض STL ثلاثي الأبعاد
        // (مالوش معنى في DXF)، و"طبقات" في عرض DXF ثنائي الأبعاد (يعرض قائمة الطبقات
        // مع Checkbox لإخفاء/إظهار كل طبقة). الأيقونة والوصف بيتغيّروا في
        // switchTo2DMode/switchTo3DMode.
        btnAutoRotate.setOnClickListener { btn ->
            if (is2DMode) {
                animBtn(btn)
                showDxfLayersDialog()
            } else {
                btn.isSelected = !btn.isSelected
                val isChecked = btn.isSelected
                animBtn(btn)
                glViewerView.stlRenderer.autoRotate = isChecked
            }
        }
        // لو المستخدم لمس الشاشة عشان يدوّر يدويًا، الدوران التلقائي بيقف من نفسه —
        // هنا بنزامن شكل الزرار عشان يرجع "مطفي" بصريًا برضو
        glViewerView.onAutoRotateStopped = { btnAutoRotate.isChecked = false }
        glViewerView.onLongPressPivot = { x, y ->
            setPivotFromTouch(x, y)
            pivotFeedbackView.pulseAt(x, y)
            glViewerView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        dxf2DView.onDistanceMeasured = { dist ->
            Toast.makeText(context, getString(R.string.toast_dxf_distance, dist, getString(currentUnit.labelRes)), Toast.LENGTH_LONG).show()
        }

        btnInspect.setOnClickListener {
            animBtn(it)
            currentModel?.let { m -> showInspectionReport(m) }
                ?: Toast.makeText(context, getString(R.string.toast_open_file_first), Toast.LENGTH_SHORT).show()
        }

        btnResetView.setOnClickListener  { animBtn(it); if (is2DMode) dxf2DView.resetView() else resetCamera() }
        // نفس منطق تبديل الدور اللي استخدمناه في btnAutoRotate: "صندوق الأبعاد" في
        // عرض STL ثلاثي الأبعاد، و"لون خلفية العارض" في عرض DXF ثنائي الأبعاد (بديل
        // بيسمح للمستخدم يغيّر خلفية العارض بدل الأسود الثابت — زي زرار الخامة في الـ 3D).
        btnBoundingBox.setOnClickListener { btn ->
            if (is2DMode) {
                animBtn(btn)
                showDxfBackgroundPicker()
            } else {
                btn.isSelected = !btn.isSelected
                val isChecked = btn.isSelected
                animBtn(btn)
                glViewerView.stlRenderer.showBoundingBox = isChecked
            }
        }
        btnWhatsapp.setOnClickListener   { animBtn(it); openWhatsapp() }

        btnWireframe.setOnClickListener { btn ->
            btn.isSelected = !btn.isSelected
            val c = btn.isSelected
            animBtn(btn); glViewerView.stlRenderer.wireframeMode = c
        }

        btnMaterial.setOnClickListener  { animBtn(it); showMaterialGrid() }
        btnUnit.setOnClickListener      { animBtn(it); cycleUnit() }
        btnExport.setOnClickListener    { animBtn(it); exportCurrentView() }

        btnLightToggle.setOnClickListener { btn ->
            btn.isSelected = !btn.isSelected
            val c = btn.isSelected
            animBtn(btn)
            if (c) openLightTool() else closeLightTool()
        }
        btnCloseLightWheel.setOnClickListener { closeLightTool() }
        lightDialOverlay.onAngleChanged = { angle ->
            glViewerView.queueEvent { glViewerView.stlRenderer.lightAngle = angle }
        }

        // زر طي/إظهار شرائط الأدوات — بيدي مساحة أكبر للمعاينة
        btnToggleToolbars.setOnClickListener {
            animBtn(it)
            toolbarsVisible = !toolbarsVisible
            if (toolbarsVisible) showToolbars() else hideToolbars()
        }

        // أزرار الاتجاهات الـ 6
        btnDirections.setOnClickListener {
            animBtn(it)
            val showing = directionsPanel.visibility == View.VISIBLE
            if (showing) {
                directionsPanel.animate().alpha(0f).setDuration(150)
                    .withEndAction { directionsPanel.visibility = View.GONE }.start()
            } else {
                directionsPanel.alpha = 0f
                directionsPanel.visibility = View.VISIBLE
                directionsPanel.animate().alpha(1f).setDuration(200).start()
            }
        }
        // ⚠️ إصلاح: البوصلة كانت بتضيف ميلان -10° ثابت على أمام/خلف/يسار/يمين
        // كلهم (احتمالاً بقصد إحساس "3D" بدل منظور مسطح)، لكن ده كان يخلي
        // زاوية "يسار"/"أمام" تبان مايلة/غير حقيقية بدل ما تكون زاوية جانبية
        // أو أمامية صحيحة فعليًا — وده بالظبط اللي البلاغ وصفه. الحل: زاوية
        // مسطحة تمامًا (rotationX=0) لأي اتجاه جانبي/أمامي/خلفي حقيقي. فوق/تحت
        // فضلوا عند 89°/-89° (مش 90° بالظبط) عمدًا عشان يتجنبوا أي مشكلة رؤية
        // عند القمة تمامًا.
        btnViewFront.setOnClickListener  { jumpToView(0f, 0f);    hideDirections() }
        btnViewBack.setOnClickListener   { jumpToView(0f, 180f);  hideDirections() }
        btnViewLeft.setOnClickListener   { jumpToView(0f, 90f);   hideDirections() }
        btnViewRight.setOnClickListener  { jumpToView(0f, -90f);  hideDirections() }
        btnViewTop.setOnClickListener    { jumpToView(89f, 0f);   hideDirections() }
        btnViewBottom.setOnClickListener { jumpToView(-89f, 0f);  hideDirections() }

        glViewerView.onSingleTap = { x, y -> if (measureModeOn) handleMeasurementTap(x, y) }

        inspectionCard.setOnClickListener  { inspectionCard.visibility = View.GONE }
        measurementCard.setOnClickListener {
            measurementCard.visibility = View.GONE
            glViewerView.stlRenderer.clearMeasurementPoints()
        }
    }

    private fun hideDirections() {
        directionsPanel.visibility = View.GONE
    }

    private fun animBtn(v: View) {
        v.animate().scaleX(0.87f).scaleY(0.87f).setDuration(70)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            }.start()
    }

    // ══ حدود حجم الملف اللي تستوجب رسائل التنبيه على مرحلتين (البند 1.1) ══
    companion object {
        private const val STL_LARGE_FILE_BYTES = 70L * 1024L * 1024L  // 70 ميجابايت
        private const val DXF_LARGE_FILE_BYTES = 10L * 1024L * 1024L  // 10 ميجابايت
    }

    // ══ تحميل الملف — آمن حتى قبل onViewCreated ══
    fun loadFile(uri: Uri) {
        // إذا لم يكن الـ View جاهزاً بعد، نحفظ الـ URI ونحمّله لاحقاً
        if (!isAdded || view == null) {
            pendingUri = uri
            return
        }

        val isStl = getFileExtension(uri) != "dxf"

        // بنحسب حجم الملف بسرعة (من غير ما نفتحه كامل) قبل أي قراءة فعلية —
        // عشان نقدر نعرض رسالة التنبيه للملفات الكبيرة (البند 1.1) قبل ما نبدأ
        viewLifecycleOwner.lifecycleScope.launch {
            val sizeBytes = withContext(Dispatchers.IO) { quickFileSize(uri) }
            if (!isAdded || view == null) return@launch

            val threshold = if (isStl) STL_LARGE_FILE_BYTES else DXF_LARGE_FILE_BYTES
            if (sizeBytes >= 0 && sizeBytes >= threshold) {
                showLargeFileWarning(uri, isStl, sizeBytes)
            } else {
                proceedToLoad(uri, isStl, forceSimplify = false)
            }
        }
    }

    /** حجم الملف بسرعة عن طريق OpenableColumns.SIZE أولاً (من غير فتح/قراءة الملف
     * كامل). بعض مزوّدي المحتوى (خصوصًا مع بعض تطبيقات إدارة الملفات أو موفّري
     * SAF) مش بيرجّعوا عمود SIZE صحيح لسبب أو لآخر — لو حصل كده، بنرجع لقراءة
     * الملف بالكامل لحساب حجمه الحقيقي (نفس الاحتياط الموجود في STLParser أصلاً)
     * عشان رسالة تنبيه الملفات الكبيرة تشتغل بثبات مهما كان مصدر الملف، مش بس
     * لما يجي من مصدر خارجي بيرجّع SIZE صح زي واتساب. */
    private fun quickFileSize(uri: Uri): Long {
        val fromQuery = try {
            requireContext().contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
                } else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
        if (fromQuery > 0) return fromQuery

        return try {
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                var count = 0L; val buf = ByteArray(65536)
                var n = stream.read(buf)
                while (n >= 0) { count += n; n = stream.read(buf) }
                count
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun proceedToLoad(uri: Uri, isStl: Boolean, forceSimplify: Boolean) {
        if (isStl) loadStlFile(uri, forceSimplify) else loadDxfFile(uri)
    }

    /** نافذة تأكيد بشكل موحّد مع هوية التطبيق (خلفية داكنة + إطار خارجي، زي نافذة
     * اختيار الخامة بالظبط) بدل شكل AlertDialog الرمادي الافتراضي اللي مش متماشي
     * مع تصميم التطبيق. قابلة لإعادة الاستخدام لأي تنبيه تأكيد مستقبلي. */
    private fun showStyledConfirmDialog(
        title: String,
        message: String,
        positiveText: String,
        negativeText: String,
        onPositive: () -> Unit,
        onNegative: () -> Unit = {}
    ) {
        if (!isAdded) return
        val ctx = requireContext()
        val dialog = android.app.Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 34, 40, 22)
            setBackgroundResource(R.drawable.bg_dialog_panel)
        }

        root.addView(TextView(ctx).apply {
            text = title
            textSize = 17f
            setTextColor(0xFFFF8A1E.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 14)
        })

        root.addView(TextView(ctx).apply {
            text = message
            textSize = 14.5f
            setTextColor(0xFFE8E8EC.toInt())
            setLineSpacing(6f, 1.1f)
            setPadding(0, 0, 0, 22)
        })

        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        buttonRow.addView(TextView(ctx).apply {
            text = negativeText
            textSize = 14f
            setTextColor(0xFF9CA3AF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(22, 12, 22, 12)
            isClickable = true; isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, null
            )
            setOnClickListener { dialog.dismiss(); onNegative() }
        })
        buttonRow.addView(TextView(ctx).apply {
            text = positiveText
            textSize = 14f
            setTextColor(0xFFFF8A1E.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(22, 12, 22, 12)
            isClickable = true; isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FF8A1E), null, null
            )
            setOnClickListener { dialog.dismiss(); onPositive() }
        })
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            (320 * ctx.resources.displayMetrics.density).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    /** رسالة التنبيه الأولى: الملف كبير، استمرار/رجوع (البند 1.1 - خطوة 1) */
    private fun showLargeFileWarning(uri: Uri, isStl: Boolean, sizeBytes: Long) {
        if (!isAdded) return
        val sizeMb = sizeBytes / (1024.0 * 1024.0)
        val sizeText = String.format(Locale.US, "%.1f", sizeMb)
        showStyledConfirmDialog(
            title = getString(R.string.large_file_warning_title),
            message = getString(R.string.large_file_warning_message, sizeText),
            positiveText = getString(R.string.large_file_btn_continue),
            negativeText = getString(R.string.large_file_btn_back),
            onPositive = { showLargeFileConfirm(uri, isStl) },
            // "رجوع" → إلغاء التحميل تمامًا، رجوع لحالة "لا يوجد ملف محمّل" (ما بيحصلش أي تغيير)
            onNegative = {}
        )
    }

    /** رسالة التأكيد التانية: توضيح التبسيط (لـ STL) أو تأكيد عام (لـ DXF) — البند 1.1 خطوة 2 */
    private fun showLargeFileConfirm(uri: Uri, isStl: Boolean) {
        if (!isAdded) return
        // DXF ملف ثنائي الأبعاد بيتفهرس بطريقة مختلفة تمامًا ومفيهوش تبسيط (البند 1.2 خاص بـ STL بس)
        val message = if (isStl) getString(R.string.large_file_simplify_message)
                      else getString(R.string.large_file_confirm_message_generic)
        showStyledConfirmDialog(
            title = getString(R.string.large_file_confirm_title),
            message = message,
            positiveText = getString(R.string.large_file_btn_agree),
            negativeText = getString(R.string.large_file_btn_cancel),
            onPositive = { proceedToLoad(uri, isStl, forceSimplify = isStl) },
            // "إلغاء" → إلغاء التحميل، رجوع لحالة "لا يوجد ملف محمّل"
            onNegative = {}
        )
    }


    /** بيحدد امتداد الملف الحقيقي (من اسم الملف، مش بس من الـ URI) */
    private fun getFileExtension(uri: Uri): String {
        var name: String? = null
        try {
            requireContext().contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (_: Exception) {}
        val fileName = name ?: uri.lastPathSegment ?: ""
        return fileName.substringAfterLast('.', "").lowercase()
    }

    /** مسار تحميل ملفات STL — العارض ثلاثي الأبعاد (زي ما كان)
     * forceSimplify: true لو المستخدم وافق على تبسيط ملف كبير من رسائل التنبيه
     * (البند 1.1) — بيفرض تبسيط معتدل حتى لو إعداد الجودة الحالي "عالي" */
    /** يصفّر حالة أداة القياس بالكامل (واجهة + بيانات داخلية) — بينفّذ فورًا عند
     * بداية تحميل أي ملف جديد (قبل ما القراءة/التبسيط تاخد وقتها)، بغض النظر عن
     * حالة القياس على الملف اللي قبله أو وضع العرض الحالي. ده بيصلّح مشكلة إن
     * زرار القياس ونص "Distance: ..." القديم كانوا بيفضلوا ظاهرين فوق شاشة تحميل
     * الملف الجديد لو الملف الجديد أخد وقت في التحميل (لأن switchTo3DMode/
     * switchTo2DMode بترجع فورًا من غير تصفير لو إحنا أصلاً في نفس الوضع). */
    /** تلميح تعليمات مؤقت في **منتصف الشاشة** (بدل الـ Toast اللي كان بيظهر تحت
     * شريط الأدوات — تحت إصبع المستخدم مباشرة لحظة ما بيدوس على زرار زي القياس،
     * فمكانش بيلحق يشوفه). بيختفي تلقائيًا بعد شوية، وبنفس هوية التطبيق (خلفية
     * داكنة + إطار خارجي) بدل شكل الـ Toast الرمادي الافتراضي. */
    private fun showCenterHint(message: String) {
        if (!isAdded || view == null) return
        val ctx = requireContext()
        val hint = TextView(ctx).apply {
            text = message
            textSize = 14f
            setTextColor(0xFFE8E8EC.toInt())
            setPadding(44, 26, 44, 26)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dialog_panel)
            gravity = Gravity.CENTER
            alpha = 0f
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        viewerContainer.addView(hint, params)
        hint.animate().alpha(1f).setDuration(200).withEndAction {
            hint.postDelayed({
                if (hint.parent == null) return@postDelayed
                hint.animate().alpha(0f).setDuration(300).withEndAction {
                    (hint.parent as? ViewGroup)?.removeView(hint)
                }.start()
            }, 1600)
        }.start()
    }

    /** بيحدّث عداد Faces/Vertices أعلى شاشة العارض — أو بيخفيه لو الإعداد مقفول
     * من الإعدادات (show_face_count)، أو مفيش موديل خالص. الافتراضي: ظاهر. */
    private fun updateModelStatsOverlay(model: STLModel?) {
        if (!isAdded || view == null) return
        val prefs = requireContext().getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("show_face_count", true)
        if (!enabled || model == null || is2DMode) {
            modelStatsText.visibility = View.GONE
            return
        }
        val faces = model.triangleCount
        val vertices = faces * 3 // بيانات Flat غير مفهرسة — كل مثلث بنقطه الخاصة
        modelStatsText.text = getString(R.string.model_stats_overlay, faces, vertices)
        modelStatsText.visibility = View.VISIBLE
    }

    private fun resetMeasurementState() {
        measureModeOn = false
        glViewerView.measurementModeActive = false
        dxf2DView.measureModeOn = false
        btnMeasureTool.isChecked = false
        measurementCard.visibility = View.GONE
        glViewerView.queueEvent { glViewerView.stlRenderer.clearMeasurementPoints() }
    }

    private fun loadStlFile(uri: Uri, forceSimplify: Boolean = false) {
        switchTo3DMode()
        resetMeasurementState()
        modelStatsText.visibility = View.GONE
        showLoadingBar(getString(R.string.loading_file), 0)
        // بنحرر الموديل القديم من الذاكرة (لو موجود) قبل ما نبدأ نقرا الملف الجديد —
        // مش بس عند تبديل الوضع، عشان حتى تحميل ملف واحد كبير لوحده منوصلش لحد الذاكرة
        // بسرعة بسبب موديل سابق لسه قاعد. لازم يتنفذ على GL thread عشان بيلمس VBOs.
        glViewerView.queueEvent { glViewerView.stlRenderer.clearModel() }
        dxf2DView.clear() // مفيش داعي نسيب رسمة DXF قديمة قاعدة في الذاكرة ونحن بنفتح STL

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val model = withContext(Dispatchers.IO) {
                    STLParser.parse(requireContext(), uri) { percent ->
                        // بيتنادى من خيط IO — لازم ننقل التحديث للـ main thread
                        requireActivity().runOnUiThread {
                            updateLoadingBar(getString(R.string.loading_analyzing), percent)
                        }
                    }
                }

                if (!isAdded || view == null) return@launch

                // ── البند 1.2: تبسيط حقيقي لعدد المثلثات في الموديل الصلب نفسه ──
                // بيتنفذ هنا (فورًا بعد القراءة، قبل تصحيح المحور ورفع الـ GPU) عشان لو
                // اتبسط، النسخة الخام الأصلية الأكبر تبقى مرشحة إن الـ GC يحررها من
                // الذاكرة بدري، بدل ما تفضل قاعدة براحتها لحد ما ترفع على كارت الشاشة.
                val prefs = requireContext().getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
                val qualityLevel = when (prefs.getString("quality", "HIGH")) {
                    "LOW" -> 0; "MEDIUM" -> 1; else -> 2
                }
                var keepRatio = MeshDecimator.keepRatioForQuality(qualityLevel)
                if (forceSimplify) keepRatio = minOf(keepRatio, MeshDecimator.FORCED_LARGE_FILE_KEEP_RATIO)
                val simplifiedModel = if (keepRatio < 0.999f) {
                    withContext(Dispatchers.Default) { MeshDecimator.decimate(model, keepRatio) }
                } else model

                if (!isAdded || view == null) return@launch

                updateLoadingBar(getString(R.string.loading_preparing), 95)
                delay(200)
                updateLoadingBar(getString(R.string.loading_done), 100)
                delay(300)
                hideLoadingBar()

                // بنطبّق نفس تصحيح المحور اللي هيتطبّق على نسخة الـ GPU، عشان أي حاجة
                // تانية بتستخدم currentModel (زي أدوات القياس عن طريق RayPicker، وتقرير
                // الفحص) تشتغل على نفس فضاء الإحداثيات اللي فعليًا معروض على الشاشة —
                // لو سبناها على النسخة الخام، نقاط القياس هتطلع في مكان غلط بصريًا.
                // applyAxisConvention بتشتغل على simplifiedModel (الموديل بعد التبسيط،
                // لو حصل) عشان أدوات القياس/الفحص تتعامل مع نفس البيانات المبسّطة
                // المعروضة فعليًا على الشاشة، مش النسخة الخام الأصلية.
                val correctedModel = glViewerView.stlRenderer.applyAxisConvention(simplifiedModel)
                currentModel = correctedModel

                // رفع الموديل على GL thread
                glViewerView.queueEvent {
                    glViewerView.stlRenderer.setModel(correctedModel)
                }

                requireActivity().runOnUiThread {
                    emptyStateText.visibility  = View.GONE
                    welcomeText.visibility     = View.GONE
                    inspectionCard.visibility  = View.GONE
                    measurementCard.visibility = View.GONE
                    btnMeasureTool.isChecked   = false
                    btnWireframe.isChecked     = false
                    directionsPanel.visibility = View.GONE
                }

                // حفظ في التاريخ — المسار الحقيقي فقط
                saveToHistory(uri)

                updateModelStatsOverlay(correctedModel)

                Toast.makeText(context, getString(R.string.toast_triangle_count, correctedModel.triangleCount), Toast.LENGTH_SHORT).show()

            } catch (e: SecurityException) {
                if (!isAdded || view == null) return@launch
                hideLoadingBar()
                Toast.makeText(context, getString(R.string.toast_permission_error), Toast.LENGTH_LONG).show()
            } catch (e: OutOfMemoryError) {
                // نفس ملحوظة DXF فوق: OutOfMemoryError نوعها Error مش Exception، فمحتاجة
                // catch منفصل وإلا التطبيق بيكراش من غير أي رسالة بدل ما يقول للمستخدم
                // "الملف كبير جدًا" بشكل واضح ومهذّب
                if (!isAdded || view == null) return@launch
                hideLoadingBar()
                glViewerView.queueEvent { glViewerView.stlRenderer.clearModel() }
                Toast.makeText(context, getString(R.string.toast_file_too_large), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                hideLoadingBar()
                Toast.makeText(context, getString(R.string.toast_file_read_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** مسار تحميل ملفات DXF — شاشة عرض 2D حقيقية منفصلة تماماً عن محرك الـ 3D */
    private fun loadDxfFile(uri: Uri) {
        switchTo2DMode()
        resetMeasurementState()
        showLoadingBar(getString(R.string.loading_file), 0)
        // نفس المنطق بالظبط: نحرر موديل الـ STL القديم (لو موجود) قبل ما نفتح DXF جديد،
        // عشان الاتنين ميفضلوش قاعدين في الذاكرة مع بعض من غير داعي
        glViewerView.queueEvent { glViewerView.stlRenderer.clearModel() }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val dxfModel = withContext(Dispatchers.IO) {
                    DXFParser.parse(requireContext(), uri)
                }

                if (!isAdded || view == null) return@launch

                updateLoadingBar(getString(R.string.loading_preparing), 95)
                delay(150)
                updateLoadingBar(getString(R.string.loading_done), 100)
                delay(250)
                hideLoadingBar()

                dxf2DView.setModel(dxfModel)

                // زرار الطبقات بيتفعّل بس لو الملف فيه أكتر من طبقة واحدة — لو طبقة
                // واحدة بس مفيش داعي نوريه أصلًا (زي ما طلب في البند 1)
                btnAutoRotate.visibility = if (dxfModel.layers.size > 1) View.VISIBLE else View.GONE

                emptyStateText.visibility  = View.GONE
                welcomeText.visibility     = View.GONE

                saveToHistory(uri)

                Toast.makeText(context, getString(R.string.toast_entity_count, dxfModel.entityCount), Toast.LENGTH_SHORT).show()

            } catch (e: SecurityException) {
                if (!isAdded || view == null) return@launch
                hideLoadingBar()
                Toast.makeText(context, getString(R.string.toast_permission_error), Toast.LENGTH_LONG).show()
            } catch (e: OutOfMemoryError) {
                // ⚠️ مهم: OutOfMemoryError نوعها Error مش Exception في الجافا/الكوتلن —
                // يعني catch (e: Exception) العادي ما كانش بيلقطها خالص، والتطبيق كان
                // بيكراش تمامًا من غير ما يوري أي رسالة للمستخدم. دلوقتي بنلقطها صراحةً.
                if (!isAdded || view == null) return@launch
                hideLoadingBar()
                glViewerView.queueEvent { glViewerView.stlRenderer.clearModel() }
                dxf2DView.clear()
                Toast.makeText(context, getString(R.string.toast_file_too_large), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                hideLoadingBar()
                Toast.makeText(context, getString(R.string.toast_file_read_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** تفعيل وضع عرض DXF ثنائي الأبعاد — يخفي أدوات العارض ثلاثي الأبعاد اللي مالهاش معنى هنا */
    private fun switchTo2DMode() {
        if (is2DMode) return
        is2DMode = true
        currentModel = null
        glViewerView.visibility = View.GONE
        // بيوقف خيط رندر الـ GL تمامًا بدل ما يفضل شغال باستمرار في الخلفية وهو مخفي
        // (إخفاء الـ View بس (GONE) ما بيوقفش الرندر — GLSurfaceView ليه Thread منفصل).
        // بفضل preserveEGLContextOnPause=true، الموديل هيفضل جاهز للعرض على طول لما
        // نرجع للـ 3D من غير ما نحتاج نرفعه تاني لكارت الشاشة.
        glViewerView.onPause()
        dxf2DView.visibility = View.VISIBLE

        displayToolbar.visibility = View.GONE   // مادة / شبكي / وحدة / تصدير / إضاءة — كلها خاصة بالـ 3D
        btnMeasureTool.visibility = View.VISIBLE // القياس دلوقتي متاح في DXF كمان (شغال على شاشة الـ 2D)
        btnMeasureTool.isChecked  = false
        btnInspect.visibility     = View.GONE   // الفحص (الأبعاد الكاملة) لسه خاص بالموديل ثلاثي الأبعاد بس

        // "صندوق الأبعاد" مالوش معنى في عرض 2D — بيتحول لزرار "لون الخلفية" بدل ما يختفي
        btnBoundingBox.isChecked = false
        btnBoundingBox.setImageResource(R.drawable.ic_material_palette)
        btnBoundingBox.contentDescription = getString(R.string.dxf_background_tool)
        btnBoundingBox.visibility = View.VISIBLE
        btnBoundingBox.isChecked  = false
        btnDirections.visibility  = View.GONE
        directionsPanel.visibility = View.GONE
        closeLightTool()
        measurementCard.visibility = View.GONE
        inspectionCard.visibility  = View.GONE

        // "الدوران التلقائي" مالوش معنى في عرض 2D — بيتحول لزرار "طبقات" بدل ما يختفي،
        // وحالة الظهور النهائية (لو الملف فيه طبقة واحدة بس) بتتحدد بعد ما الملف يتحمّل
        // في loadDxfFile عن طريق updateLayersButtonVisibility()
        btnAutoRotate.isChecked = false
        btnAutoRotate.setImageResource(R.drawable.ic_layers_panel)
        btnAutoRotate.contentDescription = getString(R.string.layers_tool)
        btnAutoRotate.visibility = View.GONE

        modelStatsText.visibility = View.GONE

        // زرار التنقل بيوضح فين هيوديك لو دُست عليه (مش الوضع الحالي) — إحنا دلوقتي
        // في DXF (2D)، فبيبان "3D" عشان يوضح إنه هيرجعك للعارض الثلاثي
        btnToggleDxf.text = "3D"

        Toast.makeText(context, getString(R.string.dxf_mode), Toast.LENGTH_SHORT).show()
    }

    /** الرجوع لوضع العارض ثلاثي الأبعاد العادي (لما نفتح ملف STL) */
    private fun switchTo3DMode() {
        if (!is2DMode) return
        is2DMode = false
        dxf2DView.measureModeOn = false
        dxf2DView.visibility = View.GONE
        dxf2DView.clear()
        glViewerView.visibility = View.VISIBLE
        glViewerView.onResume()

        displayToolbar.visibility = View.VISIBLE
        btnMeasureTool.isChecked  = false
        btnMeasureTool.visibility = View.VISIBLE
        btnInspect.visibility     = View.VISIBLE
        btnBoundingBox.visibility = View.VISIBLE
        btnBoundingBox.isChecked = false
        btnBoundingBox.setImageResource(R.drawable.ic_bounding_box)
        btnBoundingBox.contentDescription = getString(R.string.bounding_box_tool_description)
        btnDirections.visibility  = View.VISIBLE

        // نفس الفكرة بالعكس: دلوقتي في 3D، فالزرار بيبان "2D" عشان يوضح إنه هيوديك للـ DXF
        btnToggleDxf.text = "2D"

        // إرجاع زرار "الدوران التلقائي" لشكله وسلوكه الأصلي بعد ما نطلع من وضع DXF
        btnAutoRotate.isChecked = false
        btnAutoRotate.setImageResource(R.drawable.ic_rotate)
        btnAutoRotate.contentDescription = getString(R.string.rotate_tool_description)
        btnAutoRotate.visibility = View.VISIBLE
    }

    /** بيعرض قائمة (Dialog) بكل طبقات ملف الـ DXF الحالي، وبجنب كل طبقة Checkbox
     * لإخفاء/إظهار عناصرها في العرض. الزرار اللي بيستدعي الدالة دي مش بيبان أصلًا
     * لو الملف فيه طبقة واحدة بس (اتحدد في loadDxfFile). */
    private fun showDxfLayersDialog() {
        val ctx = requireContext()
        val layers = dxf2DView.getLayers()
        if (layers.isEmpty()) return

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        // ── ملخص طول القطع الكلي — بيتحدث لحظيًا مع كل تغيير في إظهار/إخفاء الطبقات
        // (بما إن الطول بيتحسب من العناصر الظاهرة بس) ──
        val summaryText = TextView(ctx).apply {
            setTextColor(0xFFFF8A1E.toInt())
            textSize = 13f
            setPadding(8, 4, 8, 20)
        }
        fun refreshSummary() {
            val lengthMm = dxf2DView.totalCutLength()
            val count = dxf2DView.visibleCuttableEntityCount()
            summaryText.text = getString(R.string.dxf_cut_length_summary, lengthMm, count)
        }
        refreshSummary()
        container.addView(summaryText)

        for (layerName in layers) {
            val groupColor = dxf2DView.colorForGroup(layerName) ?: 0xFFFF8A1E.toInt()
            val label = if (dxf2DView.isColorGroup(layerName)) {
                getString(R.string.layer_color_group, dxf2DView.colorGroupIndex(layerName) + 1)
            } else {
                layerName
            }
            val swatch = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(groupColor)
                setSize(36, 36)
            }
            val row = CheckBox(ctx).apply {
                text = label
                isChecked = dxf2DView.isLayerVisible(layerName)
                setTextColor(0xFFF2F3F5.toInt())
                setPadding(8, 20, 8, 20)
                textSize = 15f
                compoundDrawablePadding = 20
                setCompoundDrawablesWithIntrinsicBounds(swatch, null, null, null)
                setOnCheckedChangeListener { _, checked ->
                    dxf2DView.setLayerVisible(layerName, checked)
                    refreshSummary()
                }
            }
            container.addView(row)
        }

        val scroll = ScrollView(ctx).apply { addView(container) }

        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.dialog_layers_title))
            .setView(scroll)
            .setPositiveButton(getString(R.string.dialog_close)) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    /** بيعرض قائمة بألوان خلفية عارض DXF القابلة للاختيار — نفس فكرة زرار الخامة في
     * الموديل ثلاثي الأبعاد بالظبط (شكل الـ Dialog وصف السواتش)، بس هنا بيغيّر لون
     * الخلفية بس (`DXF2DView.setBackgroundColor`) بدل ما يغيّر خامة موديل. */
    private fun showDxfBackgroundPicker() {
        val ctx = requireContext()
        // بنسيب معظم الخيارات غامقة عشان ألوان عناصر DXF (زي الأبيض والأصفر من AciColors)
        // تفضل واضحة فوقها؛ ألوان الشبكة/المحاور بتتلوّن تلقائيًا حسب سطوع أي خيار تختاره.
        val bgHex   = listOf("#0D0F12", "#000000", "#1E2229", "#0D1530", "#EDEDEF")
        val bgNames = listOf(
            getString(R.string.bg_color_dark), getString(R.string.bg_color_black),
            getString(R.string.bg_color_gray), getString(R.string.bg_color_navy),
            getString(R.string.bg_color_white)
        )

        val dialog = android.app.Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 20)
            setBackgroundResource(R.drawable.bg_dialog_panel)
        }
        root.addView(TextView(ctx).apply {
            text = getString(R.string.dxf_background_tool)
            textSize = 17f
            setTextColor(0xFFFF8A1E.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 20)
        })

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        bgNames.forEachIndexed { i, name ->
            val swatchSize = (48 * ctx.resources.displayMetrics.density).toInt()
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(3, 0, 3, 0) }
            }
            val swatch = object : View(ctx) {
                override fun onDraw(c: android.graphics.Canvas) {
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    val r = 10f * resources.displayMetrics.density
                    val rect = android.graphics.RectF(2f, 2f, width - 2f, height - 2f)
                    p.color = android.graphics.Color.parseColor(bgHex[i])
                    c.drawRoundRect(rect, r, r, p)
                    p.style = android.graphics.Paint.Style.STROKE
                    p.strokeWidth = 1.5f
                    p.color = 0x44FFFFFF
                    c.drawRoundRect(rect, r, r, p)
                }
            }
            swatch.layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize)
            swatch.setOnClickListener {
                dxf2DView.setDxfBackgroundColor(android.graphics.Color.parseColor(bgHex[i]))
                dialog.dismiss()
            }
            cell.addView(swatch)
            cell.addView(TextView(ctx).apply {
                text = name; textSize = 9f
                setTextColor(0xFF888888.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0, 3, 0, 0)
            })
            row.addView(cell)
        }
        root.addView(row)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            (340 * ctx.resources.displayMetrics.density).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    /** بتحدد لون خلفية العارضين (ثلاثي الأبعاد وDXF) تلقائيًا حسب الوضع الحالي (فاتح/غامق)
     * وقت فتح الشاشة — رمادي فاتح/أبيض مع الوضع الفاتح، وغامق زي المعتاد مع الوضع الغامق.
     * ده مجرد "افتراضي" بس؛ المستخدم لسه يقدر يغيّره يدويًا في أي وقت من زرار الخامة (3D)
     * أو زرار لون الخلفية (DXF) الموجودين أصلًا — مفيش تعارض بينهم. */
    private fun applyViewerBackgroundForCurrentMode() {
        if (AppDisplayMode.isLight(requireContext())) {
            glViewerView.stlRenderer.setBackgroundColor(0.92f, 0.92f, 0.92f) // نفس قيمة سواتش "أبيض" في زرار الخامة
            dxf2DView.setDxfBackgroundColor(android.graphics.Color.parseColor("#EDEDEF"))
        } else {
            glViewerView.stlRenderer.setBackgroundColor(0.10f, 0.11f, 0.13f) // نفس قيمة سواتش "داكن"
            dxf2DView.setDxfBackgroundColor(android.graphics.Color.parseColor("#0D0F12")) // نفس الافتراضي الأصلي في DXF2DView
        }
    }

    private fun saveToHistory(uri: Uri) {
        try {
            val cursor = requireContext().contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx >= 0 && !c.isNull(idx)) {
                        val path = c.getString(idx)
                        if (path != null) HistoryFragment.addToHistory(requireContext(), path)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun loadSTLFile(uri: Uri) = loadFile(uri)

    private var currentLoadingMsg = ""
    private var showLargeFileHint = false
    private var largeFileHintJob: Job? = null

    private fun renderLoadingText(msg: String, progress: Int) {
        currentLoadingMsg = msg
        val base = "$msg  $progress%"
        loadingText.text = if (showLargeFileHint)
            "$base\n${getString(R.string.loading_large_file_hint)}" else base
    }

    private fun showLoadingBar(msg: String, progress: Int) {
        if (view == null || !isAdded) return
        showLargeFileHint = false
        loadingContainer.visibility = View.VISIBLE
        loadingContainer.alpha = 1f
        loadingProgress.progress = progress
        renderLoadingText(msg, progress)

        // لو التحميل استغرق وقت طويل (ملف كبير نسبياً) بنوضّح ده للمستخدم بدل ما يفضل مستني من غير تفسير
        largeFileHintJob?.cancel()
        largeFileHintJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(4000)
            if (isAdded && view != null && loadingContainer.visibility == View.VISIBLE) {
                showLargeFileHint = true
                renderLoadingText(currentLoadingMsg, loadingProgress.progress)
            }
        }
    }

    private fun updateLoadingBar(msg: String, progress: Int) {
        if (view == null || !isAdded) return
        loadingProgress.progress = progress
        renderLoadingText(msg, progress)
    }

    private fun hideLoadingBar() {
        largeFileHintJob?.cancel()
        if (view == null || !isAdded) return
        loadingContainer.animate().alpha(0f).setDuration(300).withEndAction {
            if (isAdded && view != null) {
                loadingContainer.visibility = View.GONE
                loadingContainer.alpha = 1f
            }
        }.start()
    }

    private fun jumpToView(targetRotX: Float, targetRotY: Float) {
        val r = glViewerView.stlRenderer
        if (r.autoRotate) { r.autoRotate = false; btnAutoRotate.isChecked = false }
        val sx = r.rotationX; val sy = r.rotationY
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                r.rotationX = sx + (targetRotX - sx) * t
                r.rotationY = sy + (targetRotY - sy) * t
            }
        }.start()
    }

    private fun resetCamera() {
        val r = glViewerView.stlRenderer
        if (r.autoRotate) { r.autoRotate = false; btnAutoRotate.isChecked = false }
        r.rotationX = -25f; r.rotationY = 35f
        r.scaleFactor = 1f; r.panX = 0f; r.panY = 0f
        r.pivotOverride = null
        glViewerView.queueEvent { r.updateProjection() }
    }

    // ══ قائمة الخامات — كرات زجاجية ══
    private fun showMaterialGrid() {
        val materials = STLRenderer.Material.values()
        val ctx = requireContext()

        // ألوان الكرات (gradient start, gradient end, glow) — بترتيب مطابق تمامًا
        // لترتيب Material.values() الجديد (كل الخامات Clay دلوقتي، الفرق بينهم اللون بس)
        // ⚠️ نفس قيم الألوان دي بتبان "زاهية" على خلفية غامقة، لكن نفسها بالظبط
        // بتبان باهتة/شاحبة على خلفية بيضاء (خدعة تباين بصري معروفة) — عشان كده
        // فيه Palette منفصلة مخصوصة للثيم الفاتح، أعمق وأشبع لونيًا، تظهر بوضوح
        // على خلفية بيضاء بدل ما تختفي فيها.
        val isLightTheme = AppDisplayMode.isLight(ctx)
        val ballColors = if (isLightTheme) listOf(
            Triple("#8A8D93", "#33363B", "#55585E"),  // كلاي رمادي
            Triple("#F2ECD8", "#9F9880", "#7A7460"),  // كلاي أبيض
            Triple("#3E8FE0", "#0B2C55", "#1E5B96"),  // كلاي أزرق
            Triple("#9C6636", "#2A1608", "#5C3618"),  // كلاي بني
            Triple("#FF8A24", "#8A3C02", "#C9600E"),  // كلاي برتقالي
            Triple("#3A3A40", "#08080A", "#1C1C20"),  // كلاي أسود
            Triple("#F5C518", "#7A5A00", "#C89A10"),  // كلاي أصفر
            Triple("#D8452F", "#4A0E0A", "#8C2318"),  // كلاي أحمر
        ) else listOf(
            Triple("#B8B8BC", "#3A3A3E", "#6E6E74"),  // كلاي رمادي
            Triple("#FAF8F2", "#B0AC9E", "#D8D4C8"),  // كلاي أبيض
            Triple("#7FB0E0", "#1A3860", "#3A6CA0"),  // كلاي أزرق
            Triple("#B8825A", "#3A2414", "#7A5030"),  // كلاي بني
            Triple("#FFB068", "#7A3808", "#C06818"),  // كلاي برتقالي
            Triple("#4A4A50", "#0A0A0C", "#242428"),  // كلاي أسود
            Triple("#FFE888", "#806810", "#D4A828"),  // كلاي أصفر
            Triple("#E0806E", "#601A16", "#A03A30"),  // كلاي أحمر
        )

        val bgColors = listOf(
            floatArrayOf(0.10f,0.11f,0.13f), floatArrayOf(0.02f,0.02f,0.02f),
            floatArrayOf(0.22f,0.24f,0.27f), floatArrayOf(0.92f,0.92f,0.92f),
            floatArrayOf(0.05f,0.08f,0.18f)
        )
        val bgNames  = listOf("داكن","أسود","رمادي","أبيض","كحلي")
        val bgHex    = listOf("#1A1D24","#050607","#3A3D44","#EEEEF0","#0D1530")

        val dialog = android.app.Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val scroll = android.widget.ScrollView(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 20)
            setBackgroundResource(R.drawable.bg_dialog_panel)
            // rounded corners via clip
        }

        // عنوان
        root.addView(TextView(ctx).apply {
            text = "🎨  اختر الخامة"
            textSize = 17f
            setTextColor(0xFFFF8A1E.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 20)
        })

        // شبكة 4 × 2
        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 4
        }

        materials.forEachIndexed { i, mat ->
            val colors = ballColors.getOrNull(i)
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(6, 6, 6, 6)
            }

            // الكرة — Canvas مرسومة
            val ball = object : android.view.View(ctx) {
                override fun onDraw(c: android.graphics.Canvas) {
                    val w = width.toFloat(); val h = height.toFloat()
                    val r = minOf(w,h)/2f - 3f
                    val cx = w/2f; val cy = h/2f
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

                    // توهج خارجي
                    if (colors != null) {
                        val glowColor = android.graphics.Color.parseColor(colors.third)
                        p.maskFilter = android.graphics.BlurMaskFilter(r*0.5f,
                            android.graphics.BlurMaskFilter.Blur.NORMAL)
                        p.color = (glowColor and 0x00FFFFFF) or 0x44000000
                        c.drawCircle(cx, cy+4f, r*0.85f, p)
                        p.maskFilter = null
                    }

                    // Gradient الرئيسي
                    if (colors != null) {
                        p.shader = android.graphics.RadialGradient(
                            cx - r*0.25f, cy - r*0.25f, r*1.1f,
                            intArrayOf(
                                android.graphics.Color.parseColor(colors.first),
                                android.graphics.Color.parseColor(colors.second)
                            ),
                            floatArrayOf(0f, 1f),
                            android.graphics.Shader.TileMode.CLAMP
                        )
                    } else {
                        p.color = 0xFF444444.toInt()
                    }
                    c.drawCircle(cx, cy, r, p)
                    p.shader = null

                    // بريق PS5 (أبيض ناعم من أعلى يسار)
                    p.shader = android.graphics.RadialGradient(
                        cx - r*0.22f, cy - r*0.30f, r*0.55f,
                        intArrayOf(0xAAFFFFFF.toInt(), 0x00FFFFFF),
                        floatArrayOf(0f, 1f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    c.drawCircle(cx - r*0.12f, cy - r*0.18f, r*0.5f, p)
                    p.shader = null

                    // حلقة بريق خارجية — بيضاء خفيفة على الغامق (بريق)، غامقة خفيفة
                    // على الفاتح (حد تعريف) عشان الكرة تفضل واضحة الحواف في الحالتين
                    p.style = android.graphics.Paint.Style.STROKE
                    p.strokeWidth = 1.5f
                    p.color = if (isLightTheme) 0x33000000 else 0x22FFFFFF
                    c.drawCircle(cx, cy, r, p)
                }
            }

            val size = (72 * ctx.resources.displayMetrics.density).toInt()
            ball.layoutParams = LinearLayout.LayoutParams(size, size)
            ball.setOnClickListener {
                glViewerView.stlRenderer.setMaterial(mat)
                Toast.makeText(ctx, mat.nameAr, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }

            val name = TextView(ctx).apply {
                text = mat.nameAr; textSize = 10f
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 4, 0, 0)
            }

            cell.addView(ball)
            cell.addView(name)

            val lp = android.widget.GridLayout.LayoutParams().apply {
                columnSpec = android.widget.GridLayout.spec(i % 4, 1f)
                rowSpec    = android.widget.GridLayout.spec(i / 4)
                width  = 0
                height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                setMargins(4, 4, 4, 4)
            }
            cell.layoutParams = lp
            grid.addView(cell)
        }
        root.addView(grid)

        // فاصل خلفية
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.setMargins(0,20,0,12) }
            setBackgroundColor(0xFF2A2E38.toInt())
        })
        root.addView(TextView(ctx).apply {
            text = "الخلفية"; textSize = 12f
            setTextColor(0xFF666666.toInt()); setPadding(0,0,0,10)
        })

        // صف الخلفيات
        val bgRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        bgNames.forEachIndexed { i, name ->
            val swatchSize = (48 * ctx.resources.displayMetrics.density).toInt()
            val cell2 = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.setMargins(3,0,3,0) }
            }
            val swatch = object : android.view.View(ctx) {
                override fun onDraw(c: android.graphics.Canvas) {
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    val r = 10f * resources.displayMetrics.density
                    val rect = android.graphics.RectF(2f,2f,width-2f,height-2f)
                    p.color = android.graphics.Color.parseColor(bgHex[i])
                    c.drawRoundRect(rect, r, r, p)
                    p.style = android.graphics.Paint.Style.STROKE
                    p.strokeWidth = 1.5f
                    p.color = 0x44FFFFFF
                    c.drawRoundRect(rect, r, r, p)
                }
            }
            swatch.layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize)
            swatch.setOnClickListener {
                val c = bgColors[i]
                glViewerView.stlRenderer.setBackgroundColor(c[0], c[1], c[2])
                dialog.dismiss()
            }
            cell2.addView(swatch)
            cell2.addView(TextView(ctx).apply {
                text = name; textSize = 9f
                setTextColor(0xFF888888.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0,3,0,0)
            })
            bgRow.addView(cell2)
        }
        root.addView(bgRow)

        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.window?.setLayout(
            (340 * ctx.resources.displayMetrics.density).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun cycleUnit() {
        currentUnit = when (currentUnit) {
            MeasurementUnit.MM   -> MeasurementUnit.CM
            MeasurementUnit.CM   -> MeasurementUnit.INCH
            MeasurementUnit.INCH -> MeasurementUnit.MM
        }
        btnUnit.text = getString(currentUnit.labelRes)
        dxf2DView.currentUnit = currentUnit
        currentModel?.let { if (inspectionCard.visibility == View.VISIBLE) showInspectionReport(it) }
        val pts = glViewerView.stlRenderer.getMeasurementPoints()
        if (pts.size == 2) updateMeasurementText(pts[0], pts[1])
    }

    private fun exportCurrentView() {
        if (currentModel == null) { Toast.makeText(context, getString(R.string.toast_open_file_first), Toast.LENGTH_SHORT).show(); return }
        val r = glViewerView.stlRenderer
        val w = r.getSurfaceWidth(); val h = r.getSurfaceHeight()
        if (w <= 0 || h <= 0) return
        glViewerView.queueEvent {
            val bmp = r.captureFrame(w, h)
            requireActivity().runOnUiThread { saveAndShareBitmap(bmp) }
        }
    }

    private fun saveAndShareBitmap(bitmap: Bitmap) {
        try {
            val file = File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "Amr3D_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(requireContext(),
                "${requireContext().packageName}.fileprovider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, getString(R.string.share_export_title)))
        } catch (e: Exception) {
            Toast.makeText(context, getString(R.string.toast_save_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun openWhatsapp() {
        val phone = "201009172167"
        val msg = Uri.encode(getString(R.string.whatsapp_default_msg))
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("whatsapp://send?phone=$phone&text=$msg")).apply { setPackage("com.whatsapp") })
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=$msg")))
        }
    }

    /** بتتنادى أول ما الإصبع يلمس الشاشة (مش وضع قياس) — بتحدد مركز الدوران لحركة
     * السحب دي على أساس نقطة اللمس نفسها على سطح الموديل (raycast)، بدل ما يفضل
     * دايمًا مركز الصندوق المحيط اللي ممكن يكون بعيد عن شكل الموديل الفعلي */
    private fun setPivotFromTouch(screenX: Float, screenY: Float) {
        val model = currentModel ?: return
        val r = glViewerView.stlRenderer
        val ray = RayPicker.screenPointToRay(screenX, screenY,
            r.getSurfaceWidth(), r.getSurfaceHeight(),
            r.getCurrentModelMatrix(), r.getCurrentViewMatrix(), r.getCurrentProjectionMatrix())
        // لو مفيش تقاطع (لمس مكان فاضي) بيرجع null، ومعاه STLRenderer بترجع تلقائي
        // لمركز الموديل الافتراضي (السلوك القديم) — مفيش داعي لأي حالة خاصة هنا
        r.pivotOverride = RayPicker.findClosestIntersection(ray, model)
    }

    private fun handleMeasurementTap(screenX: Float, screenY: Float) {
        val model = currentModel ?: run {
            // كان بيرجع من غير أي تنبيه — ده كان بيدّي إحساس إن الأداة "مش شغالة"
            // في حين إن المشكلة كانت إن الموديل لسه مش جاهز وقت اللمس (race condition)
            Toast.makeText(context, getString(R.string.toast_open_file_first), Toast.LENGTH_SHORT).show()
            android.util.Log.d("Amr3D_MeasureDebug", "handleMeasurementTap: currentModel == null")
            return
        }
        val r = glViewerView.stlRenderer
        android.util.Log.d("Amr3D_MeasureDebug", "tap at ($screenX,$screenY) surface=${r.getSurfaceWidth()}x${r.getSurfaceHeight()}")
        val ray = RayPicker.screenPointToRay(screenX, screenY,
            r.getSurfaceWidth(), r.getSurfaceHeight(),
            r.getCurrentModelMatrix(), r.getCurrentViewMatrix(), r.getCurrentProjectionMatrix())
        android.util.Log.d("Amr3D_MeasureDebug", "ray origin=${ray.origin.toList()} dir=${ray.direction.toList()}")
        val hit = RayPicker.findClosestIntersection(ray, model) ?: run {
            android.util.Log.d("Amr3D_MeasureDebug", "no triangle intersection found")
            Toast.makeText(context, getString(R.string.toast_no_point_selected), Toast.LENGTH_SHORT).show(); return
        }
        android.util.Log.d("Amr3D_MeasureDebug", "hit=${hit.toList()}")
        r.addMeasurementPoint(hit)
        val pts = r.getMeasurementPoints()
        if (pts.size == 2) updateMeasurementText(pts[0], pts[1])
        else {
            measurementText.text = getString(R.string.measure_first_point)
            measurementCard.visibility = View.VISIBLE
        }
    }

    /** بتتنادى باستمرار أثناء سحب الإصبع بعد اختيار أول نقطة قياس — بتحدّث معاينة حية
     * للنقطة التانية والمسافة من غير ما تثبّتها فعلياً (التثبيت بيحصل لما الإصبع يترفع) */
    private fun handleMeasurementDrag(screenX: Float, screenY: Float) {
        val model = currentModel ?: return
        val r = glViewerView.stlRenderer
        val pts = r.getMeasurementPoints()
        if (pts.size != 1) return
        val ray = RayPicker.screenPointToRay(screenX, screenY,
            r.getSurfaceWidth(), r.getSurfaceHeight(),
            r.getCurrentModelMatrix(), r.getCurrentViewMatrix(), r.getCurrentProjectionMatrix())
        val hit = RayPicker.findClosestIntersection(ray, model) ?: return
        r.setPreviewMeasurementPoint(hit)
        updateMeasurementText(pts[0], hit)
    }

    private fun updateMeasurementText(p1: FloatArray, p2: FloatArray) {
        val d = MeasurementTools.distanceBetween(p1, p2, currentUnit)
        measurementText.text = String.format(Locale.US, getString(R.string.measure_distance_label), d, getString(currentUnit.labelRes))
        measurementCard.visibility = View.VISIBLE
    }

    private fun showInspectionReport(model: STLModel) {
        if (inspectionCard.visibility == View.VISIBLE) { inspectionCard.visibility = View.GONE; return }
        val report = MeasurementTools.inspect(model, currentUnit)
        val u = getString(report.unit.labelRes)
        inspectionText.text = getString(R.string.inspection_report_header) + "\n" +
            getString(R.string.inspection_width_label, "%.2f".format(report.width), u) + "\n" +
            getString(R.string.inspection_depth_label, "%.2f".format(report.depth), u) + "\n" +
            getString(R.string.inspection_height_label, "%.2f".format(report.height), u)
        inspectionCard.visibility  = View.VISIBLE
        measurementCard.visibility = View.GONE
    }
}
