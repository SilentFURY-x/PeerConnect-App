# PeerConnect

![Android](https://img.shields.io/badge/Platform-Android-green) ![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple) ![Status](https://img.shields.io/badge/Status-Stable_Beta-blue)

**PeerConnect** is a serverless, offline-first Android messaging application. It allows devices to communicate via Wi-Fi P2P and Bluetooth Low Energy (BLE) without requiring an internet connection, cellular data, or a centralized server.

Built using the **Google Nearby Connections API**, PeerConnect is designed for resiliency, privacy, and seamless proximity communication.

## 🌟 Key Features

* **🚫 100% Offline:** Works in airplanes, subways, or remote areas with no signal.
* **🔄 Auto-Discovery Engine:** Uses a randomized role-switching algorithm to automatically find and connect to known peers in the background.
* **🔒 End-to-End Encryption:** Messages are encrypted locally before transmission.
* **📂 File Sharing:** Supports sharing images and documents directly between devices using the `MediaStore` API.
* **💾 Local Persistence:** Chat history and friend lists are stored locally using a Room Database.
* **📍 Location Enforcer:** Automatically handles Android's strict Location/GPS requirements for P2P discovery.

## 🛠️ Tech Stack

* **Language:** Kotlin
* **Architecture:** MVVM (Model-View-ViewModel)
* **Connectivity:** Google Nearby Connections API (Strategy: `P2P_STAR`)
* **Database:** Android Room (SQLite abstraction)
* **Concurrency:** Kotlin Coroutines & Lifecycle Scopes

## 🚀 Installation & Setup

1.  **Clone the repository:**
    ```bash
    git clone [https://github.com/YourUsername/PeerConnect.git](https://github.com/YourUsername/PeerConnect.git)
    ```
2.  **Open in Android Studio:** Ensure you have the latest version (Hedgehog or newer).
3.  **Permissions:** The app requires the following permissions (handled at runtime):
    * `BLUETOOTH_SCAN` / `ADVERTISE` / `CONNECT`
    * `ACCESS_FINE_LOCATION`
    * `NEARBY_WIFI_DEVICES` (Android 13+)
4.  **Build & Run:** Deploy to **two** physical Android devices (Note: Emulators do not support Bluetooth/Wi-Fi P2P).

## 📖 How It Works

### 1. The "Heartbeat" Loop
Unlike standard Bluetooth, Nearby Connections requires one device to be an Advertiser and one to be a Discoverer. To solve the "who connects to whom" problem without a server, PeerConnect implements a **Randomized Role Switching Loop**:

1.  Device A starts Scanning.
2.  Device B starts Advertising.
3.  If no connection is made within `X` seconds, they swap roles.
4.  **Ghost Timer Fix:** A custom handler ensures that role-switching timers are cancelled immediately upon connection to prevent the radio from resetting during a chat.

### 2. Connection Lifecycle
* **Handshake:** Devices exchange keys and nicknames securely.
* **Stability:** The app manages radio state to prevent "Radio Monopoly," where one connection blocks the discovery of other peers.
* **Resiliency:** If a packet fails, the connection is maintained, and the message is queued locally for retry.

## 🤝 Contributing

1.  Fork the Project
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4.  Push to the Branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

## 🔮 Future Roadmap

* [ ] **Background Service:** Keep connections alive when the app is minimized (Foreground Service).
* [ ] **Mesh Networking:** Allow Device A to talk to Device C via Device B.
* [ ] **Voice Calls:** WebRTC implementation over the offline socket.

