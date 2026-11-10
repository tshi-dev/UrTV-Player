# Third-party components

| Component | Licence | Where |
|---|---|---|
| AndroidX, Jetpack Compose, tv-material, ProfileInstaller | Apache 2.0 | app |
| AndroidX Media3 (ExoPlayer, HLS, DASH, RTSP, OkHttp data source) | Apache 2.0 | app |
| OkHttp, Okio | Apache 2.0 | app |
| Coil | Apache 2.0 | app |
| ZXing core | Apache 2.0 | app (QR codes) |
| kotlinx.coroutines, kotlinx.serialization | Apache 2.0 | app, core |
| IBM Plex Sans Arabic | SIL Open Font License 1.1 ([text](../licenses/OFL-IBM-Plex-Sans-Arabic.txt)) | `app/src/main/res/font/`, design mockups |
| Thmanyah typeface | Private licence: only inside the built product | official release builds only, never in this repository |

Test-only: Robolectric, JUnit, kxml2. Versions are pinned in `app/build.gradle.kts` and `core/build.gradle.kts`; Dependabot watches them.
