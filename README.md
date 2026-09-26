# PedalBridge

<img src="branding/pedalbridge-icon.png" width="160" alt="PedalBridge Icon">

PedalBridge verbindet ein Bodytone SMB1 V3 Spinning-Bike über einen ESP32 mit einer Garmin-Uhr und einem optionalen Android-Display. Das Smartphone ist zum Aufzeichnen eines Trainings nicht erforderlich.

- **PedalBridge 0.14.1**: ESP32-Firmware unter [`firmware/`](firmware).
- **PedalBridge Display 0.9.1**: Android-App unter [`android/`](android).

```text
Bike -- BLE FTMS --> ESP32 -- BLE CPS/CSC --> Garmin
                      |
                 Bluetooth LE
                      |
               PedalBridge Android <-- Live-Puls von der Fenix
```

Die App zeigt Leistung, Kadenz, Geschwindigkeit, Distanz, Kalorien, Widerstandsstufe und optional den Puls. Sie bietet Training, Verlauf und Einstellungen als Tabs, dunkles und helles Design, Querformat, Wischgesten sowie ein Diagramm mit Datumsachse, Zoom und Verschieben.

Die Bridge zeichnet Trainings ohne Smartphone auf. Zusammenfassungen bleiben auf dem ESP; Sekundenwerte werden nach bestätigtem Transfer in das Smartphone-Archiv freigegeben. Bei Platzbedarf entfernt die Bridge die ältesten abgeschlossenen Sekundenaufzeichnungen. Die App speichert synchronisierte Trainings dauerhaft lokal und übernimmt MyBodytone-Importdateien in den gemeinsamen Verlauf.

**Grenzen:** Mit der Fenix 8 Sapphire funktioniert die Leistungsübertragung. Geschwindigkeit, Distanz und Erkennung als Smart-Trainer sind weiterhin experimentell. Das Bike liefert seine Wattwerte; die Bridge ist kein kalibrierter Leistungsmesser. Kalorien sind, wenn kein gültiger Bike-Wert vorliegt, eine Schätzung aus Leistung mit angenommener 24 % Effizienz. Garmin kann anders rechnen. Live-Puls erfordert „Herzfrequenz senden“ auf der Uhr und die Auswahl des Pulssensors in der App.

## Installation

Siehe [Einrichtung und Builds](docs/SETUP.md), [Bluetooth-Protokoll](docs/PROTOCOL.md), [Datenspeicherung](docs/DATA.md) und [Änderungen](docs/CHANGELOG.md).

Android benötigt Android 12 oder neuer. Die Bridge wurde für einen klassischen ESP32 D1 Mini mit CP2104 und USB-C entwickelt. Die Anwendung behält `de.smb1display` und die bisherigen BLE-UUIDs. Die App erkennt auch `SMB1 Bridge` und `SMB1 Trainer`.

GPL-3.0 gemäß [LICENSE](LICENSE). Hinweise zu Abhängigkeiten und Branding stehen in [THIRD_PARTY.md](THIRD_PARTY.md). Dieses Projekt ist unabhängig von Garmin und Bodytone.
