# ONVIF Viewer for Android

A lightweight, full-screen camera wall for Android tablets. It shows the RTSP streams of
ONVIF / IP cameras (Dahua, Hikvision, Reolink and most others) in a grid of up to 16 tiles, and it
is built to do that on old, slow hardware: the reference device is an Amazon Fire HD 8 (2020,
Fire OS 7 / Android 9, quad-core MediaTek, 2 GB RAM). It also targets current tablets (Android 13+).

The app plays RTSP streams. It does not do ONVIF discovery, PTZ or recording, and it never plays audio.

## Features

* **Up to 16 live cameras** in grid layouts, with a separate set for each orientation so cells
  stay wider than tall and every layout covers the whole screen: 2x2, 3x2, 3x3, 4x3, 4x4 and
  "1 big + 5 / 7 / 8" in landscape; 1x4, 2x3, 2x4, 2x5, 2x6, 3x5, 2x8 and "1 big + 4 / 6 / 8" in
  portrait. A layout can therefore hold a different number of cameras in each orientation
  ("1 big + 5" in landscape pairs with "1 big + 6" in portrait). The app remembers one layout per
  orientation and switches when the tablet is rotated; cameras that stay in the same cell keep
  playing, only the others connect.
* **Very low CPU use**: hardware decoding only, one drawing surface for the whole wall, capped
  refresh rate per layout, video track only. See [How it works](#how-it-works).
* **Sub stream in the grid, main stream when enlarged**: a tapped camera goes full screen at once
  on its sub stream and switches to the high-resolution main stream as soon as that has a picture
  (about half a second). If the main stream is unavailable or too large for the device's hardware
  decoder, the sub stream stays. Can be turned off in Settings.
* **Graceful beyond the hardware limit**: when a device runs out of hardware decoder instances
  (8 per codec on the Fire HD 8), extra tiles switch to key-frame stills that refresh every 1-2 s.
* **Full screen**, no bars or buttons until asked for. Tap a camera to enlarge it, swipe to change
  page, swipe in from the top or bottom edge for the control bar.
* **Scaling modes**: fit (keep proportions), stretch to fill the cell, zoom to fill (crop).
  Stretch always fills a grid cell, whatever the camera's shape. A camera shown full screen is
  stretched only while that distorts it by less than 40% (4:3 video on a 16:9 screen is 33%);
  beyond that, for example full screen in portrait, it keeps its proportions with black bars.
* **Live stream statistics** in single view: codec, resolution, decoder, decode and display frame
  rate, bit rate, dropped frames, reconnects, uptime.
* **Camera management**: add, edit, delete, reorder, enable/disable; RTSP URLs with Basic or
  Digest authentication; import and export the list as YAML.
* **Self-healing**: automatic reconnect with back-off, stall detection, per-tile status labels.
* H.264 and H.265 (HEVC) over RTSP/TCP. No third-party player or native libraries; the APK is under 3 MB.

## How it works

* **Own RTSP client** (`engine/RtspClient`): OPTIONS / DESCRIBE / SETUP / PLAY on a single TCP
  connection with interleaved RTP, Basic and Digest authentication, keep-alives. Only the video
  track is set up, so audio is never even received. RTP packets are reassembled into H.264 / H.265
  access units (`engine/RtpDepacketizer`).
* **MediaCodec hardware decoding in asynchronous mode** (`engine/VideoDecoder`). Access units go
  straight into the codec's input buffers and the decoder writes frames into a GPU texture. No
  software decoding and no pixel copies in app code.
* **One window surface for all tiles** (`ui/GlCompositorView`). A small OpenGL ES thread draws every
  tile at a capped rate (30 fps for one camera, 20 for up to 4, 15 for up to 9, 12 beyond), so the
  system compositor handles one layer at that rate instead of 8-16 video layers at 60 Hz. Decoders
  only output the frames that will actually be drawn.
* **Decoder budget** (`engine/VideoDecoder.kt`, `DecoderBudget`). The app asks the device how many
  hardware decoder instances it supports and gives tiles beyond that a software decoder fed with
  key frames only (about 1% CPU each).

### Measurements

Fire HD 8 (2020), camera sub streams of 640x480 H.264 at 15-25 fps plus one 640x352 HEVC stream.
Percentages are of one core; the tablet has four (400% in total).

| Tiles on screen | App process | Decoder service | System compositor | Idle |
|---|---|---|---|---|
| 1 | 17-23% | 3-7% | 7-10% | ~340% |
| 4 | 65-80% | 20-24% | 7-10% | ~245% |
| 8 (all hardware) | 74-90% | 32-40% | 3-7% | ~200% |
| 16 (10 hardware + 6 key-frame tiles) | 80-93% | 40-50% | 0-7% | ~140-215% |

A first prototype using a stock media player with one video surface per camera needed more than
twice the CPU at 8 tiles and could not reach 16.

The remaining cost is per decoded frame, so the best further saving is on the camera side: set the
sub streams to 10-15 fps and a low resolution (VGA / D1).

## Requirements

* Android 8.0 (API 26) or newer. Tested on Fire OS 7 (Android 9, 32-bit).
* Cameras reachable over the local network with RTSP enabled (H.264 or H.265).

## Build

You need JDK 17 and the Android SDK. Point the build at the SDK with the `ANDROID_HOME`
environment variable or a `local.properties` file containing `sdk.dir=/path/to/Android/sdk`.

```bash
git clone git@github.com:rpanachi/onvif-viewer-android.git
```

```bash
cd onvif-viewer-android
```

Optionally bundle your own camera list so the app starts with it (the file is ignored by git):

```bash
cp cameras.example.yaml cameras.yaml
```

Build the APK:

```bash
./gradlew :app:assembleRelease
```

The result is `app/build/outputs/apk/release/app-release.apk`. It is one universal APK: there are
no per-device variants and no native code, so the same file installs on every supported device
(Android 8.0 or newer, 32-bit or 64-bit, any screen size). Decoder limits and screen shape are
detected at run time. For convenience the release build is
signed with the local debug keystore so it can be installed right away; configure your own signing
before distributing it. An APK built with a `cameras.yaml` contains those credentials: keep it private.

## Install

Enable Developer options and USB debugging on the tablet (on Fire tablets: Settings, Device
Options, tap the serial number seven times, then enable ADB), connect it over USB and run:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Alternatively copy the APK to the tablet and open it with a file manager (allow installation from
unknown sources when asked).

## Camera list format

```yaml
cameras:
  front_door:
    enabled: true
    name: Front door
    vendor: Dahua                 # optional, informational
    input: rtsp://user:password@192.0.2.10:554/cam/realmonitor?channel=1&subtype=1
    main: rtsp://user:password@192.0.2.10:554/cam/realmonitor?channel=1&subtype=0   # optional
```

`input` is the low-resolution sub stream used in the grid. `main` is optional; when it is missing
the app derives it for common vendors (Dahua `subtype=1`, Hikvision `/Streaming/Channels/102`,
Reolink `_sub`, `/stream2`, `/12`). Special characters in the user name or password must be percent-encoded.

Cameras can be added and edited in the app, or imported from a YAML file in this format
(Settings, Import YAML). Export writes the same format. Credentials are stored in plain text in
the app's private storage and in exported files.

## Using the app

* **Tap** a camera to enlarge it; tap again (or Back) to return to the grid. In single view a bar
  shows the stream statistics (including whether the sub or the main stream is on screen), a
  shortcut to that camera's settings and the scaling mode.
* **Swipe** left / right to page through cameras when there are more than the layout shows.
* **Swipe in from the top or bottom edge** (or long-press) to show the control bar: Settings,
  layout, page, scaling mode. It hides itself after a few seconds. The layout button lists the
  layouts of the orientation the tablet is in and changes only that orientation's choice.
* **Settings**: layout in landscape, layout in portrait, video scaling, camera names on/off, main
  stream in single view, camera list, import / export. An orientation whose layout was never
  chosen follows the other one with a layout of similar size.

A tile label shows the camera name and, when something is wrong, its state: `connecting`,
`error: unreachable`, `error: wrong user or password`, `stills` (key-frame mode) and so on.

## Scripting and kiosk provisioning

The viewer accepts a few intent extras, useful for testing or for setting up a wall-mounted tablet:

```bash
adb shell am start -n com.rpanachi.onvifviewer/.MainActivity --es layout 4x4 --ei page 0 --es scale stretch
```

```bash
adb shell am start -n com.rpanachi.onvifviewer/.MainActivity --es single "FRONT DOOR" --es orientation landscape
```

```bash
adb shell am start -n com.rpanachi.onvifviewer/.MainActivity --es import /sdcard/Android/data/com.rpanachi.onvifviewer/files/cameras.yaml
```

```bash
adb shell am start -n com.rpanachi.onvifviewer/.MainActivity --ez reset true
```

Layout ids are `<columns>x<rows>` (`2x2`, `3x3`, `2x4`, ...) or `1+<n>` for the "1 big" layouts
(`1+5`, `1+7`, `1+8` in landscape; `1+4`, `1+6`, `1+8` in portrait); `layout` sets the layout of the
current orientation. Scale: `fit`, `stretch`, `zoom`. `single` opens one camera
full screen by name. `orientation` (`landscape`, `portrait`, `auto`) holds the screen in one
orientation until the app is closed. `reset` restores the list bundled at build time. The logcat tag `OnvifViewer` prints per-camera
decoder, frame counters and compositor timing every 10 seconds:

```bash
adb logcat -s OnvifViewer
```

## Limitations

* RTSP over TCP only; no UDP or multicast, no RTSPS.
* No audio, recording, PTZ or ONVIF discovery.
* Main streams larger than the device's hardware decoder limit (2048x1088 on the Fire HD 8) are
  not shown; single view stays on the sub stream for those cameras.

## License

Released under the [MIT License](LICENSE). The app depends on AndroidX libraries, which are
licensed under the Apache License 2.0.
