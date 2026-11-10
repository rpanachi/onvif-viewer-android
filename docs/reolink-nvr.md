# Connecting to a Reolink NVR

Notes on getting the cameras of a Reolink NVR into this viewer. Two routes exist:

| Route | What you need | Works with this app | Status |
|---|---|---|---|
| **Local IP** (same network, or through a VPN) | The NVR's address, RTSP enabled on it, a login | Yes, directly over RTSP | Not tested yet on the NVR these notes were written for |
| **UID** (Reolink's peer-to-peer service) | Only the 16-character UID and a login | No. Needs a bridge that turns it into RTSP | Connection, login and video verified; RTSP output through the bridge not working yet |

Every statement below is marked as **verified** (seen on a real RLN16-410, 16 channels, firmware
build 23103153, on 2026-11-09) or **not verified** (from Reolink's documentation or general
knowledge, still to be confirmed on that NVR).

Keep the UID, user and password in `.env` (ignored by git). Never put them in a tracked file.

## What the viewer needs

The app speaks RTSP over TCP and decodes H.264 and H.265 in hardware. It supports Basic and Digest
login, uses the sub stream in the grid and switches to the main stream when a camera is shown full
screen. Anything that offers an RTSP URL per camera works. A UID is not an address, so the app
cannot use it on its own.

## Route 1: local IP

This is the simple route, and the one to use as soon as the tablets can reach the NVR's address,
either on the same network or over a VPN (Tailscale, WireGuard, a site-to-site tunnel).

### Enable RTSP on the NVR (not verified)

On many Reolink models the RTSP, ONVIF, HTTP and RTMP services are **off by default**. Turn RTSP on
with an admin login:

* On the NVR's own screen: Settings, Network, Advanced, Port Settings.
* In the Reolink app or desktop client: Device Settings, Network, Advanced, Server Settings.

Default ports:

| Service | Port | Used for |
|---|---|---|
| RTSP | 554 | Video for this app |
| ONVIF | 8000 | Discovery, events. Note that it is not port 80 |
| HTTP / HTTPS | 80 / 443 | Web interface and the JSON API |
| RTMP | 1935 | Flash-style streams |
| Basic service ("media port") | 9000 | Reolink's own Baichuan protocol, used by the Reolink apps |

### RTSP URLs (not verified)

```
rtsp://USER:PASSWORD@192.0.2.20:554/h264Preview_01_sub     sub stream of channel 1
rtsp://USER:PASSWORD@192.0.2.20:554/h264Preview_01_main    main stream of channel 1
rtsp://USER:PASSWORD@192.0.2.20:554/h265Preview_01_main    main stream of an H.265 camera
rtsp://USER:PASSWORD@192.0.2.20:554/Preview_01_sub         newer firmware, either codec
```

* The two digits are the NVR channel, counted from `01`. Channel 3 is `..._03_sub`.
* Sub streams are H.264 even when the main stream is H.265.
* Older reports say H.265 main streams did not come through an NVR's RTSP at all, only from the
  camera directly. Check this on current firmware before relying on main streams.
* Reolink advises against special characters in the password for RTSP. If the password has any,
  percent-encode them in the URL (`@` becomes `%40`), and if that still fails use a simpler one.

### Camera list entries for this app

One entry per channel, with the sub stream as `input`:

```yaml
cameras:
  nvr_front:
    enabled: true
    name: FRONT
    vendor: Reolink NVR
    input: rtsp://USER:PASSWORD@192.0.2.20:554/h264Preview_01_sub
```

The app derives the main stream by itself for this pattern (`_sub` becomes `_main`). For an H.265
camera whose main path differs (`h265Preview_01_main`), set `main:` explicitly.

Main streams of 4K or 5 MP cameras are larger than the tablets' hardware decoders accept (2048x1088
on the Fire HD 8, 2560x1440 on the Redmi Pad). The app then stays on the sub stream in full screen
and says so in the statistics line.

### Checklist for the first test

```bash
ffprobe -v error -rtsp_transport tcp -show_entries stream=codec_name,width,height,avg_frame_rate -of csv=p=0 "rtsp://USER:PASSWORD@192.0.2.20:554/h264Preview_01_sub"
```

1. Confirm port 554 answers and which login it accepts (a non-admin user is enough for the Reolink
   apps; whether RTSP accepts it is not verified).
2. Probe sub and main of every channel: codec, resolution, frame rate. Set sub streams to 10-15 fps
   for the lowest tablet load.
3. Check how many simultaneous RTSP sessions the NVR allows. A 16-tile wall opens 16.
4. Add the channels to `cameras.yaml`, rebuild, and load the list on the tablets.

### Other local interfaces (not verified)

* **Baichuan on port 9000**: Reolink's own protocol over TCP. The bridge described below can use it
  with `address = "192.0.2.20:9000"` instead of a UID, without Reolink's servers.
* **JSON API** on the HTTP(S) port: `POST /cgi-bin/api.cgi?cmd=Login` returns a token. Useful
  commands: `GetDevInfo`, `GetChannelstatus` (which channels have a camera), `GetEnc` (stream
  settings), `Snap`. The API and neolink count channels from 0, RTSP paths from 01.
* **ONVIF on port 8000**: offers motion events. An earlier experiment in this app read motion from
  ONVIF on port 80 of other cameras; for Reolink the port is 8000.
* **RTMP / FLV** URLs exist as well, but they carry the password as a URL parameter. Prefer RTSP.

## Route 2: UID (Reolink peer-to-peer)

### How it works

A UID identifies the device to Reolink's servers. Clients never learn an address they could open
with RTSP. The official apps, and the open-source [neolink](https://github.com/QuantumEntangledAndy/neolink),
speak Reolink's proprietary "Baichuan" protocol over UDP:

1. **Lookup.** The client sends the UID to `p2p.reolink.com` (also `p2p1` to `p2p11`), UDP port 9999.
   The reply names a register server and a relay server.
2. **Registration.** The client tells the register server its own address and client id. The reply
   carries a session id and, depending on the network, the device's addresses or only relay servers.
3. **Connection**, tried in this order by neolink: `local` (broadcast on the LAN), `remote` (direct to
   the device's address), `map` (the device connects back to the client), `relay` (all traffic
   through Reolink's relay).
4. **Login and video** inside that connection, with the user and password. Only this step uses the
   credentials; lookup and registration send the UID alone.

Lookup packets are small XML documents, obfuscated with a fixed XOR key and wrapped in a 20-byte
header. Details, as documented in neolink's source (`crates/core/src/bcudp`):

* Header, little-endian 32-bit fields: magic `0x2a87cf3a`, payload length, `1`, transaction id,
  checksum of the obfuscated payload.
* Obfuscation: XOR with the bytes of eight 32-bit keys, each increased by the transaction id.
* Checksum: CRC-32 with initial value 0 and no final inversion.

A lookup that needs no credentials, enough to tell whether an NVR is online and how it is reachable:

```python
#!/usr/bin/env python3
"""Reolink UID lookup. Usage: REOLINK_UID=XXXXXXXXXXXXXXXX python3 lookup.py"""
import os, random, re, socket, struct, time, zlib

KEY = [0x1f2d3c4b, 0x5a6c7f8d, 0x38172e4b, 0x8271635a, 0x863f1a2b, 0xa5c6f7d8, 0x8371e1b4, 0x17f2d3a5]
MAGIC = 0x2a87cf3a

def crypt(tid, data):
    key = b"".join(struct.pack("<I", (k + tid) & 0xffffffff) for k in KEY)
    return bytes(b ^ key[i % 32] for i, b in enumerate(data))

def pack(tid, xml):
    body = crypt(tid, xml.encode())
    crc = (zlib.crc32(body, 0xffffffff) ^ 0xffffffff) & 0xffffffff
    return struct.pack("<IIIII", MAGIC, len(body), 1, tid, crc) + body

def unpack(data):
    magic, size, _, tid, _ = struct.unpack("<IIIII", data[:20])
    return crypt(tid, data[20:20 + size]).decode("utf-8", "replace") if magic == MAGIC else ""

def ask(sock, addr, tid, xml, want):
    for _ in range(3):
        sock.sendto(pack(tid, xml), addr)
        end = time.time() + 1.5
        while time.time() < end:
            try:
                reply = unpack(sock.recvfrom(4096)[0])
            except socket.timeout:
                break
            if re.search(want, reply):
                return reply
    return None

def ip_port(xml, tag):
    m = re.search(r"<%s>\s*<ip>([^<]*)</ip>\s*<port>(\d+)</port>" % tag, xml)
    return (m.group(1), int(m.group(2))) if m else None

uid = os.environ["REOLINK_UID"]
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", 0))
sock.settimeout(1.5)
found = ask(sock, (socket.gethostbyname("p2p.reolink.com"), 9999), 0,
            "<P2P><C2M_Q><uid>%s</uid><p>MAC</p></C2M_Q></P2P>" % uid, "M2C_Q_R")
reg, relay = ip_port(found or "", "reg"), ip_port(found or "", "relay")
print("UID known to Reolink:", bool(reg and relay))
if reg and relay:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    probe.connect(("8.8.8.8", 53))
    me = (probe.getsockname()[0], sock.getsockname()[1])
    answer = ask(sock, reg, random.randint(1, 255),
                 "<P2P><C2R_C><uid>%s</uid><cli><ip>%s</ip><port>%d</port></cli><relay><ip>%s</ip><port>%d</port></relay>"
                 "<cid>%d</cid><debug>false</debug><family>4</family><p>MAC</p><r>3</r></C2R_C></P2P>"
                 % (uid, me[0], me[1], relay[0], relay[1], random.randint(1, 2**31 - 1)), "R2C_C_R|R2C_T") or ""
    print("online (rsp=0):", "<rsp>0</rsp>" in answer)
    print("direct address offered:", bool(ip_port(answer, "dmap") or ip_port(answer, "dev")))
    print("relay offered:", bool(ip_port(answer, "relay")))
```

### What was verified through the UID

* The lookup answered, the register server returned `rsp=0` with a session id: the NVR is online.
* The replies carried relay servers only (`relay`, `relayt`), no device address (`dev`, `dmap`), and
  no packet from the NVR arrived directly. This NVR is reachable **only through Reolink's relay**.
  Its public address stays unknown, so nothing can be learned this way about open ports.
* neolink 0.6.3-rc.3 (official image `quantumentangledandy/neolink:latest`, source commit `6e05e78`)
  connects through the relay in about 4 seconds and logs in with a **non-admin** user.
* Nine parallel sessions, one per channel, all logged in.
* `neolink image` (the NVR's snapshot function) returned full-resolution JPEGs for most channels.
  A few channels returned data that is not a valid JPEG; the cause is unknown. Channels without a
  camera answer `Msg of type 109 returned code 400`.
* `neolink image --use-stream` produced a frame cut from the live H.264 stream. **Live video does
  arrive over the relay.**
* neolink 0.5.9 cannot connect at all (`Discovery failed`). Reolink's servers have moved on from
  the protocol revision it speaks.

### What does not work yet: RTSP out of neolink

In `rtsp` mode neolink serves every camera at `rtsp://HOST:8554/NAME/subStream` and
`/NAME/mainStream` (also `/NAME`, `/NAME/sub`, `/NAME/main`). On this NVR every one of those
streams showed only neolink's placeholder: **MJPEG, 896x512, 25 fps**. If a probe reports that
format, you are looking at the splash screen, not at a camera.

Cause: before it publishes a real stream, neolink asks the device for its stream settings
(Baichuan message 146). This NVR refuses that request for the login used:

```
Task Error: Camera responded with Service Unavaliable: Msg of type 146 returned code 300
```

neolink then waits forever (`src/rtsp/mod.rs`, the task that fills `supported_streams`), and the
same request would also abort the pipeline setup (`StreamConfig::new` in `src/rtsp/factory.rs`).
With debug logging the tell-tale line is `Building Unknown Pipeline` and the absence of
`Building H264 Pipeline`.

Two ways forward, **neither verified**:

* **Use an admin login.** The refusal is probably a permission of the non-admin user. If an admin
  login is accepted for message 146, stock neolink should work unchanged.
* **Patch neolink** so a refused request is not fatal: assume `mainStream` and `subStream` exist,
  start with a default frame rate (it must not be zero, timestamps are derived from it) and let
  the values be learned from the video. A first draft of this patch did not compile and was never
  run.

The same login is also refused the motion subscription (`Error in getting MD listen_on_motion`), so
motion events over this route would need the admin login too.

### Running neolink

Configuration, one `[[cameras]]` block per channel. neolink counts channels from 0:

```toml
bind = "0.0.0.0"
bind_port = 8554

[[cameras]]
name = "nvr1"
username = "USER"
password = "PASSWORD"
uid = "XXXXXXXXXXXXXXXX"
channel_id = 0
discovery = "relay"
stream = "both"        # or "subStream" / "mainStream" to open fewer streams
```

```bash
docker run -d --name reolink-bridge -p 8554:8554 -v "$PWD/neolink.toml:/etc/neolink.toml:ro" quantumentangledandy/neolink
```

```bash
docker run --rm -v "$PWD/neolink.toml:/etc/neolink.toml:ro" -v "$PWD/out:/out" quantumentangledandy/neolink /usr/local/bin/neolink image --config /etc/neolink.toml --file-path /out/nvr1.jpg nvr1
```

Notes:

* The container only needs outbound UDP. No ports have to be opened towards the internet.
* `RUST_LOG=info,neolink=debug` as an environment variable shows why a stream does not start.
* The warning `PHONE_REGISTRATION_ERROR` about push notifications is harmless.
* The config file holds the password in clear text. Keep it out of git and out of Docker build
  contexts.
* The native macOS binary needs the GStreamer framework installed system-wide; Docker avoids that.
* In this app, bridge URLs end in `/subStream`, which the automatic main-stream rule does not
  recognise. Set `main:` to the `/mainStream` URL for each camera.

### Limits of the UID route

* Video travels through Reolink's relay servers: more delay and less bandwidth than a direct
  connection, and it depends on their service being available.
* The bridge has to run on a machine that is always on and reachable by the tablets.
* Every channel is a separate login session on the NVR.
* The protocol is proprietary and reverse-engineered. Building it into the Android app would mean
  reimplementing the UDP transport, the login and the media framing.

## Choosing

Use the local IP route whenever the NVR's address can be reached. If the NVR is on another network,
a VPN to that network gives the same result without exposing anything to the internet. Forwarding
port 554 on the remote router also works but puts the NVR's login on the open internet. The UID
route is the fallback when nothing can be changed at the NVR's site.

## Sources

* [How to Live View Reolink Cameras via VLC Media Player](https://support.reolink.com/articles/360007010473-How-to-Live-View-Reolink-Cameras-via-VLC-Media-Player/) (RTSP URL format, password advice)
* [How to Configure Reolink Ports Settings](https://support.reolink.com/articles/900000621783-How-to-Configure-Reolink-Ports-Settings/) (default ports, where to enable them)
* [Reolink community: RTSP URLs](https://community.reolink.com/topic/1182/rtsp-urls) and [H.265 stream through an NVR](https://community.reolink.com/topic/2136/rlk8-810b4-a-get-access-to-the-h265-stream-for-ipcam-viewer) (channel numbering, H.265 reports)
* [neolink](https://github.com/QuantumEntangledAndy/neolink) (AGPL-3.0): source of the protocol details and of the bridge
