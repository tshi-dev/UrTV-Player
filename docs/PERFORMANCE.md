# Big lists, budgets and what only a TV can show

Everything here is measured on the JVM with generated data (`ScaleTest`, `PlaybackTuningTest`); no real playlist is used.
The test limits are loose on purpose (CI is slower than a laptop, far faster than a low-end TV): they catch an accidental
O(n^2), not a 20 % change.

## Measured (JVM, generated data)
| Case | Result | Test limit |
|---|---|---|
| Parse an M3U of 20 000 channels (3.7 MB) | 0.29 to 0.35 s | 4 s |
| Heap held by the 20 000 channels | about 7 MB | 80 MB |
| Group list of 20 000 channels (120 groups) | 4 to 6 ms | 300 ms |
| Search "channel 19" over 20 000 | 12 to 23 ms | 300 ms |
| Filter to one group | 2 ms | 300 ms |
| Channel numbers for one screen of 40 rows | 13 to 14 ms | 200 ms |
| Parse an XMLTV guide of 100 000 programmes (21 MB, 2 000 channels) | 0.66 s | 15 s |
| Build the now/next index (window -3 h to +36 h) | 26 ms | 1 s |
| now/next for 2 000 channels | 4 ms | 200 ms |

## How it stays light
- The playlist and the guide are parsed as streams, the guide keeps only channels the playlist uses and only a 39 h window.
- Lists are lazy (only visible rows compose); logos load through Coil with downsampling.
- Cache first: the last channel list is on screen before the network answers.

## Low-RAM TVs (`isLowRamDevice`)
Buffer bytes capped at 8 MB and max buffer at 15 s, live preview off by default and stopped when the list is left, one decoder for
the whole session. Pinned in `PlaybackTuningTest`.

## Pinned playback budgets (`PlaybackTuningTest`)
Live start buffer at most 1.5 s (Normal) and 1 s (Low); a frozen picture is noticed within 8 s and a stall within 15 s; a channel is
declared failed after about 3 minutes (8 attempts, back-off up to 12 s, 15 s stall limit each); zaps are debounced 250 ms so holding a key never thrashes the decoder.

## Only a TV can show (not measured here)
Frame time and jank while scrolling a 20 000-channel list, real memory pressure on a 1 GB TV, time to first frame and zap time on
real streams, the decoder limits of a given model, Wi-Fi behaviour. The playback stats overlay (Settings > Playback) and a
Macrobenchmark run on the device are the tools for these. First TV test (TCL): cold start 0.69 to 0.77 s, 39 MB idle.

## First real-TV findings (TCL, v0.9.2 to v0.9.3) and what v0.9.6 changed
| Finding | Change | Re-measure on the TV |
|---|---|---|
| 29 % janky frames scrolling 360 rows (p50 38 / p90 69 / p99 109 ms) | Preview starts only after the focus rests 500 ms and stops decoding the moment it moves; rows are flat (no scale or shadow); the preview pane is the only thing that recomposes on a focus move; channel numbers come from a map, not a scan; guide lookups are remembered | scroll the list alone (preview off) and with it on; budget at most 5 % janky |
| Old picture stays when changing channel, tiny spinner | Black-out at once, banner with number, name, logo and «جارٍ الفتح…»; reconnect text sits in the banner | change channel on a slow one |
| Zap 1.5 to 3.6 s, first picture 3.7 to 5.9 s | Stats show dns, connect, manifest, media bytes and decode per channel; DNS answers cached 10 min; zap debounce 250 to 180 ms | read the new line in the stats overlay |
| 360-channel playlist took 35 to 90 s | The list is shown as soon as it is parsed (cache is written after), "N channels" progress, fetch+parse and cache-write times in the stats; the Keystore write is no longer on the critical path | stats line "list N ch: fetch+parse X ms, cache Y ms" tells network from our own time |
| PSS 127 MB then 86 | Stats show current PSS | watch it during and right after a load |

## v0.9.7 (second TV round: list still 12.6 % janky, zap 3.1 s up)
- List rows: logos decoded at their fixed on-screen size without subcomposition and prefetched a screen ahead off the UI thread; the preview's video surface exists only while a preview is actually playing (scrolling composes no surface); DNS of the focused channel is resolved immediately, so the 500 ms rest hides it.
- Zap: DNS of the next two channels each way is resolved when a channel starts; the two nearest neighbours' manifests are fetched ahead and served once from memory (20 s, 256 KB, off on low-RAM TVs and metered links, on by default).
- The stats overlay now carries a UI frame meter per screen (janky share above 25 ms, p50/p90/p99) and a real frames-per-second reading (rendered frame count over time).

## v0.9.8: finding the list jank on the TV
The gfxinfo numbers (p50 36 ms: every frame takes two vsyncs) say the cost is in every frame, not in spikes. What changed in the app, and how to bisect the rest.

What changed: the list scrolls by jumping to the focused row (no spring animation that lands between frames); a **baseline profile** now ships inside the release APK (`assets/dexopt/baseline.prof`, checked by CI), so a fresh install does not scroll and draw through the interpreter; the frame meter now uses `FrameMetrics` (the same TOTAL_DURATION `dumpsys gfxinfo` reports, janky = longer than one display interval) and starts empty on every screen.

Bisect from a computer (nothing to rebuild; the list is re-read when the Home screen is entered or a tab is changed, so press a tab after setting it):

    adb shell setprop debug.urtv.bisect "nopreview"                 # no preview pane next to the list
    adb shell setprop debug.urtv.bisect "nobackdrop"                # plain background colour, no gradient/poster backdrop
    adb shell setprop debug.urtv.bisect "nologos"                   # no channel logo images
    adb shell setprop debug.urtv.bisect "nofocus"                   # no focus animation (scale, colour fade)
    adb shell setprop debug.urtv.bisect "nomarquee"                 # the focused name does not scroll
    adb shell setprop debug.urtv.bisect "springscroll"              # the old spring scrolling, for comparison
    adb shell setprop debug.urtv.bisect "nopreview,nologos,nofocus,nomarquee,nobackdrop"   # everything off at once
    adb shell setprop debug.urtv.bisect ""                          # back to normal

Turn the stats on (Settings > Playback > Playback stats, or the star/stats panel in the player) to see the live meter, labelled with the screen and the active switches. For the system's own numbers:

    adb shell dumpsys gfxinfo com.urtv.player reset        # then scroll the list with a fixed key speed
    adb shell dumpsys gfxinfo com.urtv.player              # "Janky frames" and the 50th/90th/95th/99th percentile

Is it code that has not been compiled yet? Compile the app fully ahead of time and measure again; if the numbers drop, it is the interpreter/JIT:

    adb shell cmd package compile -m speed -f com.urtv.player          # everything compiled
    adb shell cmd package compile -m speed-profile -f com.urtv.player  # only what the baseline profile lists
    adb shell cmd package compile --reset com.urtv.player              # back to the default

If "nopreview" fixes it the video surface/preview is compositing the whole screen; if "nologos" does, image decoding/drawing is; if only "nofocus" does, it is the animations; if only the AOT compile does, it is warm-up and the baseline profile will fix it after the first background dexopt.

## v0.9.9: install state, the late frame per key, the backdrop
**What the TCL bisect showed (v0.9.8):** the TV's own list draws at p50 11 ms / 1 % janky. Ours back to back: 17.8 % as installed, 0.7 % after `compile -m speed`, 1.2 % after `compile -m speed-profile` (so the shipped baseline profile is enough once it is compiled). With keys 0.1 s apart: 14 to 15 % in every state, about one late frame per key press.

**Does an install get compiled code? Honestly: not at once.** An app cannot ask Android to compile it (dex2oat is the system's). The release APK carries the baseline profile (`assets/dexopt/baseline.prof`, checked by CI); on the first run the AndroidX ProfileInstaller (already included; its start-up initializer runs after launch) writes it to the system, and Android compiles it during the next idle maintenance window (device idle, charging/screen off). A PackageInstaller or sideloaded install gets no ahead-of-time compile of the profile at install time (only a store with a cloud profile does). Until that maintenance run the app is interpreted/JIT'd, which is why a fresh install scrolls at 15 to 18 % janky. What the app does about it: the stats frame meter prints the state (`aot: compiled with profile` / `profile waiting for the system's idle compile` / `no profile compiled`), so every measurement says which state it was taken in; measurements for the UI budget are to be taken in the profiled state (`adb shell cmd package compile -m speed-profile -f com.urtv.player`, or after a night of standby). We also lowered the per-frame work so the un-compiled state suffers less (below).

**Changes aimed at the per-key frame and the per-frame cost**
- The screen background is the *window's* background drawable (one static gradient the system draws), not a full-screen gradient Compose records again every frame. `C.bgBrush` is now transparent and the screens no longer paint a full-screen layer (`nobackdrop` took p50 from 23 to 18 ms).
- The preview/info pane follows the focus about 90 ms behind it, so one key press = a light frame (focus + scroll) and the heavier pane update lands in the next frame, not in the same one.
- Row logos come from an in-memory bitmap cache at their fixed size (one plain `Image` when cached, no image-request machinery while composing a row); prefetched a screen ahead.
- DNS pre-resolve on focus is a map lookup plus, only for a new host, a task on one background thread (no thread per key).
- The frame meter's "janky" now follows gfxinfo: a frame is janky when it missed its own deadline (FrameMetrics DEADLINE, API 31+; before that more than 1.5 display intervals), and the line prints the frame count.

**Trace one key press** (find the late frame):

    adb shell setprop debug.urtv.bisect keytrace      # then press a tab so it is re-read
    adb logcat -c; adb logcat -s UrTVKeyTrace          # press DOWN a few times at 0.1 s
    # per key: "key DPAD_DOWN dispatchLag=..." then every frame in the next 400 ms:
    #   +Nms frame: unknown= input= anim+compose= layout= draw= sync= cmd= swap= total= late=
    adb shell setprop debug.urtv.bisect ""

Reading it: `anim+compose` is the Choreographer animation callback, which is where Compose recomposes; `layout` is measure/layout; `draw` is recording the draw commands on the UI thread; `sync`/`cmd`/`swap` are the render thread (GPU side); `unknown` is time lost before the frame started (a busy main thread). The stage that is large in the late frame is the one to fix.

## v0.9.10: one late frame per key

- A channel row is now one self-drawing node: the highlight, texts, logo, progress bar and heart are painted in a single draw block, and focus is read only at draw time. A focus change records only the two rows that change.
- Coming back from the player restores the remembered row once; going between tabs no longer jumps the focus into the content (the Home rows no longer take focus on their own).
- The OK panel remembers the row it was opened from, so RIGHT/LEFT back from the actions returns to that row.
- To re-measure on the TV: `adb shell setprop debug.urtv.bisect keytrace`, press keys about 400 ms apart, `adb logcat -s UrTVKeyTrace`. Targets: draw stage at most 2 ms, total at most 12 ms on the focus-change frame.

### v0.9.11: tab bar ends, and the one late frame

- Pressing towards the end of the tab bar from «الرئيسية» (and from the server button at the other end) no longer jumps into the channel list: the beam search had a wide list row as a candidate. Both ends now cancel that direction.
- The late frame per key (7.7–10.7 % janky at spaced keys while draw on the key frame is 2–4 ms) is not in the draw stage. Candidates left: the preview/info pane update that lands about 90 ms after the key, and composing one new row at the list edge. No safe change was made without a trace; next step is a Perfetto/`gfxinfo` capture of one spaced key to see which frame is late.

### v0.9.12: preview pane

- The tester's trace (preview on 27 % janky, off 3 %) put the late frames in the preview pane: two big frames per key, the video surface being created (composition and attach) and destroyed on every focus change. The surface is now created once when the first preview starts and stays while previews are on; an opaque cover hides it until the new channel's own first frame, so a stale frame never shows.
- The pane already follows focus 90 ms behind it and a preview starts only after the focus rested 500 ms; a held key starts nothing.
- Not changed: the player's own start work still runs on the main thread (ExoPlayer is used from the application looper). To re-measure: preview on and off, 10 DOWN 1 s apart.
- Back from a tab with the focus on the tab bar goes to «الرئيسية» (and the focus follows to that tab); only Back there leaves. A test covers both presses.
- Package names: the player is `com.urtv.player`; `sa.urtv.home.player` is only the content authority of UrTV's update provider.

### v0.9.13: preview, second round

TV result of v0.9.12: preview on 28.6 % janky vs 6.5 % off; frames over 30 ms were start delays (57–70 ms: the UI thread busy before the frame) and heavy draws when the pane changed.

- ExoPlayer already runs its playback work (renderers, loading, decoding) on its own thread, so `setPlaybackLooper` would not move what is left: building the media source, the player listener and surface calls, which must stay on the application looper. Nothing safe to move today; not changed.
- The pane no longer changes size per key: name, programme title, time, progress bar, description and the "next" list are fixed-height blocks, so a key changes only text inside them.
- The live preview is OFF by default on devices under 2600 MB RAM (and on low-RAM devices). It stays one switch in Settings; an existing explicit choice is kept.
- To re-measure on the TV: preview on vs off, 10 DOWN 1 s apart; tell which frames are still over 30 ms.

### Big provider lists (50 000 entries, measured on a development machine; a TV is about 5-10 times slower)

| Step | Cold | Warm | Where |
|---|---|---|---|
| M3U parse | 0.73 s | | background thread |
| Xtream JSON parse | 0.50 s | | background thread |
| Groups | 112 ms | 12 ms | main thread, once per list |
| Visible list with a SHA-256 key per channel | 612 ms | 44 ms | main thread (this was the worst) |
| Search over 50 000 names | 209 ms | 31 ms | main thread per keystroke (this was the second worst) |

Fixed: the per-channel key is computed only when something is hidden; the by-key index is built only when the «recent» group needs it; typing searches on a background thread after a 140 ms pause and the list keeps the previous result meanwhile; the movie and series lists are read from the cache at start and downloaded only when Movies or Series is opened (once per run); the big Xtream arrays are parsed from the stream one entry at a time instead of as one string plus one tree. Logos load only for visible rows (unchanged).

### Sound without a picture

A stream can carry a video track (for example HEVC 2160p or 10-bit) that the TV has no decoder for. The player drops it silently and plays the sound only. The player now says so in a line («هذا التلفزيون ما يقدر يشغّل صورة هذه القناة (4K HEVC)…») and the problem report carries `picture no_picture_<size>_<codec>_decoders<N>`. Decoder fallback (hardware first, then software) was already on.

### v0.9.16: the Channels tab reads one prepared index

When a list is loaded, a background thread builds a `ChannelIndex`: the groups, the channels of each group, the channel numbers, the favourite key of every channel and the prepared (normalised) names for search. The screens only read it, so opening the Channels tab, switching a group and typing a letter no longer do per-channel work on the main thread. On a 50 000-entry list (development machine): index build 0.6 s off the main thread (100 000 entries: 0.64 s), a search over all names 25 ms warm, a group switch is a map lookup. `BigListBudgetTest` pins budgets about 10 times looser than that, so it only fails if per-channel work is put back on those paths.

`adb logcat -s UrTVPerf` prints one line per step: `cold_start_first_frame`, `tab_open_<Tab>` (to the second frame after the switch), `list_build`, `search` and `first_frame` (time to the first picture of a channel). Numbers and counts only.

Playback: after 6 s of sound with no rendered frame the player rebuilds itself with the software decoders first (once per channel); if there is still no picture after 10 s the «can't show the picture» line appears. Quality switches that would force the decoder to be rebuilt are not allowed (strict boxes wedge on them).

Xtream: a pasted `get.php?username=..&password=..` link is detected (only those query keys, one login) and the provider's API is asked first; if it answers, the playlist becomes an Xtream account (the original link is kept, favourites and recents follow their channels by stream id) and «أفلام» / «مسلسلات» appear. Anything else stays an M3U; there, entries whose URL has /movie/ or /series/ (or a video file with a movie or series group) are split out of the live list into those tabs, with episodes grouped by show name when the title says «Name S01 E02».

### v0.9.16 beta5: why the app could exit by itself on the home screen

The program guide (XMLTV) of a big provider was parsed in full into memory (up to 1.5 million programmes, each with title and description) a few seconds after start, whatever screen the person was on, with a regular expression rebuilt twice per programme. On a 2 GB TV that is a CPU spike that makes everything heavy and then an out-of-memory kill. Now: the guide is read only for the window the screens show (2 h back, 14 h ahead), at most 12 programmes per channel, descriptions cut to 400 characters, at background thread priority, started 3 s after the list is on screen; if memory still runs out the guide is skipped and the app keeps running.
Also fixed: the movie and series lists could vanish after loading (the cache-only open at start cancelled a fetch in progress or replaced lists already showing, and a cancelled fetch was remembered as done); the commentary picker lists the whole channel list (it stopped at the first 80 entries).

### v0.9.16 beta6: live stutter

Causes found in the code, all from the zapping-first tuning: buffers of 4 s minimum and 15 s maximum (too thin for a provider with jittery delivery; now 8 s and 30 s, picture after 1 s, 48 MB cap), a playback speed of up to 1.04 to catch the live edge (it eats the buffer; now exactly 1.0), and a jump to the live edge when more than 25 s behind (the jump empties the buffer and the stutter starts again; now only beyond 60 s). A film stream stalls for up to 40 s before a reconnect (a seek on a big file can take a while), and asks for the file as it is (no compression), which range requests need.
