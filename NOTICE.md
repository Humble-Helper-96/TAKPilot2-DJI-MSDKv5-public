# Notices

`LICENSE` is plain MIT and covers the TAKPilot2 code in this repository. It is kept
free of any other text so that automated licence detection reads it correctly. The
terms below sit alongside it; they do not modify it.

## What the MIT licence does NOT cover

**The DJI Mobile SDK V5 binaries.** This project builds against
`com.dji:dji-sdk-v5-aircraft` and `com.dji:dji-sdk-v5-aircraft-provided`, resolved
from Maven. They are not bundled in this repository and they are licensed separately
under DJI's End User License Agreement (https://developer.dji.com/policies/eula/).

**DJI's Sample Code.** This project is built on DJI's Mobile SDK for Android V5
sample application (https://github.com/dji-sdk/Mobile-SDK-Android-V5), which DJI
releases under the MIT License. That original notice is preserved, as its terms
require, in `LICENSE-DJI-SAMPLE`.

**The vendored SRT client.** `app/src/main/java/com/pedro/srt/` and
`app/src/main/java/com/pedro/common/` are taken from RootEncoder (Copyright pedroSG94),
Apache License 2.0, tag `2.4.7` — https://github.com/pedroSG94/RootEncoder. `com/pedro/srt/`
is the whole `srt` module; `com/pedro/common/` holds the seven files that module uses. The
rest of `common` and the AV1 parser are not included.

It is vendored as source rather than taken as a dependency so that the changes below could be
made and so that the same code compiles on an older Kotlin toolchain. Changes, each marked
`TAKPILOT2 CHANGE` in the file:

- `SrtClient.latencyMs` is settable and goes into BOTH handshake delay fields. The library sent
  a fixed 120 ms as the receiver delay and hardcoded the sender delay to zero, so a publisher's
  value governed nothing and the server used its own default.
- `Constants.MTU` 1500 -> 1316. It sizes an outgoing packet and the library does not subtract
  the IP and UDP headers, so 1500 put 1360 bytes on the wire — larger than an ordinary path
  carries. `READ_BUFFER` keeps the receive path at 1500.
- `setAuthorization` threw a bare `TODO()`; it throws with a message saying where the
  credentials go. SRT has no user or password field.
- Seven enum files called `Enum.entries` and now call `values()`; `BitrateChecker` was Java and
  is Kotlin. Both keep the code compilable on an older Kotlin toolchain and are harmless here.

**The vendored RTSP client.** `app/src/main/java/com/pedro/rtsp/` is taken from
rtmp-rtsp-stream-client-java (Copyright pedroSG94), tag `2.2.6`, licensed under the Apache
License 2.0. Its notice is preserved, as that licence requires, in
`app/src/main/java/com/pedro/rtsp/NOTICE.txt`. Changes, marked `TAKPILOT2 CHANGE` in the
file: the `Authorization` header is masked in the request log (`CommandsManager.redactAuth`),
and the `User-Agent` string names this application.

## This software commands an aircraft

It is provided "as is" and without warranty of any kind, as `LICENSE` states. Nobody but
you has tested the build you make from this source. The remote pilot is responsible for the
safety of the flight and for compliance with the applicable aviation regulations; test every
build on the ground, against your own TAK server and media server, before flying it.
