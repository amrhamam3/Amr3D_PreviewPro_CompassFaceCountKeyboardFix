package com.amr3d.preview.pro

import android.animation.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.animation.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.InputStream

class SplashActivity : AppCompatActivity() {

    private var splashDuration = 5500L
    private lateinit var wireframeView: WireframeSplashView
    private lateinit var ringView: RingView
    private lateinit var logoImg: ImageView
    private lateinit var titleText: LaserTextView
    private lateinit var subText: TextView
    private lateinit var modeLabel: TextView
    private lateinit var progressBar: GlowProgressBarView
    private val handler = Handler(Looper.getMainLooper())
    private var animId: android.animation.ValueAnimator? = null
    // true لو التطبيق اتفتح بملف من مصدر خارجي (واتساب/تليجرام) — السبلاش هنا مجرد
    // "منظر" تمهيدي قصير (2 ثانية) بدون شريط تحميل حقيقي، مع شارة توضح نوع العارض
    private var externalFileUri: android.net.Uri? = null
    private var isExternalStlFile = true

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    private val LABELS = listOf("TAP TO START", "LOADING...", "OPTIMIZING...", "READY ✓")

    override fun onCreate(savedInstanceState: Bundle?) {
        AppDisplayMode.applySavedMode(this) // لازم قبل super.onCreate عشان يتطبّق قبل ما الشاشة تترسم
        super.onCreate(savedInstanceState)

        // لو دي أول مرة يتفتح فيها التطبيق (فتح عادي من أيقونة التطبيق، مش فتح ملف
        // من مصدر خارجي زي واتساب/تليجرام)، نعرض فيديو التعريف الأول قبل أي حاجة
        // تانية — بيرجع تاني لـ SplashActivity (وهيكمل مساره الطبيعي زي العادة) فور
        // ما يخلص، وبعلّم إنه اتشاف عشان مايتكررش في أي فتح تاني للتطبيق.
        val isFileOpenIntent = intent?.action == Intent.ACTION_VIEW && intent?.data != null
        if (!isFileOpenIntent && !IntroVideoActivity.hasSeenIntro(this)) {
            IntroVideoActivity.startAsFirstLaunch(this)
            finish()
            return
        }

        // Fullscreen كامل
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        // من واتساب/تليجرام (فتح بملف من مصدر خارجي) — السبلاش دلوقتي بيظهر برضه (مش
        // بيتخطى خالص زي الأول)، بس لمدة أقصر وبدون شريط تحميل حقيقي (مجرد منظر تمهيدي)
        if (intent?.action == Intent.ACTION_VIEW && intent?.data != null) {
            externalFileUri = intent.data
            isExternalStlFile = getFileExtension(externalFileUri) != "dxf"
            splashDuration = 2400L // كافية إن شارة النوع تبان بوضوح قبل ما تختفي، وتفضل "قصيرة" فعلاً
        }

        setContentView(R.layout.activity_splash)

        wireframeView = findViewById(R.id.wireframeAnim)
        ringView      = findViewById(R.id.splashRingView)
        logoImg       = findViewById(R.id.splashLogo)
        titleText     = findViewById(R.id.splashTitle)
        subText       = findViewById(R.id.splashDev)
        modeLabel     = findViewById(R.id.splashModeLabel)
        progressBar   = findViewById(R.id.splashProgress)

        // تطبيق لون الثيم
        val theme = AppTheme.getCurrent(this)
        wireframeView.accentColor = theme.accent
        wireframeView.backgroundColorValue =
            if (AppDisplayMode.isLight(this)) 0xFFF1F2F5.toInt() else 0xFF020510.toInt()
        ringView.accent1 = theme.accent
        ringView.accent2 = theme.accentDark
        titleText.accentColor = theme.accent
        progressBar.accentColor = theme.accent
        modeLabel.setTextColor(theme.accent)
        modeLabel.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor((theme.accent and 0x00FFFFFF) or 0x22000000)
            setStroke((1.5f * resources.displayMetrics.density).toInt(), theme.accent)
        }
        if (externalFileUri != null) {
            modeLabel.text = if (isExternalStlFile) "🧊  3D MODE" else "📐  2D MODE"
        }

        // تحميل اللوجو من assets
        loadLogoFromAssets()

        // اخفاء كل العناصر في البداية
        listOf(logoImg, titleText, subText, modeLabel, progressBar).forEach { it.alpha = 0f }
        // اللوجو بيبدأ صغير جدًا ومايل في الفضاء (زي إنه جاي من بعيد) عشان يدخل
        // بإحساس "3D انترو" لما يتحرك ويثبت في مكانه بدل ما يظهر بتكبير بسيط بس
        logoImg.scaleX = 0.05f; logoImg.scaleY = 0.05f
        logoImg.rotationX = 55f
        logoImg.rotationY = -40f
        logoImg.cameraDistance = 14000f * resources.displayMetrics.density

        // لمس الشاشة يؤثر على الـ Wireframe
        window.decorView.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                wireframeView.onTouch(e.x, e.y)
                createRippleEffect(e.x, e.y)
            }
            true
        }

        startSplashSequence()
        startRenderLoop()
    }

    private fun loadLogoFromAssets() {
        try {
            val stream: InputStream = assets.open("logo.jpg")
            val bmp = BitmapFactory.decodeStream(stream)
            stream.close()
            logoImg.setImageBitmap(bmp)
        } catch (e: Exception) {
            logoImg.setImageResource(R.drawable.splash_logo)
        }
    }

    /** بيحدد امتداد الملف (من اسمه الحقيقي، مش بس من الـ URI) — نفس منطق
     * ViewerFragment.getFileExtension، بس هنا بسيطة ومكررة عمدًا لأن السبلاش هنا
     * غرضه الوحيد إنه يعرض شارة النوع، ومش محتاج يشارك أي حالة تانية مع العارض. */
    private fun getFileExtension(uri: android.net.Uri?): String {
        if (uri == null) return ""
        var name: String? = null
        try {
            contentResolver.query(
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

    private fun startSplashSequence() {
        // اللوجو يظهر من العدم بعد 300ms — بيدخل من بعيد ومايل (3D) وبعدين يثبت مسطّح
        handler.postDelayed({
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(logoImg, "alpha", 0f, 1f).setDuration(900),
                    ObjectAnimator.ofFloat(logoImg, "scaleX", 0.05f, 1.15f, 1f).setDuration(1100),
                    ObjectAnimator.ofFloat(logoImg, "scaleY", 0.05f, 1.15f, 1f).setDuration(1100),
                    ObjectAnimator.ofFloat(logoImg, "rotationX", 55f, 0f).setDuration(1100),
                    ObjectAnimator.ofFloat(logoImg, "rotationY", -40f, 0f).setDuration(1100)
                )
                interpolator = DecelerateInterpolator(2.2f)
                start()
            }
        }, 300)

        // نبضة توهج على اللوجو
        handler.postDelayed({
            ObjectAnimator.ofFloat(logoImg, "alpha", 1f, 0.7f, 1f, 0.85f, 1f).apply {
                duration = 600; interpolator = AccelerateDecelerateInterpolator(); start()
            }
        }, 1200)

        // العنوان
        handler.postDelayed({
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(titleText, "alpha", 0f, 1f).setDuration(600),
                    ObjectAnimator.ofFloat(titleText, "translationY", 40f, 0f).setDuration(600)
                )
                interpolator = OvershootInterpolator(1.5f); start()
            }
            titleText.translationY = 40f
        }, 1400)

        // النص الفرعي — بس للفتح العادي (من غير ملف خارجي). لما التطبيق يتفتح بملف من
        // مصدر خارجي، بنعرض شارة النوع (splashModeLabel) بدل النص ده، وأبكر شوية
        // (1500ms بدل 1800ms) عشان تبان بوضوح كافي قبل ما السبلاش يختفي بسرعة
        val subTextOrBadgeDelay = if (externalFileUri != null) 1500L else 1800L
        handler.postDelayed({
            val target = if (externalFileUri != null) modeLabel else subText
            target.visibility = View.VISIBLE
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(target, "alpha", 0f, 1f).setDuration(500),
                    ObjectAnimator.ofFloat(target, "translationY", 30f, 0f).setDuration(500)
                )
                interpolator = DecelerateInterpolator(); start()
            }
            target.translationY = 30f
        }, subTextOrBadgeDelay)

        if (externalFileUri == null) {
            // شريط التحميل — بس للفتح العادي (من غير ملف). لما التطبيق يتفتح بملف خارجي
            // مفيش شريط تحميل خالص هنا (البند 2 المبسّط) — السبلاش مجرد منظر قصير
            // والتحميل الفعلي بيحصل بعدين جوه MainActivity/ViewerFragment زي ما هو
            handler.postDelayed({
                ObjectAnimator.ofFloat(progressBar, "alpha", 0f, 1f).apply { duration=300; start() }

                animId = ValueAnimator.ofInt(0, 100).apply {
                    duration = splashDuration - 2000
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { anim ->
                        val p = anim.animatedValue as Int
                        progressBar.progress = p
                        val lblIdx = (p / 34).coerceAtMost(LABELS.size - 1)
                        subText.text = LABELS[lblIdx]
                        progressBar.statusText = LABELS[lblIdx]
                    }
                    start()
                }
            }, 2000)
        }

        // الانتقال للـ MainActivity
        handler.postDelayed({
            val root = window.decorView
            ObjectAnimator.ofFloat(root, "alpha", 1f, 0f).apply {
                duration = 500; interpolator = AccelerateInterpolator()
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // لو اتفتح بملف خارجي، بنمرر نفس الـ URI لـ MainActivity عشان
                        // تكمل التحميل الفعلي (مع رسائل تنبيه الملفات الكبيرة والتبسيط
                        // من مرحلة 1) بالظبط زي ما كانت بتعمل قبل كده
                        val next = Intent(this@SplashActivity, MainActivity::class.java)
                        externalFileUri?.let {
                            next.action = Intent.ACTION_VIEW
                            next.data = it
                            next.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        startActivity(next)
                        @Suppress("DEPRECATION")
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        finish()
                    }
                })
                start()
            }
        }, splashDuration)
    }

    private fun createRippleEffect(x: Float, y: Float) {
        val ripple = View(this).apply {
            val size = 60
            layoutParams = FrameLayout.LayoutParams(size, size).also {
                it.leftMargin = (x - size/2).toInt()
                it.topMargin  = (y - size/2).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(AppTheme.getCurrent(this@SplashActivity).accent and 0x00FFFFFF or 0x88000000.toInt())
            }
        }
        (window.decorView as? ViewGroup)?.addView(ripple)
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(ripple, "scaleX", 1f, 5f),
                ObjectAnimator.ofFloat(ripple, "scaleY", 1f, 5f),
                ObjectAnimator.ofFloat(ripple, "alpha", 0.6f, 0f)
            )
            duration = 600
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    (window.decorView as? ViewGroup)?.removeView(ripple)
                }
            })
            start()
        }
    }

    private var frameRunnable: Runnable? = null

    private fun startRenderLoop() {
        frameRunnable = object : Runnable {
            override fun run() {
                wireframeView.updatePhysics()
                wireframeView.invalidate()
                ringView.touchForce = wireframeView.touchForce
                ringView.tick()
                titleText.update()
                titleText.invalidate()
                progressBar.onFrameTick()
                handler.postDelayed(this, 16) // ~60fps
            }
        }
        handler.post(frameRunnable!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        frameRunnable?.let { handler.removeCallbacks(it) }
        animId?.cancel()
        handler.removeCallbacksAndMessages(null)
    }
}
