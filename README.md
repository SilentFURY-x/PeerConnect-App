<div align="center">
   
# PeerConnect 📱🔗

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple?style=for-the-badge&logo=kotlin)
![Platform](https://img.shields.io/badge/Platform-Android-green?style=for-the-badge&logo=android)
![API](https://img.shields.io/badge/API-Google_Nearby_Connections-blue?style=for-the-badge&logo=google)
![Status](https://img.shields.io/badge/Status-Active_Development-orange?style=for-the-badge)

</div>

> **Serverless. Private. Resilient.**

**PeerConnect** is an offline-first, peer-to-peer (P2P) messaging application for Android. It enables devices to create a local mesh network via **Wi-Fi P2P** and **Bluetooth Low Energy (BLE)**, allowing for secure chat and file transfer without an internet connection, cellular data, or a centralized server.

It is designed for high-interference environments, subways, airplanes, or remote locations where traditional connectivity fails.

---

## 📥 Download
[Download the latest APK here](https://github.com/SilentFURY-x/PeerConnect-App/releases)

---

## 🌟 Key Features

### 📡 Connectivity & Discovery
* **100% Offline:** Operates entirely on the device's radio hardware (Bluetooth/Wi-Fi).
* **Hybrid Discovery Engine:**
    * **Auto-Connect:** Uses a background algorithm to automatically detect and reconnect to known "friends" without user intervention.
    * **Scan & Select:** A manual "Pairing Mode" that scans for unknown devices and presents a selection list, preventing accidental connections to strangers.
* **Location Enforcer:** Automatically handles Android's strict location/GPS requirements for P2P discovery (Android 10 through 14+).

### 💬 Messaging & Data
* **End-to-End Encryption:** Messages are AES-encrypted locally before transmission, ensuring privacy even over open airwaves.
* **File Sharing:** Supports binary stream transfer for images and documents. Files are automatically saved to the system `Downloads` folder using the `MediaStore` API.
* **Resilient Queuing:** If a device disconnects mid-chat, messages are queued locally in a Room Database and auto-flushed upon reconnection.

---

## 🛠️ Tech Stack

* **Language:** Kotlin
* **Connectivity:** Google Nearby Connections API (Strategy: `P2P_STAR`)
* **Database:** Android Room (SQLite abstraction) for persistence.
* **Concurrency:** Kotlin Coroutines & `lifecycleScope`.
* **Architecture:** Event-Driven Activity (Refactoring to MVVM in progress).

---

## 📖 How It Works

### 1. The "Heartbeat" Loop (Auto-Connect)
Unlike standard Bluetooth, the Nearby Connections API requires one device to be an **Advertiser** and one to be a **Discoverer**. To solve the "who connects to whom" problem without a server, PeerConnect implements a **Randomized Role Switching Loop**:
1.  Device A starts **Scanning**.
2.  Device B starts **Advertising**.
3.  If no connection is made within `X` seconds, they swap roles.
4.  *Ghost Timer Fix:* A custom handler ensures that role-switching timers are cancelled immediately upon connection to prevent the radio from resetting during an active chat.

### 2. The Pairing Handshake (Manual)
When adding a new contact, the app suspends the auto-loop and enters **Pairing Mode**:
1.  **Host:** Broadcasts availability securely.
2.  **Joiner:** Scans for signals and filters them into a temporary list.
3.  **Selection:** The user is presented with a dialog of found devices. Connection is only initiated when a specific peer is tapped.
4.  **Identity:** Devices exchange cryptographically generated IDs and nicknames.

### 3. Data Transmission
* **Text:** Serialized Java Objects converted to Byte Payloads.
* **Files:** `Payload.Type.FILE` utilizes `ParcelFileDescriptor` to stream data directly from storage to storage, bypassing memory limits.

---

## 🚀 Installation & Setup

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/SilentFURY-x/PeerConnect-App.git
    ```
2.  **Open in Android Studio:**
    Ensure you are using the latest version (Hedgehog or newer).
3.  **Permissions:**
    The app requests the following runtime permissions:
    * `BLUETOOTH_SCAN` / `ADVERTISE` / `CONNECT`
    * `ACCESS_FINE_LOCATION`
    * `NEARBY_WIFI_DEVICES` (Android 13+)
4.  **Build & Run:**
    * Deploy to **two physical Android devices**.
    * *Note: Android Emulators do not support Bluetooth/Wi-Fi P2P protocols.*

---

## 📂 Project Structure

```text
com.fury.peerconnect
├── data
│   ├── database       # Room Database setup
│   ├── daos           # Data Access Objects (PeerDao, MessageDao)
│   └── entities       # Data classes (PeerEntity, MessageEntity)
├── logic
│   ├── UserManager    # Identity & SharedPrefs wrapper
│   └── SecurityHelper # AES Encryption logic
├── ui
│   ├── adapters       # RecyclerView Adapters for Chat/Peers
│   └── MainActivity.kt # Core Radio Logic & UI Controller
```
---

## 🔮 Future Roadmap

- [ ] **Background Service:** Implement a Foreground Service to keep the radio alive when the app is minimized.
- [ ] **Mesh Networking:** Implement routing logic to allow Device A to talk to Device C via Device B.
- [ ] **Voice Calls:** WebRTC implementation over the offline socket for real-time audio.
- [ ] **MVVM Refactor:** Migrate UI logic from Activity to ViewModels for better testability.

---

## 🤝 Contributing

Contributions are what make the open-source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

1.  Fork the Project
2.  Create your Feature Branch:
    ```bash
    git checkout -b feature/AmazingFeature
    ```
3.  Commit your Changes:
    ```bash
    git commit -m 'Add some AmazingFeature'
    ```
4.  Push to the Branch:
    ```bash
    git push origin feature/AmazingFeature
    ```
5.  Open a Pull Request

---

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.

---

## 👨‍💻 Author
<div align="center">

**Arjun Tyagi**

[![GitHub](https://img.shields.io/badge/github-%23121011.svg?style=for-the-badge&logo=github&logoColor=white)](https://github.com/SilentFURY-x)
[![LinkedIn](https://img.shields.io/badge/linkedin-%230077B5.svg?style=for-the-badge&logo=linkedin&logoColor=white)](https://www.linkedin.com/in/arjun-tyagi-84b1b5328/)

</div>

---

<p align="center">
  Built with ❤️ by <b>Arjun Tyagi</b>
</p>

