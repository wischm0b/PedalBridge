# Einrichtung und Builds

## ESP32

PlatformIO installieren und im Ordner `firmware` ausführen:

```powershell
pio run -e esp32_d1_mini
pio device list
pio run -e esp32_d1_mini --target upload --upload-port COM3
pio device monitor --baud 115200
```

`COM3` durch den tatsächlichen Port ersetzen. Die alternative Boarddefinition heißt `esp32dev`. Konfiguration und GPIO-Zuordnung stehen in `include/config.h`. Die LED ist auf vielen D1-Mini-Boards einfarbig; Helligkeit und Blinkmuster lassen sich einstellen.

Das Setup-WLAN heißt `PedalBridge-Setup`, Standardpasswort `smb1bridge`. Die Konfigurationsseite ist unter `http://192.168.4.1` erreichbar und enthält Diagnose. WLAN bleibt bis zum ausdrücklichen Ausschalten aktiv. Bei laufender Firmware BOOT/FLASH etwa 2,5 Sekunden halten oder innerhalb von 12 Sekunden zweimal zurücksetzen, um Setup erneut zu öffnen. RST ist der Reset-Pin; der Doppelklick wird durch gespeicherten Zustand erkannt.

Auf der Garmin nach `PedalBridge` suchen. Bluetooth-Namen können auf bereits gekoppelten Geräten zwischengespeichert sein. Die Handy-App nutzt BLE und benötigt keine WLAN-Verbindung zum ESP.

Ein Firmware-Update muss **ohne Löschen des gesamten Flashs oder Dateisystems** erfolgen, um das Archiv zu erhalten. Die Release-Anwendungsdatei gehört an Adresse `0x10000` und setzt einen bereits mit diesem Projekt eingerichteten Bootloader und dessen Standardpartitionierung voraus. Für ein neues Board das vollständige Projekt mit PlatformIO hochladen. Rohe Geräte-Backups können persönliche Trainingsdaten enthalten.

## Android

JDK 17 und Android SDK 36.1 installieren. `ANDROID_HOME` auf das SDK setzen oder eine nicht versionierte `android/local.properties` mit `sdk.dir=...` anlegen. Im Ordner `android`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Linux: `bash gradlew ...`. Das APK liegt unter `app/build/outputs/apk/release/`. Auf dem Handy Bluetooth-Berechtigungen erlauben. Zum Empfang des Pulses auf der Fenix „Herzfrequenz senden“ aktivieren und in den App-Einstellungen den Pulssensor auswählen.

**Signierung:** Die derzeitige Release-Konfiguration verwendet den lokalen Android-Debug-Schlüssel. Das veröffentlichte APK wird mit dem bisher auf diesem Entwicklungs-PC verwendeten Schlüssel gebaut und kann die vorhandene Installation aktualisieren. Eigene oder CI-Builds mit einem anderen Schlüssel können diese Installation nicht ersetzen. Den bestehenden Schlüssel sichern und niemals in Git einchecken. Eine gesonderte Release-Signierung benötigt einen geplanten Migrationsweg.

## Tests

Android-Tests verwenden synthetische Daten. Firmware-Selbsttests lassen sich nach dem regulären Build mit `pio run -e ftms_test`, `pio run -e session_test` und `pio run -e history_test` bauen. Das Ausführen benötigt ein separates Testboard; Archivtests dürfen nicht auf dem produktiven Trainingsarchiv ausgeführt werden.
