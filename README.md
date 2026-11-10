<p align="center"><img src="docs/brand/icon-512.png" width="128" alt="UrTV Player"></p>

# UrTV Player

**English** · [العربية](#العربية)

A standalone IPTV player for Android TV. **It contains no channels, no playlists and no links.** You add your own sources;
the app only plays what you give it.

- Playlists: M3U/M3U8 URL, Xtream Codes, XMLTV guide; several playlists, groups, search, favourites, recents
- Add a playlist from your phone: scan the QR code on the TV (local network only, one-time session token)
- Movies and series (Xtream): poster grid, details, seasons, resume where you stopped; catch-up from the guide where the provider offers it
- Fast zapping (Up/Down, number entry), now/next, TV guide grid, audio/subtitle tracks, aspect modes, auto-reconnect
- Parental PIN for chosen groups
- Arabic (RTL) and English, D-pad only
- Minimal permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, the normal `sa.urtv.permission.READ_LANG` (reads only UrTV's language, no prompt) and `REQUEST_INSTALL_PACKAGES` (owner decision, used only when you tap «update» and UrTV is not installed: the Player downloads its own signed release from the public UrTV releases on GitHub, checks sha256, package name, version and our pinned certificate, and hands it to Android's installer; Android asks you to allow it once). No storage, overlay, analytics, ads or crash SDKs. Never updates silently, never on debug or unofficial builds.

## Install

The Player works on its own. UrTV is optional.

1. **Direct download** (latest signed release): <https://github.com/tshi-dev/UrTV-Player/releases/latest/download/urtv-player.apk>
   · checksum: `.../releases/latest/download/urtv-player.apk.sha256`
2. **With Downloader** (or any browser app on the TV): enter the link above, let it download, open the file and allow
   «install unknown apps» for that app once.
3. Open UrTV Player, add your own playlist (from your phone with the QR code, or by typing the link), done.

**Updates come from inside the app**: Settings > About shows «Update available» when a newer signed release exists (checked at most once a day,
never while you watch, never silent). The app downloads it, checks the published checksum, that it is the same app, newer, and signed with
the same key as your installed copy, and then Android's own installer asks you. Your playlists, favourites and settings stay.
If the UrTV home app is installed, it does this job instead.

**Privacy**: no analytics, no ads, no crash reporting; nothing is sent anywhere except to the playlist/guide/server addresses you
add and, once a day, one tiny request to GitHub to ask for the newest version. Playlists and logins are stored encrypted on the TV.
«Report or suggestion» in Settings > About only shows a QR code: nothing leaves the TV unless you scan it and press send on your phone.

Every release has the stable files `urtv-player.apk`, `urtv-player.apk.sha256` and `urtv-player.version` (one line: version code, a space, version name),
plus the same files with the version in the name. Releases below 1.0 are normal releases so the links above work; a tag with a suffix
(for example `v1.2.0-beta1`) is a pre-release and is never offered as "latest".

Build: `gradle :core:test` (parsers, no Android SDK needed) · `gradle :app:assembleDebug` (needs the Android SDK).
Release signing happens only in CI from secrets; no keys are in this repository.

## Credits and licences

Fonts: the app uses the open **IBM Plex Sans Arabic** (SIL OFL 1.1, [licence](licenses/OFL-IBM-Plex-Sans-Arabic.txt)) from this repository. Official release builds may also contain the
**Thmanyah** typeface, which is licensed only for use inside the built app: its files are **not** in this repository or its history and must never be added
(CI puts them in at build time from a private repository). Libraries: AndroidX/Compose, Media3, OkHttp, Coil, ZXing, kotlinx (Apache 2.0): see [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md).
The source code has no licence file yet: until the owner adds one, all rights are reserved by the owner.

### Remote control (player)

| Key | Action |
|---|---|
| Up / Down (or CH+/CH-) | previous / next channel |
| 0-9 | channel number (0 alone = previous channel) |
| OK | mini guide (channel list with now/next) |
| Left / Menu | audio, subtitles, aspect, favourite, reload |
| Right / Info | now/next banner |
| Back | close overlay, then leave the player |

In lists, hold OK on a channel to add it to favourites, or on a group to lock it with the PIN.

## العربية

مشغّل IPTV مستقل لأجهزة Android TV. **لا يحتوي على أي قنوات ولا قوائم تشغيل ولا روابط.** تضيف مصادرك بنفسك، والتطبيق يشغّل ما تعطيه فقط.

- قوائم التشغيل: رابط M3U/M3U8 أو Xtream Codes أو دليل XMLTV؛ عدة قوائم، مجموعات، بحث، مفضّلة، آخر ما شاهدت
- أضف القائمة من هاتفك: امسح رمز QR على التلفاز (الشبكة المحلية فقط، رمز جلسة لمرة واحدة)
- أفلام ومسلسلات (Xtream): ملصقات، تفاصيل، مواسم، وتابع من حيث توقفت؛ وإعادة البرامج الفائتة من الدليل إن وفّرها المزوّد
- تنقّل سريع بين القنوات، دليل البرامج، المسارات الصوتية والترجمة، أوضاع العرض، إعادة اتصال تلقائية
- رمز PIN للرقابة الأبوية على مجموعات تختارها
- عربي (RTL) وإنجليزي، بالريموت فقط
- أقل الصلاحيات: `INTERNET` و`ACCESS_NETWORK_STATE` وصلاحية UrTV العادية `sa.urtv.permission.READ_LANG` (تقرأ لغة UrTV فقط، بلا نافذة طلب). و`REQUEST_INSTALL_PACKAGES` (قرار المالك: لا تُستخدم إلا عند ضغطك «تحديث» وعدم وجود UrTV؛ ينزّل المشغّل إصداره الموقّع من إصدارات UrTV العامة على GitHub، ويتحقق من الهاش واسم الحزمة والإصدار وشهادتنا المثبّتة، ثم يسلّمه إلى مثبّت أندرويد الذي يطلب إذنك مرة واحدة). بلا تخزين ولا تراكب ولا تحليلات ولا إعلانات. لا تحديث صامت أبدًا.

## التثبيت

المشغّل يعمل وحده، ووجود UrTV اختياري.

1. **تنزيل مباشر** (آخر إصدار موقّع): <https://github.com/tshi-dev/UrTV-Player/releases/latest/download/urtv-player.apk>
   · ملف التحقق: `.../releases/latest/download/urtv-player.apk.sha256`
2. **بتطبيق Downloader** (أو أي متصفح على التلفاز): اكتب الرابط أعلاه، نزّل الملف، افتحه، واسمح بـ«تثبيت التطبيقات غير المعروفة» لذلك التطبيق مرة واحدة.
3. افتح UrTV Player وأضف قائمتك (من جوالك برمز QR أو بكتابة الرابط).

**التحديث من داخل التطبيق**: الإعدادات ثم «عن التطبيق» يعرض «تحديث متوفر» إذا وُجد إصدار موقّع أحدث (يُفحص مرة في اليوم على الأكثر، وليس أثناء المشاهدة، وليس بصمت).
ينزّله التطبيق ويتحقق من الهاش المنشور ومن أنه نفس التطبيق وأحدث وموقّع بنفس مفتاح نسختك، ثم يسألك مثبّت أندرويد. قوائمك ومفضّلتك وإعداداتك تبقى.
وإن كان تطبيق UrTV مثبّتًا فهو من يقوم بهذا.

**الخصوصية**: لا تحليلات ولا إعلانات ولا تقارير أعطال، ولا يُرسل شيء إلا إلى عناوين القوائم والدليل والخوادم التي تضيفها أنت، ومرة في اليوم طلب صغير إلى GitHub لمعرفة أحدث إصدار.
القوائم وبيانات الدخول تُحفظ مشفّرة على التلفاز. «تقرير أو اقتراح» في عن التطبيق يعرض رمز QR فقط: لا يخرج شيء من التلفاز إلا إذا مسحته وضغطت إرسال في جوالك.

## الشكر والتراخيص

الخط: يستخدم التطبيق الخط المفتوح **IBM Plex Sans Arabic** (SIL OFL 1.1، [الرخصة](licenses/OFL-IBM-Plex-Sans-Arabic.txt)) الموجود في المستودع. وقد تحتوي نسخ الإصدار الرسمية على خط **ثمانية (Thmanyah)**
المرخّص للاستخدام داخل التطبيق المبني فقط: ملفاته **ليست** في المستودع ولا في تاريخه ولا يجوز إضافتها (يضعها CI وقت البناء من مستودع خاص). المكتبات: AndroidX وCompose وMedia3 وOkHttp وCoil وZXing وkotlinx (Apache 2.0): انظر [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md).
ليس في الشيفرة ملف رخصة بعد: إلى أن يضيفه المالك، الحقوق محفوظة لصاحب المشروع.

### أزرار الريموت (المشغّل)

| الزر | الوظيفة |
|---|---|
| أعلى / أسفل (أو CH+/CH-) | القناة السابقة / التالية |
| 0-9 | رقم القناة (0 وحده = القناة السابقة) |
| OK | الدليل المصغّر (قائمة القنوات مع الآن/التالي) |
| يسار / Menu | الصوت، الترجمة، الأبعاد، المفضّلة، إعادة التحميل |
| يمين / Info | شريط الآن/التالي |
| رجوع | يغلق اللوحة ثم يخرج من المشغّل |

في القوائم: اضغط مطوّلًا على OK عند قناة لإضافتها للمفضّلة، أو عند مجموعة لقفلها بالرمز.
