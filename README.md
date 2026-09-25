# ONU Manager HG8145V5 — تطبيق أندرويد لراوتر Huawei HG8145V5 (TE Data)

تطبيق أصلي (Kotlin + Jetpack Compose + OkHttp) بواجهة «مدير ONU»:
مراقبة فورية + تحكّم لحظي (بلا إعادة تشغيل) عبر واجهة الراوتر، بحساب `admin`.
إنجليزي أساسي + عربي، ومؤشر اتصال دائم، وحفظ آمن لبيانات الدخول.

## المنطق مبني على فحص حيّ للجهاز (انظر `../VERIFY-claude-2026-09-16.md`)
- الدخول: `GetRandCount.asp → login.cgi → sid` (إزالة BOM، باسورد base64).
- المراقبة: قراءة `GetLanUserDevInfo.asp` (مُحلِّل مضبوط على صيغة `new USERDevice(...)` بترميز `\xNN`).
- التحكّم: `setajax.cgi?x=<node>` بنفس عقدة وبارامترات صفحة الميزة + `onttoken` (يُجلب طازجاً لكل عملية).
  - **LED مؤكَّد** (`X_HW_SSMPPDT.Deviceinfo` / `x.X_HW_LedSwitch`).
  - DNS/بلوك جهاز: العُقد في `RouterViewModel.kt` **تحتاج تأكيد** بأسماء البارامترات الفعلية
    من كل صفحة (`setAction`/`addParameter`) — علّمتها بـ NOTE في الكود.
- الجلسة قصيرة ⇒ إعادة دخول تلقائية عند 403 (`withSession`).
- شهادة ذاتية التوقيع ⇒ OkHttp يثق بها (`RouterApi.buildTrustAllClient`).

## البناء (يحتاج Android SDK — غير مثبّت في بيئة الإنشاء)
هذه البيئة لا تحوي JDK/Gradle/Android SDK، فلم يُجمَّع الـAPK هنا. للبناء:

### الأسهل: Android Studio
1. افتح مجلد `WaslaApp/` في Android Studio (Hedgehog+).
2. سيُنزّل Gradle 8.9 + الاعتماديات تلقائياً (يوفّر أيضاً gradle-wrapper.jar وSDK).
3. Run ▶ على جهاز/محاكي أندرويد (minSdk 24).

### أو سطر الأوامر (لو عندك JDK 17 + Android SDK + Gradle 8.9)
```bash
cd WaslaApp
gradle wrapper            # يولّد gradle-wrapper.jar أول مرة
./gradlew assembleDebug   # الناتج: app/build/outputs/apk/debug/app-debug.apk
```
عيّن `ANDROID_HOME`/`local.properties` لمسار الـSDK.

## هيكل المشروع
```
app/src/main/
  AndroidManifest.xml
  java/com/hg8145v5/manager/
    MainActivity.kt          الثيم + نقطة الدخول
    net/RouterApi.kt         OkHttp + الدخول + setajax + التنزيل + المُحلِّلات
    data/CredStore.kt        حفظ مشفّر (EncryptedSharedPreferences)
    vm/RouterViewModel.kt    الحالة + الإجراءات (لحظي/ريبوت)
    ui/Screens.kt            واجهة Compose (دخول/رئيسية/أجهزة/حماية/المزيد)
    ui/Strings.kt            تبديل اللغة
  res/                       أيقونة، ألوان، أمان الشبكة
```

## ملاحظات أمان
- التطبيق يخاطب `192.168.100.1` محلياً فقط عبر HTTPS.
- بيانات الدخول تُحفظ بـ Android Keystore (EncryptedSharedPreferences)، ليست نصاً صريحاً.
- «تحديد سرعة لكل جهاز» و«تفعيل Telnet» و«إضافة مستخدم» تحتاج مسار ملف الإعدادات
  (تنزيل/تعديل/رفع + ريبوت) — غير مضمّنة في هذه النسخة الأولى.
