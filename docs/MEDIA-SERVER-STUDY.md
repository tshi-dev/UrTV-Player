# Study: home media-server clients, and what UrTV will do

Written from the apps' public descriptions, their documented server APIs and common user reviews. No code was copied.
Findroid, Wholphin and Plezy are open source (GPL/MPL); they are used for ideas only. Must = ships in the first release. Nice = ships if it stays polished.

## What Infuse does best (the bar)
1. **Library feel.** Artwork-first grids, a backdrop that changes with focus, one calm hero, almost no chrome. Metadata always looks finished (poster, logo, year, rating, runtime, tech badges).
2. **Direct play first.** It plays the original file; the server is only a file/metadata source. Transcoding is a last resort.
3. **Subtitles.** Every format (SRT, ASS/SSA, PGS, VobSub, embedded or sidecar), per-user style (font, size, colour, background, edge), sync offset, forced-subs logic, preferred-language auto-pick, search/download in the app.
4. **State.** Resume, watched marks, Next up, and Continue watching all agree with the server and with other devices.
5. **Speed.** Lists render from a small cache first, then refresh.

## Clients compared
| Capability | Jellyfin (Android TV) | Plex | Emby | Kodi | Findroid | Wholphin | Plezy | Infuse | UrTV |
|---|---|---|---|---|---|---|---|---|---|
| Server discovery on LAN | UDP 7359 | GDM UDP 32414 | UDP 7359 | UPnP | no (manual) | manual | plex.tv | Bonjour | **Must** (private ranges only) |
| Login: user/password | yes | via plex.tv | yes | n/a | yes | yes | n/a | yes | **Must** |
| Quick Connect / PIN link | Quick Connect | PIN link | no | no | Quick Connect | yes | PIN | no | **Must** (both) |
| Multiple servers/users | yes | yes | yes | yes | yes | partly | yes | yes | **Must** |
| Continue / Next up / Latest rows | yes | yes | yes | add-ons | yes | yes | yes | yes | **Must** |
| Grids + sort + filters + genres | yes | yes | yes | yes | partly | yes | yes | yes | **Must** |
| Collections / box sets | yes | yes | yes | yes | no | yes | yes | yes | **Must** |
| Backdrop hero on focus | partly | partly | partly | skins | no | yes | yes | yes | **Must** |
| Search | yes | yes | yes | yes | yes | yes | yes | yes | **Must** |
| Cast, ratings, tech badges | yes | yes | yes | yes | partly | yes | yes | yes | **Must** (cast as text, badges as small labels) |
| Trailers (server-provided) | yes | extras | yes | yes | no | no | yes | no | **Nice** (if the server lists a local trailer) |
| Direct play (Media3) | yes | yes | yes | own | yes | yes | yes | own | **Must** |
| Transcode fallback | yes | yes | yes | n/a | yes | yes | yes | n/a | **Must** (HLS from server when codec unsupported) |
| Resume + watched sync | yes | yes | yes | local/DB | yes | yes | yes | yes | **Must** (progress reports every 10 s, stop report on exit) |
| Next episode prompt | yes | yes | yes | yes | no | yes | yes | yes | **Must** (existing design 07) |
| Skip intro / credits | plugin | native markers | plugin/markers | add-on | no | yes | yes | no | **Must** when the server provides markers |
| Audio language preference | yes | yes | yes | yes | yes | yes | yes | yes | **Must** |
| Embedded subtitles | yes | yes | yes | yes | yes | yes | yes | yes | **Must** |
| External SRT/ASS | yes | yes | yes | yes | yes | yes | yes | yes | **Must** |
| Image subs PGS / VobSub | direct, or burned in by transcode | burned in | burned in | direct | direct | direct | direct | direct | **Must** (direct where the TV decodes it, else ask the server to burn in) |
| Subtitle styling (font/size/colour/bg/edge/position) | basic | basic | basic | full | basic | basic | partly | full | **Must** |
| Sync offset ± | no | no | no | yes | no | no | no | yes | **Must** |
| Forced-subs logic | yes | yes | yes | yes | no | no | no | yes | **Must** |
| Online subtitle search | plugin | built-in agents | plugin | add-ons | no | no | no | built-in | **Must**, but only through the server (never a 3rd-party key in the app) |
| Arabic RTL shaping in subs | OS | OS | OS | font | OS | OS | OS | yes | **Must** (Android text engine + bidi-safe renderer; tested) |
| Offline downloads | mobile | mobile | mobile | n/a | yes | no | no | no | **Out** (TV, no storage permission) |
| Live TV / DVR from the server | yes | yes | yes | PVR | no | no | no | no | **Out** (UrTV has its own IPTV side) |
| Music / photos / books | yes | yes | yes | yes | no | no | no | partly | **Out** |
| Cloud relay / remote streaming | no | yes | yes | n/a | no | no | yes | no | **Out** (LAN or user-given https URL only) |

## What makes Infuse's subtitles the best, and our answer
- It **never fights the user's preferences**: audio language, then subtitle language, then "forced only when the audio language differs". We implement that as a pure, tested function (`SubtitleChoice`): preferred order ar, en; forced track picked only when the audio is not in the preferred language; full track when subtitles are on by preference.
- **Styling is global and instant**: size, colour, background, edge/shadow, position, offset. Media3's `SubtitleView` already renders text cues with `CaptionStyleCompat`; we feed it from our own settings so it does not depend on the TV's accessibility menu.
- **ASS/SSA**: Media3 parses ASS, including positioning, but we keep default styling per our settings unless the author's override is explicitly enabled (Nice).
- **Image subtitles (PGS/VobSub/DVB)**: Media3 renders PGS and DVB as bitmaps on most devices; VobSub is not decodable by Media3, so for VobSub and for any codec the TV refuses, we request a server-side **burn-in** (transcode with `SubtitleMethod=Encode`).
- **Arabic**: Android's text stack shapes and reorders; we avoid hand-rolled glyph code. The test set includes mixed Arabic/Latin/numbers cues, cue-order checks and a leading-RLM insertion for neutral punctuation at line ends.
- **Sync offset**: shift applied to the player's subtitle timeline (a wrapper on text cues), ±50 ms steps, remembered per item.
- **Online search**: Jellyfin `GET /Items/{id}/RemoteSearch/Subtitles/{lang}` and `POST /Items/{id}/RemoteSearch/Subtitles/{subtitleId}` (needs a subtitle plugin on the server, e.g. Open Subtitles). Plex: the server's own subtitle agent through `/library/metadata/{id}/subtitles`. The app never holds a subtitle-site key.

## What we build, in order
1. Core (pure Kotlin, tested, in package `com.urtv.player.server`): Jellyfin/Emby client, Plex client, discovery parsing + private-range check, subtitle choice, playback decision (direct play vs transcode), URL builders that keep tokens out of logs, hostile-JSON tests.
2. App (`com.urtv.player.server` in `:app`): token vault on the Keystore (`Secrets`), LAN discovery, source-login screens, library UI, detail pages, player with sync, subtitles UI, settings.
3. Settings: «وضع خادم المنزل فقط / Home server only» hides IPTV (nav, rows, add-playlist); the start screen respects it.

## Security rules for this feature
- Discovery is limited to RFC1918, link-local and CGNAT (Tailscale) addresses; any reply from a public address is dropped.
- https is preferred (probe `https://` first for a typed host); http is allowed only for private LAN hosts. A certificate error is shown with the server's SHA-256 fingerprint; the user may pin that exact certificate for that server (stored encrypted) but it is never trusted silently.
- Tokens live only in the Keystore-encrypted file; they are sent in headers (`Authorization: MediaBrowser … Token=` / `X-Plex-Token`), not in URLs, except where the player or image loader cannot send headers: those URLs are built just-in-time, kept in memory, and the redacting helper `Redact` strips `api_key`, `X-Plex-Token` and `ApiKey` from anything that could be logged.
- All server JSON is untrusted: tolerant decode, caps on list sizes and string lengths, no exception escapes the client.
- Permissions: `INTERNET` + `ACCESS_NETWORK_STATE` only. Jellyfin/Emby discovery is a UDP unicast-reply broadcast to `255.255.255.255:7359`, which works without a multicast lock; Plex discovery is not needed (plex.tv lists the user's servers). So `CHANGE_WIFI_MULTICAST_STATE` is **not** requested.
