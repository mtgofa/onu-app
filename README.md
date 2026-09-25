# ONU Manager — Android manager for the Huawei HG8145V5 and HG8245 routers

<p align="center">
  <a href="https://github.com/mtgofa/onu-app/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/mtgofa/onu-app?label=download&color=22c55e"></a>
  <a href="https://github.com/mtgofa/onu-app/releases/latest"><img alt="Android 7.0+" src="https://img.shields.io/badge/android-7.0%2B-3ddc84"></a>
  <a href="https://github.com/mtgofa/onu-app/releases/latest"><img alt="Language" src="https://img.shields.io/badge/language-English%20%7C%20%D8%A7%D9%84%D8%B9%D8%B1%D8%A8%D9%8A%D8%A9-1f6feb"></a>
  <a href="https://github.com/mtgofa/onu-app"><img alt="No ads, no tracking" src="https://img.shields.io/badge/ads-none-1f6feb"></a>
</p>

<p align="center"><b>English</b> · <a href="README.ar.md">العربية</a></p>

**Control your Huawei fibre router from your phone.** Change the Wi-Fi name, password and
visibility for each band, create a guest network with client isolation, and watch every
connected device live — without opening the router's web page and waiting for a reboot.

Built for the **Huawei HG8145V5** used by **TE Data** (Egypt) and other Huawei fibre ONTs that
run the same HiSilicon web interface (**HG8245H**, **HG8245H5**, **HG8245V5** …).
Native Android, written in Kotlin, no root, no ads, no account, fully offline.

---

## What it does

| | |
|---|---|
| **Wi-Fi name & password** | Rename the network and set the password per band (2.4 GHz / 5 GHz). Applied through the router's own `set.cgi`, so it takes effect in **seconds — no restart**. |
| **Guest network** | Your firmware has no Guest button, so the app creates one: an extra SSID with **client isolation** (guests reach the internet, not your devices). Pick 2.4 GHz, 5 GHz or both. |
| **Connected devices** | Live list of who is on your network, with the link speed, signal strength (RSSI) and uptime of each device. |
| **Device names & icons** | Give devices your own names (stored on your phone, never sent to the router) and a phone/PC icon per device. |
| **MTU settings** | Read the current MTU, test the real value with a packet-size probe, and set the recommended value. |
| **Speed & DNS tools** | Browsing speed check, per-device speed, DNS blocking of specific sites, and router info (model, IP, uptime, optic/WAN status). |
| **English & العربية** | Full RTL Arabic interface, switchable inside the app. |

Everything runs **locally on your own network**. The app talks to `192.168.100.1` (or your
gateway's address) and to nothing else.

## Download

1. Open [**Releases**](https://github.com/mtgofa/onu-app/releases/latest) and download the
   `ONU-Manager-HG8145V5.apk` asset.
2. On your phone, allow your file manager to **install unknown apps** when Android asks.
3. Open the APK and install. The app can also update itself from the About screen.

Or with ADB:

```bash
adb install -r ONU-Manager-HG8145V5.apk
```

**Requirements:** Android 7.0 (API 24) or newer, and your phone **connected to the router's
Wi-Fi** (the app reaches the router over the local network — it does not work over mobile data).

## Supported routers

The app speaks the stock HiSilicon web interface (`login.cgi`, `set.cgi`, `GetLanUserDevInfo.asp`,
`WlanBasic.asp`, `cfgfile.asp` …) and **detects your model automatically**.

| Status | Models |
|---|---|
| ✅ **Tested** | **HG8145V5** (TE Data Egypt) |
| 🟡 **Same web interface, not tested here** | HG8245H · HG8245H5 · HG8245V5 · HG8245H5-V5R019C10 and other V5R boards rebranded by ISPs |
| ❌ Not supported | Routers with a different web interface (TP-Link, ZTE, Nokia… ) |

Your model is read from the router itself (`var ProductName = '…'`) and shown in the app, so
you do not have to guess. If your Huawei board works — or does not — please
[open an issue](https://github.com/mtgofa/onu-app/issues) with the model name, it helps others.

## First login

The app auto-detects your gateway, then signs in with your router's **`admin`** account:

```
GetRandCount.asp  →  login.cgi  →  session id (sid)
```

The session is short-lived, so the app signs in again automatically when the router returns
`403`. The router's self-signed certificate is accepted for the local address only.

**Note:** the app needs the router password of the **`admin`** user (the same one you use in
the router's web page). It is stored encrypted with the Android Keystore and never leaves the
phone.

## Privacy and permissions

- 🔒 **No analytics, no trackers, no ads, no server of ours.** Requests go to your router only.
- 🔒 Credentials are kept in an AES-GCM vault backed by the Android Keystore, not in plain text.
- The router's certificate is not system-trusted; a network security config allows the local
  address only.

| Permission | Why |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Talk to the router on your local network |
| `ACCESS_WIFI_STATE` | Show the SSID you are connected to |
| `REQUEST_INSTALL_PACKAGES` | The in-app APK update (only when you tap *Update*) |

## Build from source

```bash
# JDK 17, Android SDK with platform 34, Gradle 8.9
cd WaslaApp
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # signed with the keys in keystore.properties
```

`minSdk 24`, `targetSdk 34`, Kotlin + Jetpack Compose (BOM 2024.09.02) + OkHttp.
The release build is signed with a keystore referenced from `keystore.properties` (not in git).

```
app/src/main/java/com/hg8145v5/manager/
  MainActivity.kt        theme, entry point, update dialog
  net/Discover.kt        gateway + model auto-discovery
  net/RouterApi.kt       OkHttp, login, set.cgi, config download/upload, parsers
  net/UpdateCheck.kt     GitHub releases feed for in-app updates
  data/CredStore.kt      Android Keystore encrypted vault
  vm/RouterViewModel.kt  state + every action (live edits, reboot-only config edits)
  ui/Screens.kt          Compose screens (login, home, devices, settings, more)
  ui/Strings.kt          English / Arabic strings
  ui/Theme.kt            colours, fonts, RTL
```

## How the app talks to the router

- **Live actions** (rename, password, visibility, guest SSID, LED) go through `set.cgi` /
  `setajax.cgi` with a fresh `onttoken`, so they are applied instantly.
- **Structural changes** (DNS blocking, MTU, user limits) go through the configuration file:
  download with `cfgfiledown.cgi`, edit the XML, upload with `cfgfileupload.cgi`, then reboot.
  The app shows clearly which of the two a setting needs.

## Roadmap

- [ ] Guest network password sync between bands
- [ ] Per-device speed limit (needs the config-file path)
- [ ] Scheduled Wi-Fi (turn the network off at night)
- [ ] More model names in the compatibility list

## Contributing

Issues and pull requests are welcome — especially **model reports**: if you tested a board that
is not listed above, tell us the model and what worked.

```bash
git clone git@github.com:mtgofa/onu-app.git
cd onu-app/WaslaApp && ./gradlew assembleDebug
```

## Credits

Built by [mtgofa](https://github.com/mtgofa). Uses the router's own web interface — no firmware
is modified and no setting is changed outside what you do in the app.

## License

[MIT](LICENSE) © 2026 mtgofa — use it, change it, ship it; just keep the credit.
The app talks to your own router, so you are responsible for the changes you apply to your
network.
