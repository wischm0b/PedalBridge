# Setup and builds

## ESP32

Install PlatformIO and run these commands in `firmware`:

```powershell
pio run -e esp32_d1_mini
pio device list
pio run -e esp32_d1_mini --target upload --upload-port COM3
pio device monitor --baud 115200
```

Replace `COM3` with the actual serial port. `esp32dev` is an alternative board definition. GPIO assignments and defaults are in `include/config.h`. Many D1 Mini boards have a single-color LED; brightness and blink patterns can be adjusted. ESP32-S2 has no Bluetooth and cannot be used.

The setup hotspot is **PedalBridge-Setup**, default password **smb1bridge**. Open `http://192.168.4.1` for setup and diagnostics. Use the page's Language selector to switch between English and Deutsch. The preference survives reset and is independent of the phone setting.

Wi-Fi remains active until explicitly disabled. While the firmware is running, hold BOOT/FLASH for about 2.5 seconds or press reset twice within 12 seconds to reopen setup. RST is the reset pin; the double press is detected using persisted state.

Find **PedalBridge** in Garmin's sensor search. Existing pairings may cache the old name. The phone app uses BLE and does not need to connect to the ESP's Wi-Fi.

Update firmware **without erasing the entire flash or filesystem**, to preserve workouts. The release BIN is the application for **address 0x10000** and assumes this project's existing bootloader and standard partition layout. Initialize a new board using PlatformIO. Raw device backups can contain personal training data.

## Android

Install JDK 17 and Android SDK 36.1. Set `ANDROID_HOME` or create an untracked `android/local.properties` containing `sdk.dir=...`. Run in `android`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

On Linux, use `bash gradlew ...`. The APK is generated in `app/build/outputs/apk/release/`. Grant Bluetooth permissions on your phone. The app opens in English by default; choose Settings → Appearance → Language to switch to Deutsch. Switching language preserves BLE clients, live data, the selected tab and pending bridge settings.

To receive live pulse, enable Broadcast Heart Rate on the Fenix and select its heart rate sensor in the app's Settings.

**Signing:** The current release configuration uses the local Android debug key. Published APKs use the same key as earlier local releases and can update existing installations. Own or CI builds signed with another key cannot replace them. Back up the existing key and never commit it. A dedicated release-signing setup needs a planned migration.

## Tests

Android tests use synthetic data and cover BLE lifecycle, history, charts, dialogs, gestures and language switching. Firmware self-test variants can be compiled after the regular build with `pio run -e ftms_test`, `pio run -e session_test`, and `pio run -e history_test`. Running these tests requires a separate test board. Never run archive self-tests against a production workout archive.
