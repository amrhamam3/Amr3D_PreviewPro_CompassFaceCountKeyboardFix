package com.amr3d.preview.pro

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * فيديو تعريف بالتطبيق — بيتشغّل في حالتين (البند: انترو الفيديو):
 * 1) أول مرة يتثبّت فيها التطبيق (مرة واحدة بس — بعدين مابيتكررش تلقائيًا)،
 *    بيتفتح من [SplashActivity] قبل ما ينتقل لـ MainActivity.
 * 2) لما المستخدم يضغط زرار "شاهد فيديو التعريف" من صفحة الإعدادات — في الحالة
 *    دي بيتفتح في أي وقت، من غير ما يأثر على علامة "الانترو اتشاف قبل كده".
 *
 * الفرق بين الحالتين اتحدد بـ EXTRA_IS_FIRST_LAUNCH: لو true، بعد ما يخلص (أو
 * المستخدم يعمل تخطي) بنعلّم إن الانترو "اتشاف" (markIntroAsSeen) قبل ما نرجع
 * لـ SplashActivity، عشان مايتكررش تاني في أي فتح جاي للتطبيق.
 */
class IntroVideoActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "amr3d_prefs"
        private const val KEY_INTRO_SEEN = "intro_video_seen"
        private const val EXTRA_IS_FIRST_LAUNCH = "is_first_launch"

        /** true لو الانترو اتشاف قبل كده (أو المستخدم عمل تخطي قبل كده) — بيستخدمها
         * SplashActivity عشان يقرر يعرض الفيديو ولا لأ */
        fun hasSeenIntro(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_INTRO_SEEN, false)

        private fun markIntroAsSeen(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_INTRO_SEEN, true).apply()
        }

        /** بيفتح فيديو الانترو كجزء من تسلسل أول تشغيل — لازم علّم "اتشاف" فور
         * ما يخلص (أو المستخدم يعمل تخطي)، عشان مايتكررش في أي فتح تاني للتطبيق */
        fun startAsFirstLaunch(context: Context) {
            context.startActivity(Intent(context, IntroVideoActivity::class.java).apply {
                putExtra(EXTRA_IS_FIRST_LAUNCH, true)
                if (context !is AppCompatActivity) flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        /** بيفتح نفس الفيديو للمشاهدة الاختيارية (من زرار "حول" في الإعدادات) —
         * من غير ما يأثر على علامة "أول تشغيل" خالص */
        fun startAsReplay(context: Context) {
            context.startActivity(Intent(context, IntroVideoActivity::class.java).apply {
                putExtra(EXTRA_IS_FIRST_LAUNCH, false)
            })
        }
    }

    private lateinit var videoView: VideoView
    private var isFirstLaunch = false
    private var finished = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppDisplayMode.applySavedMode(this)
        super.onCreate(savedInstanceState)

        isFirstLaunch = intent?.getBooleanExtra(EXTRA_IS_FIRST_LAUNCH, false) ?: false

        // Fullscreen كامل — نفس أسلوب SplashActivity
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        setContentView(R.layout.activity_intro_video)
        videoView = findViewById(R.id.introVideoView)

        val btnSkip = findViewById<android.widget.TextView>(R.id.btnSkipIntro)
        btnSkip.setOnClickListener { finishIntro() }

        val uri = Uri.parse("android.resource://$packageName/${R.raw.intro_video}")
        videoView.setVideoURI(uri)
        // من غير MediaController (بار تحكم الفيديو الافتراضي) — تجربة انترو نظيفة
        // بره أي أزرار تحكم غير زرار التخطي بس
        videoView.setMediaController(null)
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            videoView.start()
        }
        videoView.setOnCompletionListener { finishIntro() }
        videoView.setOnErrorListener { _, _, _ ->
            // لو الفيديو فشل لأي سبب (ملف تالف، مشغّل غير مدعوم..)، منسيبش المستخدم
            // عالق في شاشة سودة — نكمل المسار العادي فورًا
            finishIntro()
            true
        }
    }

    /** ينهي شاشة الفيديو ويكمل المسار الصحيح حسب الحالة (أول تشغيل أو مشاهدة اختيارية) */
    private fun finishIntro() {
        if (finished) return // حماية من نداء مزدوج (Completion + يدوس Skip في نفس اللحظة تقريبًا)
        finished = true
        if (isFirstLaunch) {
            // نعلّم "اتشاف" قبل الرجوع لـ SplashActivity عشان مفيش أي احتمال لولوب —
            // SplashActivity هيتأكد إن العلامة اتظبطت ويكمل مساره الطبيعي (سبلاش
            // عادي بالشعار والانيميشن، بعدين MainActivity زي أي فتح تاني)
            markIntroAsSeen(this)
            startActivity(Intent(this, SplashActivity::class.java))
        }
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::videoView.isInitialized) videoView.stopPlayback()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // الرجوع بزرار الموبايل بيتصرف زي زرار التخطي بالظبط (مايوقّفش المستخدم
        // في شاشة الفيديو من غير مخرج)
        finishIntro()
    }
}
