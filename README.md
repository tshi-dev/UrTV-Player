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

Build: `gradle :core:test` (parsers, no Android SDK needed) · `gradle :app:assembleDebug` (needs the Android SDK).
Release signing happens only in CI from secrets; no keys are in this repository.

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
