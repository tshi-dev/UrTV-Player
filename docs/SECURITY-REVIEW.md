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
| 15 | Extra attack surface | Permissions: INTERNET, ACCESS_NETWORK_STATE, the normal app-defined `sa.urtv.permission.READ_LANG` (UrTV's language only, no prompt) and, by owner decision, REQUEST_INSTALL_PACKAGES (only for the verified in-app update, see S-U below); two exported components (launcher activity, and `FindMatchActivity`, see F1); the exported `ProfileInstallReceiver` is removed; no WebView, no content providers of our own, no deep links, no broadcast receivers |
| 16 | Supply chain | All dependency versions are pinned (no ranges); Dependabot watches Gradle and Actions weekly; Actions run with `contents: read`; no signing key or secret in the repo, release signing only from CI secrets; the in-app updater is verified and never silent (see S-U) |
| 17 | R8 | Enabled for debug and release; keep rules only for serialization |

## Residual risks and notes
- Cleartext http (14) and providers that put credentials in URLs are outside our control; the app never sends them anywhere but the provider.
- Without a usable Android Keystore (very old or emulated devices) files fall back to plain text inside the app sandbox.
- A CVE scan could not run in the build sandbox (the vulnerability database host is blocked). Dependabot alerts cover this once GitHub
  reads the dependency graph; please enable "Dependabot security updates" on the repository.
- Hostile video streams are decoded by Media3/ExoPlayer and the device's codecs; keep Media3 updated (Dependabot).

## Addendum: home media-server client (Jellyfin, Emby, Plex)

Code: `core/.../server/` (API clients, tested with canned and hostile JSON) and `app/.../server/` (UI, vault, player).

| # | Threat | Fix |
|---|---|---|
| S1 | Server tokens read from app files | `servers.json` is written through `Secrets` (AES key in the Android Keystore). Tokens are never in preferences |
| S2 | Tokens in URLs, logs or crash reports | Tokens travel in headers only (`Authorization: MediaBrowser … Token=` / `X-Plex-Token`); the player and image loader add them per request and only for the server's own origin. `ServerConfig`, `Req` and `ServerException` redact on `toString`/message; `Redact` strips `api_key`, `X-Plex-Token` etc.; server-supplied transcode URLs are stripped of `api_key` [`RedactTest`, `fallsBackToServerTranscodeAndStripsKeyFromUrl`] |
| S3 | Discovery answered by a public or spoofed host | UDP replies from non-private senders, or advertising non-private addresses, are dropped [`DiscoveryTest`]. No `CHANGE_WIFI_MULTICAST_STATE`: a broadcast send plus unicast replies needs no lock |
| S4 | Cleartext http to the internet | Server base URLs must be https, or http to a private host (RFC 1918, loopback, link-local, CGNAT, ULA, bare/`.local` names), re-checked on load and on every redirect; https→http downgrades refused [`NetSafetyTest`]. The Android network security config still permits cleartext globally for IPTV, because it cannot express private ranges: the rule for server traffic is enforced in code (**residual**: Coil/Media3 requests rely on URLs that were validated at configuration time) |
| S5 | Silently trusting a bad certificate | A certificate the system rejects raises an error with its SHA-256 fingerprint; the user may pin that one certificate for that server. Nothing else is trusted. Pin is stored encrypted |
| S6 | Hostile server JSON | Tolerant decoding, no exceptions escape, string/list caps, ids validated, 32 MB response cap, deep-nesting input handled [`HostileJsonTest`] |
| S7 | Token sent to another host on redirect | The API client (`JavaTransport`) follows redirects itself and re-checks every hop; it never downgrades to http or leaves the LAN for http. Media and image requests use the shared OkHttp client: the token header is added only to requests for the server's own origin (`ResolvingDataSource`). **Residual:** OkHttp follows redirects itself and drops only the `Authorization` header on a cross-host hop (Jellyfin); the Emby/Plex token headers (`X-Emby-Token`, `X-Plex-Token`) would travel with such a redirect. Only a compromised or misconfigured server can cause it |
| S8 | Third-party subtitle keys | None in the app. Subtitle search/download uses the server's own plugin or agent |
| S9 | Plex login | Official plex.tv PIN flow; the account token is never typed on the TV |

Not tested against live servers: all behaviour is verified against canned responses based on the documented APIs.

## Addendum: FIND_MATCH (exported activity for the UrTV home app)

Code: `app/.../FindMatchActivity.kt`, `app/.../ui/FindMatch.kt`, `core/.../MatchFinder.kt`. Action `sa.urtv.player.action.FIND_MATCH`.

| # | Threat | Fix |
|---|---|---|
| F1 | Any app launching the exported activity or feeding it input | Accepted only from package `sa.urtv.home` or `sa.urtv.screensaver` whose single signing certificate equals the pinned UrTV release certificate (`Integrity.URTV_CERT_SHA256`, bc71eeac…ab2b; an empty pin would accept nobody). The same pin gates the language provider: the answer is trusted only if `sa.urtv.home.lang` belongs to `sa.urtv.home` with that signer. The calling package exists only for `startActivityForResult`, so a plain `startActivity` has none. Wrong action, no verifiable caller or another signer: the activity closes at once with `RESULT_CANCELED` and reads nothing from the intent [`FindMatchActivityTest`] |
| F2 | Hostile extras (huge strings, wrong types, many channel names, odd kickoff) | Read with defensive getters; every string is stripped of control characters and capped at 80 chars, at most 12 channel names, kickoff outside ±7 days replaced by now [`MatchFinderTest`, `FindMatchActivityTest`] |
| F3 | Caller steering playback to an attacker URL | Extras are only compared with the user's own channel names and guide text; no URL from the caller is read, opened or played. Only channels already in the user's playlist can start |
| F4 | Information leak back to the caller | The result is only `RESULT_OK` (something played) or `RESULT_CANCELED`; no channel, playlist or credential is returned |
| F5 | Remembered pick | Stored per competition as the usual hashed channel key (`playlistId|hash`), never a URL |

Residual: a debug-signed UrTV is rejected by design.

## Addendum: updates through UrTV, diagnostics, list tester

| # | Threat | Fix |
|---|---|---|
| U1 | An app posing as UrTV answers the update question or receives the install call | The provider `sa.urtv.home.player` is trusted only if it belongs to package `sa.urtv.home` signed by exactly UrTV's pinned release certificate; the install intent is explicit (package `sa.urtv.home`) and sent with `startActivityForResult`. Anything else: «التحديث من تلفزيونك» and nothing more |
| U2 | The Player downloading or installing code itself | It never does. UrTV fetches the file, checks sha256 and certificate and hands it to Android's installer; the Player has no install permission and no network path for APKs. Only the official build asks |
| U3 | Hostile provider row | `version_code` must be newer, `version_name` is matched against a strict pattern, notes are stripped of control characters and capped at 120 chars; any exception or null answer is treated as «updates come from your TV» |
| U4 | Update question while watching, or too often | Once per 24 h, 5 s after a cold start, off the main thread, skipped while anything plays [`UpdateTest`] |
| U5 | Newer data opened by an older app | `Schema.check`: data stamped by a newer schema is not read or rewritten; the app shows a notice [`MigrationTest`] |
| D1 | Diagnostics leaking links | The «why does it stall» line and the report use measurements and fixed words only; class names are matched against an identifier pattern |
| D2 | List tester hammering a provider or leaking links | At most 3 requests in flight, staggered starts, first 2 KB only, short timeouts, 30 channels; the screen shows percentages and times only; nothing is stored or logged |

## Addendum: in-app self-update (when UrTV is not installed)

Owner decision: the Player must be fully usable without UrTV, so it can update itself. This **replaces the earlier rule "no self-updater / no install permission"**; the permission list is now INTERNET, ACCESS_NETWORK_STATE, `READ_LANG`, `REQUEST_INSTALL_PACKAGES` (pinned by `ManifestSurfaceTest`). Code: `core/.../SelfUpdate.kt` (rules), `app/.../data/SelfUpdater.kt` (download + installer), `ui/SelfUpdateScreen.kt`.

Flow: with UrTV installed and answering, UrTV is the authority and the Player never contacts GitHub for updates. Otherwise, at most once a day, five seconds after a cold start, never while something plays, an official build reads `https://github.com/tshi-dev/UrTV-Player/releases/latest/download/urtv-player.version` (one line: `<versionCode> <versionName>`; GitHub's "latest" never means a pre-release or draft, and the redirect needs no API call, JSON or rate limit). A newer code and name show the quiet «update available» line/row; only when the person presses OK does it download `urtv-player.apk` and `urtv-player.apk.sha256` from the same place into app-private cache, verify, and hand the file to Android's PackageInstaller (Android's own screen asks the person; «install unknown apps» is allowed once). The same stable names are what UrTV and the direct-download link use.

| # | Threat | Defence (test) |
|---|---|---|
| S-U1 | Downgrade | The announced code must exceed the installed one and the name must be newer [`SelfUpdateTest.versionAssetNewer…`]; after download the APK's versionCode must exceed the installed one and equal the announced one [`swappedFile…`]. Android also refuses downgrades |
| S-U2 | Swapped / tampered file, or "latest" moving mid-way | sha256 of the downloaded bytes must equal the published `.sha256`; package name must be ours; versionCode must equal the announced one; exactly one signer whose SHA-256 equals our own pinned `OFFICIAL_CERT_SHA256` (a build without a pin never accepts anything) [`SelfUpdateTest`]. Android then verifies the signature again against the installed app |
| S-U3 | MITM / redirect to another host | https only, hosts pinned to `github.com`, `api.github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`, `github-releases.githubusercontent.com`, no userinfo, port 443 only; redirects followed by hand, each hop re-checked, at most 5 [`SelfUpdateTest.onlyPinnedGithubHosts…`, `SelfUpdaterTest.redirects…`, `productionPolicy…`]. System CAs only (network security config). The sha256 file travels the same channel, so a compromised GitHub account or repository could publish both a file and its hash: the pinned-certificate check (S-U2) is what stops a file not signed by our key |
| S-U4 | Hostile version asset | One line of at most 256 bytes matched against a strict pattern; HTML error pages, extra lines, huge numbers, control characters, URLs are all rejected [`SelfUpdateTest.hostileVersionAssets…`]. The cached offer is re-validated on every load, so edited preferences cannot point the downloader elsewhere [`UpdateTest`] |
| S-U5 | Disk full / interrupted download / oversize | Streams into `<file>.part`, counts bytes against a 25 MB cap (declared and actual), renames only a complete file; any failure deletes partial and final files; leftovers are cleaned at start [`SelfUpdaterTest.oversize…`, `interrupted…`] |
| S-U6 | Install-permission abuse | Install is started only by the person pressing OK on the update row; no exported component accepts an install command (`ManifestSurfaceTest`: two exported activities, no services/providers/receivers); the file lives in app-private cache and is read only by our own PackageInstaller session; the status receiver is registered dynamically, not exported; debug and unofficial builds never self-update [`UpdateTest.urtvIsTheAuthority…`] |
| S-U7 | Silent or forced update | Never: no background install, no blocking dialog, no check during playback, once a day; failures are quiet and the installed version keeps working |

Not verifiable without a device: the PackageInstaller hand-over and Android's confirmation screen, the «install unknown apps» settings screen on a given TV, and `getPackageArchiveInfo` signer extraction on real APKs (the rules that consume it are tested).

## Addendum: public-repository audit

See `docs/PUBLIC-AUDIT.md` for the findings, what was fixed and what needs the owner.
