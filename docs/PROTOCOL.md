# Bluetooth protocol

The bridge is a BLE client to the bike and a BLE server for Garmin and Android. Standard services are FTMS `1826`, Cycling Power `1818`, and Cycling Speed and Cadence `1816`. Garmin may use only a subset.

The GAP and legacy advertising name is **PedalBridge**. The compact advertising payload fits within 31 bytes. The bike name filter remains `SMB1`.

Private companion service: `9f6c1000-5a7b-4fd0-9a9f-6c30a7e63110`.

| Characteristic | Purpose |
| --- | --- |
| `...1001...` | Live measurements, Notify |
| `...1002...` | Control commands |
| `...1003...` | Archive transfer |
| `...1004...` | Storage and configuration, Read |

Live packet version 3 is 20 bytes, little-endian. Version 2 includes the first 18 bytes.

| Offset | Type | Content |
| --- | --- | --- |
| 0 | uint8 | Protocol version |
| 1 | uint8 | Status flags |
| 2 | int16 | Power, W |
| 4 | uint16 | Cadence × 10 |
| 6 | uint16 | Speed, km/h × 100 |
| 8 | uint32 | Distance, km × 100000 |
| 12 | uint32 | Elapsed seconds |
| 16 | uint16 | Calories × 10 |
| 18 | int16 | Resistance level, when valid |

Other commands are defined in `firmware/src/main.cpp` and `android/app/src/main/java/de/smb1display/MainActivity.java`. Android receives live heart rate directly from the watch's standard Heart Rate service `180D`.

UI language preferences do not change UUIDs, numeric BLE packets, imported file formats or archive identities. The ESP web language is stored in Preferences under `language`; `/language` accepts only `en` and `de` and does not restart BLE.
