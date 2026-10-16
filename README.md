# TAKPilot2 — DJI MSDK v5

A TAK flight interface for DJI Matrice 4 series aircraft, built on DJI Mobile SDK v5
(5.18.0). It runs on the aircraft's remote controller and connects the flight to a TAK
network: the aircraft, the pilot and the camera's look-point appear on the team's map, the
team's markers appear on the pilot's video, and the live video is published to a media
server and advertised to TAK clients.

Developed and flown on a Matrice 4TD with a DJI RC Plus 2.

## What it does

- **TAK connection** with certificate enrolment, channel selection and a second,
  optional "Elevated" account that alone receives the video link. A password-gated
  **Basic / Elevated** application mode hides the second account and the broadcast
  controls on controllers that do not need them.
- **Position reports**: the aircraft (with gimbal and sensor field-of-view), the pilot,
  and a sensor point of interest where the camera's crosshair meets the terrain.
- **Live video** from the controller screen to a media server over RTSP or SRT, with the
  stream address advertised in the aircraft's CoT so TAK clients can open it. An
  **Emergency Broadcast** control pushes the link to every channel for a bounded time.
- **AR overlay**: TAK markers drawn on the video at their geographic position, using
  DTED terrain for ground contacts; crosshair marker drops; an accuracy cue.
- **Terrain and airspace**: DTED tile import (levels 0–5) for the overlay and the
  look-point; FAA UAS Facility Map ceilings on the mini-map.
- **Flight tools**: a flight record (CSV/GPX), a warning banner in plain words, RTH
  with cancel, obstacle-sensing edge display, vision-assist camera views, camera and
  gimbal controls on the controller's buttons, an accessory panel for the AL1 light and
  AS1 speaker (including recorded voice messages and push-to-talk).
- **Field Guide** in the app, written for the pilot; `tools/generate_field_guide_md.py`
  prints it as Markdown from the source.

## What you need

- A DJI developer account and an app key (see *Build*).
- A TAK server that supports certificate enrolment, with accounts for the pilots.
- A media server that accepts RTSP or SRT publishing and serves RTSP reads (for example
  MediaMTX), reachable from the controller and from the TAK clients.
- DTED tiles for your area, if you want terrain-aware marker placement.

## Build

Toolchain: JDK 17, Android Gradle Plugin 8.7.0, Kotlin 2.1.0, Gradle 8.12 (wrapper
included). minSdk 24, target/compile SDK 35, `arm64-v8a` only.

1. **Register the application with DJI.** In the DJI developer console, create an app
   entry whose package name is the `applicationId` in `app/build.gradle`
   (`org.takpilot2.djiv5`). You may change the id to your own first; the key is bound to
   whatever id you register.
2. **Put the key where the build reads it.** Copy `app/dji-key.properties.example` to
   `app/dji-key.properties` and paste the key. The file is git-ignored. Without it the
   app builds but SDK registration fails.
3. **Install the secret guard** (recommended):

   ```
   cp tools/pre-commit .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
   ```

   It refuses a commit that stages a key, a keystore or a credential-looking value.
4. **Build and test:**

   ```
   ./gradlew :app:testReleaseUnitTest
   ./gradlew :app:assembleDebug
   ```

5. **Release signing** (optional): create `app/keystore.properties` with `storeFile`,
   `storePassword`, `keyAlias` and `keyPassword`. It is git-ignored. `assembleRelease`
   then signs the APK and copies the R8 mapping file to `releases/` (also git-ignored),
   named by version, so a crash trace from a fielded build can be read back.
6. **Install on the controller:** `tools/install.sh` bumps `versionCode`, builds the
   release, installs it over ADB and confirms the version from the device.

The CI workflow in `.github/workflows/build.yml` runs the unit tests, a release assembly
and vital lint on every push.

## Configuration in the app

Everything else is set on the controller, in the app's Pre-Flight and TAK Server screens:
the TAK server and enrolment, the media server(s), the video publish and advertisement
protocols, the stream path, the aircraft callsign, the application mode, and the
configuration locks. No server address, account or callsign is built into the source.

## Licence and notices

The TAKPilot2 code is MIT licensed (`LICENSE`). `NOTICE.md` lists what the MIT licence
does not cover: the DJI SDK binaries (DJI's EULA), DJI's sample code this project started
from (`LICENSE-DJI-SAMPLE`), and the vendored SRT and RTSP clients (Apache 2.0), with the
local changes to them.

This software commands an aircraft. Read the safety paragraph in `NOTICE.md` before you
fly a build of it.
