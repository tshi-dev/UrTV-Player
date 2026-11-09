# Security review (threat model and fixes)

Scope: the Android TV app, its LAN "add from phone" page, and CI. Reviewed against hostile playlists/guides/servers,
other devices on the same Wi-Fi, a person with brief physical access to the TV, and supply-chain risk.
Status: every finding below is fixed in the tree unless marked **residual**. Tests named in brackets run in CI.

## Assets
Xtream/M3U credentials and tokens (they are in URLs too), the parental PIN, the user's watch history, device integrity.

## Findings and fixes

| # | Threat | Fix |
|---|---|---|
| 1 | Hostile playlist points at `file://`, `content://`, `intent:` or `jar:` URLs to make the app read local data or fire intents | M3U parser only accepts `http, https, rtmp, rtsp, udp, rtp`; `Http` refuses every non-http(s) scheme including on redirects [`m3uRejectsLocalSchemesAndHugeLines`, `onlyHttpAndHttpsAreFetched`] |
| 2 | Gzip/zip bomb or endless response exhausts memory or disk | Byte cap applied after decompression: 64 MB playlists/JSON, 300 MB guides; line length cap 8 KB; 200 k channels, 1.5 M programmes; field length caps [`gzipBombIsCappedAfterDecompression`] |
| 3 | Slow or hanging server | connect 20 s / read 40 s timeouts, ≤ 5 redirects (http<->https allowed, other schemes refused) |
| 4 | XML external entity / DTD attack in XMLTV | DOCTYPE processing disabled on the pull parser; test proves an external entity is not expanded [`xmltvDoesNotExpandExternalEntities`] |
| 5 | Malformed or wrong-shaped JSON/XML crashes the app | Tolerant parsers never throw; garbage yields empty lists [`xtreamJsonOfWrongShapeIsHarmless`, truncation test] |
| 6 | Credentials readable from app files (root, forensic image) | `playlists.json`, channel/VOD caches, remembered guide URLs and the watch list are encrypted with an AES-256 key held in the Android Keystore (`Secrets`). Cloud backup and device transfer are disabled (`allowBackup=false` plus `dataExtractionRules`) |
| 7 | Credentials leak through preferences | Favourites and recents store `playlistId|SHA-256(url)`, never the URL |
| 8 | Credentials in logs or screenshots | The app has no logging; screenshots use placeholders (`example.com`); CI prints no secrets (none exist) |
| 9 | Another device on the Wi-Fi abuses the phone page | Page exists only while the Add screen is open; bound to the LAN address only (not 0.0.0.0) on a random port; private/link-local clients only; random 6-digit one-time code compared in constant time; wrong code adds a 300 ms delay and 5 wrong codes close the page; closes after one submit, on leaving the screen, or after 10 min [`PhoneServerTest`] |
| 10 | CSRF / DNS rebinding against the page | The code is required in every POST (acts as the CSRF token); `Host` must equal `ip:port` and `Origin`, if sent, must be the page's own origin [`oversizedBodyAndForeignOriginAreRejected`, `bindsToTheGivenAddress…`] |
| 11 | Oversized or odd requests, error leaks | Header cap 8 KB, body cap 8 KB (413), only GET/POST (405), 4 s read timeout, uniform short error bodies without stack traces or paths [`errorPagesLeakNothing`] |
| 12 | XSS on the phone page | No script anywhere; `default-src 'none'` CSP; every echoed value is HTML-escaped [`phonePageEscapesCodeAndShipsNoScript`] |
| 13 | PIN brute force or offline cracking | PBKDF2-HMAC-SHA256 (60 000 iterations) with a random 16-byte salt; after 5 wrong tries the pad locks 30 s, doubling to 15 min; PIN never stored |
| 14 | TLS interception with user-installed CAs | `network_security_config` trusts the system store only. Cleartext http stays allowed because most IPTV servers need it (**residual**, inherent to the use case) |
| 15 | Extra attack surface | Permissions: INTERNET and ACCESS_NETWORK_STATE only; one exported component (launcher activity); the exported `ProfileInstallReceiver` is removed; no WebView, no content providers of our own, no deep links, no broadcast receivers |
| 16 | Supply chain | All dependency versions are pinned (no ranges); Dependabot watches Gradle and Actions weekly; Actions run with `contents: read`; no signing key or secret in the repo, release signing only from CI secrets; no self-updater |
| 17 | R8 | Enabled for debug and release; keep rules only for serialization |

## Residual risks and notes
- Cleartext http (14) and providers that put credentials in URLs are outside our control; the app never sends them anywhere but the provider.
- Without a usable Android Keystore (very old or emulated devices) files fall back to plain text inside the app sandbox.
- A CVE scan could not run in the build sandbox (the vulnerability database host is blocked). Dependabot alerts cover this once GitHub
  reads the dependency graph; please enable "Dependabot security updates" on the repository.
- Hostile video streams are decoded by Media3/ExoPlayer and the device's codecs; keep Media3 updated (Dependabot).
