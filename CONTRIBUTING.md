# Contributing

Thank you. Small, safe changes are the best ones: a fix, a test, a clearer line of text.

## How

1. Fork the repository and make a branch.
2. Open the pull request against **`main`**.
3. Run the tests before you push:

       gradle :core:test

   The parser tests need no Android SDK. The app module (`:app`) is built by CI (`.github/workflows/build.yml`).

## Rules

- This is a player only. It ships **no channels, no playlists and no links**, and nothing in a pull request may add any
  (not even as an example or a default). The user adds their own sources.
- Never build or publish a release APK from your own machine, never add a signing key (`*.jks`, `*.keystore`, `local.properties`)
  to the repository, and never weaken the in-app updater (pinned GitHub hosts, sha256, package, version and certificate checks; never silent). Release signing happens in CI from secrets only.
- Never `git add` font files other than the open IBM Plex ones: the Thmanyah files come from a private repository in CI (`.gitignore` blocks `app/src/main/assets/fonts/`).
- Never skip, disable or quarantine a test to make it pass.
- Permissions stay at `INTERNET`, `ACCESS_NETWORK_STATE`, the normal `sa.urtv.permission.READ_LANG` and `REQUEST_INSTALL_PACKAGES` (only for the verified in-app update). No storage, overlay, analytics, ads or crash SDKs. `ManifestSurfaceTest` pins the list.
- No personal data anywhere (code, docs, tests, reports): no IP address, serial, Wi-Fi name, user name or file path.
  Tests use obvious placeholders (`2001:db8::`, `192.0.2.x`, `Example Name`, `example.com`).
- Country lists never include Israel or Western Sahara.
- Commits are authored by the project owner and carry no AI or assistant lines.
- Defaults stay as they are unless it is a bug.
- Pull requests from forks run the tests only (CI gives them no secrets); releases are cut by the owner.
- A security problem is reported privately, not as an issue: see [SECURITY.md](SECURITY.md).

## Arabic text

The screens and docs are in plain Saudi-leaning Arabic, short and warm: what it is, where to change it. Keep the same voice.
Every Arabic string has an English one beside it, and the layout must work right-to-left.

---

# المساهمة

شكرًا لك. أفضل التعديلات صغيرة وآمنة: إصلاح، أو اختبار، أو سطر أوضح.

## الطريقة

1. اعمل نسخة (fork) من المستودع وافتح فرعًا.
2. افتح طلب الدمج على الفرع **`main`**.
3. شغّل الاختبارات قبل الدفع: `gradle :core:test` (لا تحتاج Android SDK). وحدة التطبيق `:app` يبنيها CI.

## القواعد

- هذا مشغّل فقط: **لا قنوات ولا قوائم ولا روابط** داخله، ولا يُقبل طلب دمج يضيف شيئًا منها (ولا كمثال ولا كقيمة افتراضية). المستخدم يضيف مصادره بنفسه.
- لا تبنِ نسخة إصدار من جهازك، ولا تضف مفتاح توقيع إلى المستودع، ولا تُضعف المحدّث الداخلي (مضيفو GitHub المثبّتون، التحقق من الهاش والحزمة والإصدار والشهادة، ولا يعمل بصمت). التوقيع يتم في CI بالأسرار فقط.
- لا تتجاوز اختبارًا ولا تعطّله ولا تعزله لتنجح النتيجة.
- الصلاحيات تبقى `INTERNET` و`ACCESS_NETWORK_STATE` و`sa.urtv.permission.READ_LANG` العادية و`REQUEST_INSTALL_PACKAGES` (للتحديث الموثّق من داخل التطبيق فقط). بلا تخزين ولا تراكب ولا تحليلات ولا إعلانات.
- لا بيانات شخصية في أي مكان (الكود، الوثائق، الاختبارات): لا عنوان IP ولا رقم تسلسلي ولا اسم واي فاي ولا اسم مستخدم ولا مسار ملف.
- قوائم الدول لا تتضمن إسرائيل ولا الصحراء الغربية.
- الإيداعات باسم صاحب المشروع وبلا أي إشارة إلى الذكاء الاصطناعي.
- الإعدادات الافتراضية تبقى كما هي إلا إذا كان خطأً.
- المشكلة الأمنية تُبلَّغ بشكل خاص وليس كـ issue: انظر [SECURITY.md](SECURITY.md).
