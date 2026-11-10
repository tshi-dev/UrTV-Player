# Releasing and protecting UrTV Player

Goal: the player cannot be used as an official build, or updated, without us.

## What protects it
1. **Signature.** Official builds are signed with OUR key (RSA 4096, v2 + v3 schemes). Android refuses to install an update over
   the official app unless it is signed with the same key. The key exists only as GitHub secrets and on the owner's PC.
2. **Tamper notice.** At start the app compares the SHA-256 of the certificate it is installed with against the pinned official
   one (`BuildConfig.OFFICIAL_CERT_SHA256`, set by CI from the keystore). A mismatch, or a build without a pin, shows
   «نسخة غير رسمية / Unofficial build» and turns off the LAN pairing page and backup restore. It keeps working as a player, never
   crashes and never contacts anyone. (A determined person can still rebuild with their own pin; the real guard is that Android
   will not let such a build replace or update ours.)
3. **Updates are verified, never silent.** With UrTV installed, UrTV downloads the release asset, checks its `.sha256` and the signing
   certificate, then installs. Without UrTV (owner decision), the official build updates itself from the public «player-<version>»
   release (`urtv-player.apk` + `.sha256`): only https to GitHub's own hosts, sha256 = published, package = ours, a higher
   versionCode and a single signer equal to our pinned certificate, then Android's PackageInstaller asks the person. Debug and
   unofficial builds never do this.
4. **Debug builds are separate.** Debug builds use the id `com.urtv.player.debug` and are labelled «تجريبي / Debug», so they can
   never overwrite the official app.
5. The repository stays private; R8 shrinking/obfuscation is on for release; nothing secret is logged.

## One-time setup (owner, on his PC)
Create the key once and keep a copy offline. **If it is lost, no update can ever be installed over existing installs.**

    keytool -genkeypair -v -keystore urtv-player.jks -storetype PKCS12 -alias urtv-player \
            -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=UrTV Player, O=UrTV"

Use one strong password (PKCS12 uses it for both the store and the key). Then turn the file into base64:

* Linux: `base64 -w0 urtv-player.jks > urtv-player.jks.b64`
* macOS: `base64 -i urtv-player.jks | tr -d '\n' > urtv-player.jks.b64`
* Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("urtv-player.jks")) | Set-Content -NoNewline urtv-player.jks.b64`

Add these **repository secrets** (Settings, Secrets and variables, Actions) in `tshi-dev/UrTV-Player`:

| Secret | Value |
|---|---|
| `PLAYER_KEYSTORE_B64` | the content of `urtv-player.jks.b64` |
| `PLAYER_KEYSTORE_PASSWORD` | the keystore password |
| `PLAYER_KEY_ALIAS` | `urtv-player` |
| `PLAYER_KEY_PASSWORD` | the same password (PKCS12) |
| `FONTS_TOKEN` | optional: read access to `tshi-dev/urtv-fonts` (Thmanyah) |

## Publishing a release
Tag a commit: `git tag v0.2.0 && git push origin v0.2.0`. The `release` job builds, signs (v2 + v3), verifies, enforces the 4 MB budget
and attaches `urtv-player-0.2.0.apk` and `urtv-player-0.2.0.apk.sha256` to a GitHub release whose notes carry the signing
certificate SHA-256. The version code is the CI run number (always increasing). The repository is private, so downloading the
asset needs a token; if UrTV must fetch it anonymously, publish the asset to a public place (decision for the owner).

Verify by hand: `sha256sum -c urtv-player-0.2.0.apk.sha256` and `apksigner verify --print-certs urtv-player-0.2.0.apk`.
