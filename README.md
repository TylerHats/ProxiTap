<div align="center">
  <img src="assets/ProxiTapIcon.png" width="200" height="200" alt="ProxiTap Icon"/>
  <h1>ProxiTap</h1>
</div>

ProxiTap is a decentralized peer-to-peer communication and media-sharing app for Android. Designed specifically for off-grid or high-congestion environments (such as group scootering/cycling, hiking, travel, or crowded festivals), ProxiTap runs completely locally over direct device-to-device Wi-Fi. It requires no cellular service, access points, or internet connectivity to keep you and your team in sync.

---

## 🚀 Features

* **Decentralized Peer-to-Peer Networking**: Connect directly using Android **Wi-Fi Aware (NAN)** for zero-configuration device-to-device links, or **Local Hotspot Mode** for maximum device compatibility.
* **Resilient Handshake & Auto-Recovery**: Automatically retries Wi-Fi Aware discovery, NDP data-path negotiation (`RESET_NDP`), WebSocket signaling, and WebRTC ICE handshakes—with live status toasts during join and mid-call recovery if you briefly go out of range.
* **Persistent WebRTC Voice**: Ultra-low-latency bidirectional voice calls with configurable Acoustic Echo Cancellation (AEC), Noise Suppression (NS), and Discontinuous Transmission (DTX).
* **Ultra-Low-Latency Bi-Directional Media Sharing**: Watch videos or listen to music together on two phones with imperceptible lip-sync delay (~30–45ms glass-to-glass) using 5ms unfragmented 48 kHz 16-bit Stereo PCM UDP frames and click-free linear crossfade playout.
* **Smart Headphone & Earbud Routing**: Automatically detects Bluetooth (Classic SCO/A2DP & LE Audio), USB-C, and 3.5mm wired earbuds/headphones with mid-call hot-plug support, routing both playback and microphone capture and allowing one-tap cycling between **Earbuds**, **Speaker**, and **Earpiece**.
* **Bluetooth / Earbud Push-To-Talk (PTT)**: Leave your phone pocketed. Intercepts headset/earbud media buttons (`MediaSession` play/pause/hook) to toggle your microphone mute state without requiring special accessibility permissions.
* **Lobby Customization**: Secure lobbies with entry PINs, configure target audio bitrates or sample rates, and share access instantly via QR code or deep link.
* **Dynamic Bitrate Scaling**: Automatically adjusts Opus voice encoding down to **2 kbps** in extreme conditions to preserve communication, and up to **256 kbps** in clear conditions.
* **Connection Health Indicators**: Color-coded signal bars showing live latency, packet loss, and active bitrate.
* **Integrated Web Player**: Natively load and stream media inside the app with built-in desktop UA spoofing and web controls.

---

## 📖 User Guide

### 1. Hosting a Lobby
1. Open the app and configure your display name in Settings if desired.
2. Select your desired network mode (**Wi-Fi Aware** is default and preserves mobile internet connectivity; **Local Hotspot** is available as a fallback).
3. Select the lobby style:
   * **Talk (1-on-1 Voice)**: Optimized for low-latency 1-on-1 WebRTC voice calls.
   * **Group Voice**: Multi-peer mesh voice call where everyone can speak.
   * **Media Broadcast**: The host shares their device audio or browser media with up to 10 listening peers.
   * **Bi-Directional Media**: 1-on-1 ultra-low-latency stereo system audio sharing between two devices.
4. Set an optional entry **PIN** and tap **Host Lobby**.
5. Share the generated **QR Code** or allow nearby peers to join via **Search Area**.

### 2. Joining a Lobby
* **Via Search Area (Wi-Fi Aware)**: Tap **Search Area** to discover nearby active ProxiTap lobbies automatically.
* **Via QR Code**: Tap **Scan QR Code** and scan the host's QR code.
* **Via Deep Link**: Tap a shared `https://pt.htsth.app/join?...` link to launch directly into the lobby.

### 3. Audio Routing & Earbud Push-To-Talk (PTT)
* When wired, USB, or Bluetooth earbuds/headphones are connected, ProxiTap automatically routes call audio and microphone capture to your earbuds.
* Tap the bottom-left **Audio Route** button (`Earbuds` / `Speaker` / `Earpiece`) at any time during a call to switch outputs.
* Enable **Bluetooth Button Push-To-Talk** in Settings to toggle microphone mute using the play/pause button on your earbuds or headset.

### 4. Broadcasting Media
* In a **Media** lobby, grant Android system audio capture permission when prompted.
* Tap the **🌐 Web Player** button to open the built-in browser, or switch to any media/video app on your phone to stream its audio in real time.
* Use the **Desktop Mode Toggle** in the Web Player to load full desktop-compatible sites.

---

## ⚙️ Technical Architecture & Details

### 1. Networking Infrastructure
* **Wi-Fi Aware (NAN)**: Leverages Android's Neighbor Awareness Networking APIs with automated attach/publish/subscribe retry watchdogs and responder channel reset recovery.
* **LocalOnlyHotspot**: Initiates a local soft-AP hotspot on the host and connects clients via `WifiNetworkSpecifier` with automatic retry handling.
* **Signaling**: Embedded Ktor CIO WebSocket server on the host (`port 8080`) with replay buffering and progressive fast-backoff client reconnection (`450ms`–`2000ms`).

### 2. Audio Streaming Protocols
* **1-on-1 Voice (`WebRtcClient` + `UdpProxy`)**: Uses WebRTC Opus audio tracks routed through a local loopback UDP proxy (`127.0.0.1`) tunneled over the active NAN/Hotspot transport, with early STUN packet queueing and automatic ICE renegotiation (`REQUEST_OFFER`).
* **1-on-1 Bi-Directional Media (`DirectUdpAudioStreamer` + `MediaStreamer`)**: Streams uncompressed **48 kHz 16-bit Stereo PCM** in **5ms (960-byte)** unfragmented UDP packets on `port 8085` with DSCP `EF` (`0xB8`) WMM Voice hardware queue priority and 300ms scoped IPv6 keepalive/punch frames.
* **Click-Free Low-Latency Playout**: Tracks the hardware `AudioTrack` playout head (`playbackHeadPosition`) with a 25ms pre-roll and applies a linear sample crossfade whenever buffered audio exceeds 60ms, maintaining a steady **30–45ms** playout window without clicks, pops, or underruns.

### 3. Connection Health Diagnostics & Congestion Control
* **Ping Polling**: Host and peers exchange periodic `PING` and `PONG` frames every 2 seconds to measure signaling RTT alongside WebRTC RTP and UDP sequence-number packet loss tracking.
* **EMA Smoothing**: Latency and loss metrics are smoothed using an Exponential Moving Average:
  $$\text{SmoothedValue} = (\alpha \times \text{CurrentValue}) + ((1 - \alpha) \times \text{PreviousValue})$$
  *(where $\alpha = 0.35$)*
* **DTX Safeguard**: Detects silence (`hasTraffic = false`) when Discontinuous Transmission (DTX) is active and suspends bitrate adjustments to prevent artificial fluctuations.
* **Dynamic Bitrate Scaling Rules**:
  * **Extreme Strain** (RTT > 200ms or Loss > 8%): Drops Opus encoding bitrate to **2 kbps** to preserve the voice link.
  * **Poor Link** (RTT > 120ms or Loss > 4%): Decreases bitrate index by 2 steps.
  * **Fair Link** (RTT > 70ms or Loss > 1.5%): Decreases bitrate index by 1 step.
  * **Slow-Start Upscaling**: Requires 3 consecutive excellent intervals (6 seconds of RTT ≤ 40ms and Loss ≤ 0.5%) to step quality back up.

---

## 🛠 Setup & Installation

1. Download the latest APK from the [Releases](https://github.com/TylerHats/ProxiTap/releases) page.
2. Install the APK on your Android device (requires Android 14 / API 34+, targets Android 16 / API 36).
3. **Permissions Required**:
   * **Nearby Wi-Fi Devices & Location**: Required by Android to discover and connect to nearby Wi-Fi Aware / Hotspot peers.
   * **Record Audio**: Required for voice call microphone capture and system media projection audio capture.
   * **Bluetooth Connect**: Required for Bluetooth earbud detection and routing.
   * **Notifications**: Required to display the ongoing foreground call control notification.

---

## 📜 Open Source & Licensing

ProxiTap is licensed under the **GNU General Public License v3 (GPLv3)**.

It leverages the following open-source libraries:
- **WebRTC** (BSD 3-Clause)
- **Jetpack Compose & Kotlin/Ktor** (Apache 2.0)
- **ZXing & CameraX / MLKit Barcode Scanning** (Apache 2.0)

*(All dependencies are fully permissive and strictly compatible with the GPLv3 license).*
