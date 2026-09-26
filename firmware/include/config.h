#pragma once

// The advertised name used by the Bodytone SMB1 V3 console.
// Leave empty to accept the first nearby device advertising FTMS.
#define BIKE_NAME_FILTER "SMB1"

// Name shown in the Garmin sensor search.
#define GARMIN_SENSOR_NAME "PedalBridge"

// Used to translate the bike's virtual speed into wheel revolutions.
// Power and cadence do not depend on this value.
#define VIRTUAL_WHEEL_CIRCUMFERENCE_MM 2105.0f

// Wemos/LOLIN D1 mini style ESP32 boards normally use GPIO 2 for the LED.
#define STATUS_LED_PIN 2
#define STATUS_LED_ACTIVE_LOW 1

// Hold the BOOT/FLASH button while the firmware is already running to reopen
// setup. GPIO 0 is the standard BOOT pin on classic ESP32 boards.
#define CONFIG_BUTTON_PIN 0
#define CONFIG_BUTTON_HOLD_MS 2500UL

// Setup and diagnostics access point. It remains active until it is explicitly
// disabled on the web page. BOOT or a double reset enables it again.
#define CONFIG_AP_NAME "PedalBridge-Setup"
#define CONFIG_AP_PASSWORD "smb1bridge"
#define DOUBLE_RESET_WINDOW_MS 12000UL

// How often the emulated power meter sends a measurement to the Garmin.
#define GARMIN_UPDATE_INTERVAL_MS 1000UL
#define BIKE_DATA_TIMEOUT_MS 4000UL
#define SCAN_DURATION_SECONDS 10
