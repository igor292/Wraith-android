# Wraith for Android

Android VPN client for VLESS / VMess / Trojan / Shadowsocks / Hysteria2 / WireGuard on the **Xray** core. The interface is Material 3 with Material You colours. It imports Happ- and v2rayNG-style subscriptions and full Xray JSON, including multi-server configs with balancers (`leastPing` + `observatory`).

> Русский: Wraith — Android-клиент для VLESS/VMess/Trojan/SS/Hysteria2/WireGuard на ядре Xray, с дизайном Material 3. Он понимает подписки, полный Xray JSON и авто-конфиги с балансировщиками. Анонимной статистики нет; при проверке обновлений отправляется только версия приложения.

<p>
<img src="docs/screens/home-dark-connected.png" width="240">
<img src="docs/screens/home-light-off.png" width="240">
<img src="docs/screens/tunnel-dark.png" width="240">
</p>

## Features

- **Xray only.** Xray runs in an isolated `:xray` process (libXray). Since 0.12.0 sing-box is gone: TUIC, AnyTLS, SSH and sing-box JSON are not supported.
- **English and Russian.** The language follows the system.
- **Complex Xray configs work as written.**
  - Provider balancers, observatory, `dialerProxy` chains, fragment and noise dialers are kept.
  - Multi-server documents without a balancer can get an automatic `leastPing` balancer ("fastest server automatically").
- **Latency checks.** TCP, ICMP, or a real HTTP GET/HEAD through every server of a pool. The proxy check runs in a separate short-lived Xray instance, with no VPN needed.
- **Subscriptions.** Usage and expiry, announcements, update interval, QR / clipboard / file import, share links and QR export.
- **Routing.** Domain / IP / geosite / geoip / app rules, per-app split tunnelling, IPv6 handling, MTU, TLS fragmentation, mux.
- **DNS.** Uses the phone's own DNS by default. The provider's DNS from JSON, or your own DoH/DoT address, are opt-in switches.
- **Privacy.**
  - No analytics or telemetry.
  - Updates come from GitHub Releases; the check sends only `User-Agent: Wraith/<version>/Android`.
  - Settings are stored encrypted with the Android Keystore; backups are off.
- **Hardening.**
  - Core control ports (Xray metrics) are random per session.
  - The local SOCKS/HTTP proxy is off by default, and LAN access requires a password.
  - A `wraith://` link from a web page cannot silently turn the VPN off.
  - Provider configs cannot open their own API listeners or write log files.
- Quick Settings tile, boot start, `wraith://connect` / `disconnect` / `toggle` automation links (Happ-compatible `happ://` too).

## Windows

The Windows client lives in its own repository: **[Wraith-windows](https://github.com/trinity13338/Wraith-windows)**. It is a native Rust app with the same import, the same core and the same look as this one.

## Building (Android)

Requirements: JDK 17+, Android SDK 35 (build-tools 35.0.0), Gradle 8.10.2.

1. Put `libXray.aar` (libXray v26.9.30, MIT, from the [official releases](https://github.com/XTLS/libXray/releases)) in `app/libs/`. It is not committed because of its size.
2. Write `sdk.dir=<path to Android SDK>` into `local.properties`.
3. Build:

   ```sh
   ./gradlew testReleaseUnitTest assembleRelease
   ```

   Then sign the APK with your own key.

### UI screenshots without a device

```sh
WRAITH_SCREENS=1 ./gradlew testReleaseUnitTest --tests '*ScreenshotTest*'
```

The PNGs are written to `app/build/screens`, rendered with Robolectric.

### Updates

The app checks the latest GitHub release of `trinity13338/Wraith-android` (`UpdateChecker.REPO`) and installs its APK only if the SHA-256 digest GitHub reports for the asset matches. Downloads may only reach GitHub hosts. Forks should change `REPO`.

## License

Wraith is licensed under **GPL-3.0-or-later** (see [LICENSE](LICENSE)). Third-party components:

- Xray-core (MPL-2.0)
- libXray (MIT)
- ZXing / zxing-android-embedded (Apache-2.0)
- Material Components and AndroidX (Apache-2.0)
- geoip/geosite data from the v2fly / Loyalsoldier projects

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Wraith is a client only. No servers are bundled, and no conversion service is used.
