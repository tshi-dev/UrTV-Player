# Security

## Report a vulnerability

Do not open a public GitHub issue, discussion or pull request for a security problem.

Use the same anonymous report form as UrTV (it needs no account), from the UrTV repository:

    optimize/share.sh --preview --problem --owner "<what you found, in your words>" "<what you checked>"

Read what it prints (it is exactly what would be sent), then:

    optimize/share.sh --send

The report holds the steps and your words. Do not paste secrets, keys, addresses, playlist links or Wi-Fi names into it.

## In scope

- The app in `app/` and the parsers in `core/`: how they read playlists and guides from the network, and how they handle malformed input.
- The phone pairing page (the small local web server): it must stay on the local network, be protected by its session token,
  and close after use.
- Anything that stores or sends a user's playlist links, credentials or PIN somewhere it should not.
- The build files and workflow: a signing key or a secret must never appear in the repository, and the in-app updater (`SelfUpdate`, `SelfUpdater`) must keep its pinned GitHub hosts, its sha256, package-name, version and certificate checks, and must never run silently or on a debug/unofficial build.

## Out of scope

- Problems in Android, the TV maker's software, or the streams and servers a user adds themselves. The app ships no content
  and is not responsible for what a user's own sources serve.
- A TV or network that an attacker already controls.
- Reports that need the owner to run something that is not from this repository.

## What to expect

A reply is not promised on a fixed day; this is a small project. A fix goes into `main`, then into a release.

---

# الأمان

## الإبلاغ عن ثغرة

لا تفتح issue ولا نقاشًا ولا طلب دمج علنيًا لمشكلة أمنية.

استخدم نفس نموذج البلاغ المجهول في UrTV (بلا حساب) من مستودع UrTV: `optimize/share.sh --preview --problem ...` ثم اقرأ ما سيُرسل وبعدها `optimize/share.sh --send`.
لا تضع في البلاغ أسرارًا ولا مفاتيح ولا عناوين ولا روابط قوائم ولا أسماء واي فاي.

## داخل النطاق

- التطبيق في `app/` والمحللات في `core/`: قراءة القوائم والأدلة من الشبكة والتعامل مع المدخلات التالفة.
- صفحة الإقران من الهاتف (الخادم المحلي الصغير): تبقى على الشبكة المحلية، محمية برمز الجلسة، وتُغلق بعد الاستخدام.
- أي مكان يحفظ أو يرسل روابط القوائم أو بيانات الدخول أو رمز PIN إلى جهة لا يجب أن تصلها.
- ملفات البناء وسير العمل: لا مفتاح توقيع ولا سر ولا تحديث ذاتي في المستودع.

## خارج النطاق

- مشاكل أندرويد أو برامج صانع التلفاز، أو البثوث والخوادم التي يضيفها المستخدم. التطبيق بلا محتوى وليس مسؤولًا عمّا تقدمه مصادر المستخدم.
- جهاز أو شبكة يتحكم بها مهاجم أصلًا.
- بلاغات تتطلب من المالك تشغيل شيء ليس من هذا المستودع.

## ماذا تتوقع

لا نَعِد بالرد في يوم محدد؛ هذا مشروع صغير. يدخل الإصلاح في `main` ثم في إصدار.
