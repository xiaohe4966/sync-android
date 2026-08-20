# SquadSync Android

LAN + relay hybrid media-control app. Slide the volume/brightness on
one phone, every other phone in the same room (mDNS or a relay server)
mirrors it.

|                |                                                              |
| -------------- | -------------------------------------------------------------- |
| Roles          | Every phone is **both** master and slave                       |
| Transport      | mDNS (LAN) primary, WebSocket relay (Internet) fallback      |
| Default relay  | `wss://sync.he66.cn` (configurable)                            |
| Min Android    | 8.0 (API 26)                                                 |
| Target Android | 14 (API 34)                                                  |
| Build          | Gradle 8.5+, JDK 17, Android Gradle Plugin 8.5                |
| UI             | Jetpack Compose + Material 3                                  |
| WebSocket      | OkHttp 4.12 (Android-compatible WebSocket)                     |
| Wire protocol  | Custom `Wire.kt` sealed class, kotlinx-serialization JSON     |

## What you can do once installed

* Slide **volume** or **brightness** — all peers (LAN or relay) match.
* Tap **play / pause / next / prev** to control media on every phone.
* Tap **"打开应用"** to launch any installed app on a peer by name
  (e.g. `com.luna.music`), or pick from the peer's app list.
* Pick which peers respond by ticking/unticking the checkbox on each
  device card. **"全选" / "全不选"** does it in bulk.

## Build

```bash
cd app
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

The release build uses the debug signing config for now so `adb install`
works out of the box. Replace it with a real keystore before
publishing.

## Install

```bash
adb install -r -i com.android.shell app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.squadsync.app android.permission.POST_NOTIFICATIONS
adb shell appops set com.squadsync.app WRITE_SETTINGS allow
```

## Configure the relay

The app ships with a default relay URL `wss://sync.he66.cn`. Override it
in the app: expand **配置**, fill **转发服务器 URL** with your server,
tap **试连**.

| Color | Meaning                          |
| ----- | -------------------------------- |
| Grey  | No URL configured                |
| Green | Connected to the relay           |
| Amber | URL set, connecting / retrying  |

The hint below the dot tells you **where your next command will go**:
* `📡 命令通过：本地 mDNS 局域网`
* `📡 命令通过：转发服务器 (wss://sync.he66.cn)`

When the relay is connected, the app sends every command **only**
through the relay. When the relay is down, it falls back to mDNS LAN
peers.

## Project structure

```
app/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/squadsync/app/
    │   ├── SquadSyncApp.kt
    │   ├── model/             # Wire protocol, AppPrefs, Peer
    │   ├── media/            # AudioManager + brightness helpers
    │   ├── net/              # NsdController, RelayClient, MasterClient
    │   │                    # SlaveServer, WsFrames (hand-rolled RFC6455)
    │   └── ui/               # AutoStartService, EventLog, MainActivity,
    │                        # SquadViewModel
    └── res/
```

See `model/Wire.kt` for the wire schema, `net/SlaveServer.kt` for the
slave WS handshake, and `net/RelayClient.kt` for the client→relay
transport.

## Companion

* [squadsync-relay](https://github.com/xiaohe4966/sync-relay) — the
  WebSocket fan-out server

## License

MIT. See [LICENSE](LICENSE).
