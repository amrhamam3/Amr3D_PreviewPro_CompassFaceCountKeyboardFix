# Amr3DPreview Pro v7

تطبيق أندرويد لعرض ملفات STL و DXF ثلاثية الأبعاد بتقنية OpenGL ES.

## المميزات
- عرض ملفات STL ثلاثية الأبعاد
- عرض ملفات DXF
- وضع Wireframe
- أدوات القياس
- تحديد الأسطح بـ Ray Picking
- مكعب التوجيه (View Cube)
- سجل الملفات المفتوحة

## البناء التلقائي (GitHub Actions)

يتم البناء تلقائياً عند كل push على فرع `main` أو `master`.

### Artifacts
- **Debug APK** — جاهز للاختبار الفوري
- **Release APK** — جاهز للنشر (unsigned)

### كيفية تشغيل البناء يدوياً
```
Actions → Android Build & Release → Run workflow
```

## متطلبات البناء اليدوي
- JDK 17
- Android SDK (compileSdk 34)
- Gradle 8.4

```bash
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK
./gradlew test             # Unit Tests
```

## متطلبات التشغيل
- Android 7.0+ (API 24)
- OpenGL ES 2.0
