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
