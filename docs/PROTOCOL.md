# Bluetooth-Protokoll

Die Bridge arbeitet als BLE-Client zum Bike und BLE-Server für Garmin und Android. Standarddienste: FTMS `1826`, Cycling Power `1818` und Cycling Speed and Cadence `1816`. Garmin kann je nach Gerät nur einen Teil davon nutzen.

GAP-Name: `PedalBridge`. Die kompakte Legacy-Werbung verwendet `PedalBridge`, damit die Dienste in das 31-Byte-Limit passen. Der Bike-Suchfilter `SMB1` bleibt erhalten.

Privater App-Dienst: `9f6c1000-5a7b-4fd0-9a9f-6c30a7e63110`.

| Kennung | Funktion |
| --- | --- |
| `...1001...` | Live-Messwerte, Notify |
| `...1002...` | Steuerkommandos |
| `...1003...` | Archivtransfer |
| `...1004...` | Speicher und Konfiguration, Read |

Live-Version 3: 20 Byte, Little Endian. Version 2 enthält die ersten 18 Byte.

| Offset | Typ | Inhalt |
| --- | --- | --- |
| 0 | uint8 | Protokollversion |
| 1 | uint8 | Statusflags |
| 2 | int16 | Watt |
| 4 | uint16 | Kadenz × 10 |
| 6 | uint16 | Geschwindigkeit km/h × 100 |
| 8 | uint32 | Distanz km × 100000 |
| 12 | uint32 | Sekunden |
| 16 | uint16 | Kalorien × 10 |
| 18 | int16 | Widerstandsstufe, sofern gültig |

Die übrigen Kommandos sind in `firmware/src/main.cpp` und der Gegenstelle `android/app/src/main/java/de/smb1display/MainActivity.java` definiert. Live-Puls empfängt Android separat über den Standarddienst Heart Rate `180D` der Uhr.
