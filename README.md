<div align="center">
  <img src="assets/ProxiTapIcon.png" width="200" height="200" alt="ProxiTap Icon"/>
  <h1>ProxiTap</h1>
</div>

ProxiTap is the ultimate, decentralized local network walkie-talkie for Android. Built for scenarios where cellular networks are unreliable or non-existent (like scootering on the open road), ProxiTap leverages the full power of modern Android hardware to keep you and your friends connected.

## 🚀 Features

- **Decentralized P2P Networking:** Choose between Wi-Fi Aware (NAN) for seamless, drop-in background connections, or LocalOnlyHotspot for legacy device compatibility.
- **Persistent WebRTC Audio:** Ultra low-latency, persistent bidirectional audio streaming.
- **Seamless Auto-Reconnect:** Drop out of range? The app enters a silent polling state and instantly rejoins the lobby the moment you ride back into range. No buttons required.
- **Hardware Push-To-Talk (PTT):** Leave your phone in your pocket. ProxiTap intercepts your Bluetooth earbud's play/pause media button to toggle your microphone instantly.
- **RNNoise AI Wind Suppression:** An experimental C++ NDK integration of the Xiph.org RNNoise deep learning model, bypassing standard hardware cancellation constraints to eliminate extreme wind noise.
- **Wi-Fi RTT Radar:** Automatically ping and calculate the exact physical distance (in meters) to your peers using Wi-Fi Round Trip Time ranging (hardware permitting).
- **Advanced Lobby Controls:** Hosts can secure lobbies with PINs, tweak audio bitrates, force network modes, and instantly share Deep Links.
- **Android App Links:** Generate a dynamic QR code or shareable Deep Link. If your friend has the app, it instantly drops them into your lobby. If they don't, it routes them to a fallback website to download the APK.

## 🛠 Setup & Installation

1. Download the latest APK from the [Releases](https://github.com/TylerHats/ProxiTap/releases) page.
2. Install the APK on your Android device. 
3. *Note: ProxiTap relies heavily on physical hardware capabilities. Some features like Wi-Fi Aware (NAN) and RTT Radar require modern Wi-Fi chipsets.*

## 📜 Open Source & Licensing

ProxiTap is licensed under the **GNU General Public License v3 (GPLv3)**. 

It leverages the following open-source technologies:
- **WebRTC** (BSD 3-Clause)
- **RNNoise** (BSD 3-Clause)
- **Jetpack Compose & Kotlin/Ktor** (Apache 2.0)

*(All dependencies are fully permissive and strictly compatible with the GPLv3 license).*
