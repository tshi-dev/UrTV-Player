# Playback performance pass

What is tuned (all in `player/PlayerController.kt`, rules in `player/PlaybackTuning.kt`, unit-tested):

| Area | What | Notes |
|---|---|---|
| Fast start | Live starts after ~1 s of media (Normal), asks for 3 s after a stall; HLS uses chunkless preparation; max live speed 1.04x to stay near the edge | on-demand uses a 2.5 s start buffer |
| Bandwidth | Initial estimate seeded from the previous session (clamped 0.5 to 50 Mbit/s, default 3) | saved on release |
| TV-aware | Video capped to the physical display (no 4K on a 1080p TV), viewport = display | `PlaybackTuning.maxVideoSize` |
| Smooth | Frame-rate matching only when seamless; decoder fallback hw to sw; audio passthrough follows the TV/AVR capabilities (Media3 default); tunneled playback is an opt-in switch | Settings > Playback |
| Resilient | Stall watchdog + back-off reconnect; 3 rebuffers in 60 s caps the video bitrate at 70 % of the current one; retry as HLS for extension-less URLs | `RebufferTracker` |
| Network | One shared OkHttp client (HTTP/2, pooled connections, no gzip on media, tuned timeouts) | `player/Net.kt` |
| Zap | One ExoPlayer and one surface for the whole session; optional prefetch of the neighbouring channels' HLS manifests (off by default: some providers limit connections) | Settings > Playback |
| Low RAM | `isLowRamDevice`: buffer bytes capped at 8 MB, max buffer 15 s, preview off by default; preview stops when the list is left | |
| Measure | Playback stats overlay (resolution, fps, codec, video and network kbit/s, buffer, dropped frames, time to first frame and its average) | Settings > Playback > Playback stats |

Needs a real device (cannot be measured in the sandbox, no decoder or KVM): time to first frame and zap time on real streams,
frame-rate matching, tunneling and passthrough on specific TVs, dropped-frame counts, jank while scrolling (use the stats overlay
and a Macrobenchmark run).
