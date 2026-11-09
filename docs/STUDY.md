# Study: what IPTV players do, and what UrTV will do

Written from the apps' public descriptions and common user reviews (TiviMate, OTT Navigator, IPTV Smarters, Lumen, OwnTV,
Perfect Player, Sparkle, Kodi PVR). No code was copied; OwnTV and Lumen are GPL and are used only as a reference for ideas.
Must = ships in the release. Nice = ships if it fits the 10 MB budget and stays polished. Out = deliberately not built.

| Feature | TiviMate | OTT Nav | Smarters | Lumen | OwnTV | UrTV |
|---|---|---|---|---|---|---|
| M3U / M3U8 URL | yes | yes | yes | yes | yes | **Must** (done) |
| Xtream Codes login | yes | yes | yes | yes | no | **Must** (done) |
| Stalker/MAC portal | no | yes | yes | no | no | Out (needs a device identity; most portals are unlicensed) |
| XMLTV EPG, gz | yes | yes | yes | yes | yes | **Must** (done) |
| EPG time offset per playlist | yes | yes | no | no | no | **Must** |
| Programme details, reminders | yes | yes | no | no | no | **Must** details / Nice reminders (no background permission, so in-app only) |
| Catch-up / timeshift (Xtream archive) | yes | yes | yes | no | no | **Must** where the provider marks `tv_archive` |
| VOD movies + series, posters, plot, seasons | partly | yes | yes | no | no | **Must** (Xtream VOD/series; M3U VOD groups as plain list) |
| Multi-playlist | yes | yes | yes | yes | yes | **Must** (done) |
| Favourites | yes | yes | yes | yes | yes | **Must** (done) |
| Custom groups, hide / reorder groups | yes | yes | no | no | no | **Must** hide+reorder, Nice custom groups |
| Search (channels, then VOD, then EPG titles) | yes | yes | yes | yes | no | **Must** |
| Recents / continue watching | yes | yes | yes | no | no | **Must** (done; VOD resume position added) |
| Number zap, up/down zap, last-channel | yes | yes | yes | yes | yes | **Must** (done) |
| Mini-guide over video | yes | yes | no | no | no | **Must** (done) |
| Channel preview while browsing | yes | yes | no | no | no | Nice (one decoder, muted preview pane) |
| Multiview (2-4 streams) | yes | yes | yes | no | no | Out (decoder limits on cheap boxes) |
| Recording | yes | yes | no | no | no | Out (needs storage permission; against the permission rule) |
| Parental PIN | yes | yes | yes | no | no | **Must** (done), per group |
| External player option | yes | yes | yes | no | no | Out (needs package-visibility; the built-in player is the product) |
| Audio / subtitle track pick | yes | yes | yes | yes | yes | **Must** (done) |
| Aspect modes | yes | yes | yes | yes | yes | **Must** (done) |
| Stall watchdog + auto reconnect | yes | yes | no | no | partly | **Must** (done) |
| Auto-refresh playlist + EPG | yes | yes | yes | no | yes | **Must** (done) |
| Backup / restore settings | yes | yes | no | no | no | Nice (SAF file picker, no storage permission) |
| Themes / text size | yes | yes | no | no | no | **Must** text size + 2 themes (dark, OLED black) |
| Add from phone | no | no | no | partial | no | **Must** (done: QR + code, LAN only) |
| Arabic UI / RTL | no | partial | partial | no | no | **Must**, first-class (done) |
| Self-update | no | no | no | yes | no | Out (hard rule) |
| Broad permissions | no | no | no | no | yes | Out (hard rule) |

## Where the others fall short (our edge)
- TiviMate: not Arabic-first, closed source, subscription for the good features. OTT Navigator: dense, busy UI.
- Smarters: ad-like branding, weak EPG. Lumen: ships a debug keystore and self-updates. OwnTV: all-files and overlay permissions, 60 MB.
- None is small, open, and Arabic-first with a calm UI at once.

## Reuse (do not hand-roll)
| Need | Use | Why / size |
|---|---|---|
| Playback HLS/DASH/TS/RTSP | Media3 ExoPlayer (+ `media3-exoplayer-rtsp`) | already in; RTSP is small |
| Poster / logo loading, disk cache | Coil 3 + OkHttp | replaces my hand-written loader; ~1 MB |
| Large catalogues (VOD 10k+ rows), search | Room (KSP) | indexed queries, no OOM; ~1 MB |
| Settings | DataStore (Preferences) | replaces SharedPreferences; small |
| TV components | androidx.tv Material 3 (already) + Lazy lists | |
| XMLTV / M3U | own parsers (core, tested) | OwnTV is GPL: ideas only (streaming, per-channel id matching) |
| QR | zxing-core (already) | |

## Plan (ordered, each step ships green)
1. Foundation: Room for channels/VOD/EPG (streaming parse, no whole-file Strings), DataStore, Coil. Migrate current stores.
2. Playlist management: EPG offset, hide/reorder groups, custom group, per-playlist refresh, rename, backup/restore (SAF).
3. VOD: Xtream movies/series (categories, poster grid, detail page, seasons/episodes, resume position), search across all.
4. Catch-up/timeshift via Xtream archive URLs from the guide grid; programme details + in-app reminders.
5. Player polish: channel preview pane, last-channel, sleep timer, buffer/decoder fallback, RTSP.
6. Design pass: type scale, motion, text-size setting, OLED theme, empty/error states, full English + Arabic review, new screenshots (ar + en).
7. Quality: baseline profile, R8 check on a real device image, size budget < 10 MB, CI release job (signing from secrets only).
