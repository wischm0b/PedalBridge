#include <Arduino.h>
#include <DNSServer.h>
#include <NimBLEDevice.h>
extern "C" {
#include "nimble/nimble/host/services/gatt/include/services/gatt/ble_svc_gatt.h"
}
#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>

#include "config.h"
#include "training_archive.h"
#include "web_language.h"
#if defined(HISTORY_SELF_TEST) || defined(SESSION_SELF_TEST)
#include "history_selftest.h"
#endif
#ifdef HISTORY_SELF_TEST
#include "archive_selftest.h"
#endif

namespace {

constexpr char kFtmsServiceUuid[] = "1826";
constexpr char kIndoorBikeDataUuid[] = "2AD2";
constexpr char kFitnessMachineControlPointUuid[] = "2AD9";
constexpr char kFitnessMachineFeatureUuid[] = "2ACC";
constexpr char kFitnessMachineStatusUuid[] = "2ADA";
constexpr char kSupportedResistanceRangeUuid[] = "2AD6";
constexpr char kSupportedPowerRangeUuid[] = "2AD8";

constexpr char kCyclingPowerServiceUuid[] = "1818";
constexpr char kCyclingPowerMeasurementUuid[] = "2A63";
constexpr char kCyclingPowerFeatureUuid[] = "2A65";
constexpr char kSensorLocationUuid[] = "2A5D";

constexpr char kCyclingSpeedCadenceServiceUuid[] = "1816";
constexpr char kCscMeasurementUuid[] = "2A5B";
constexpr char kCscFeatureUuid[] = "2A5C";

constexpr char kDeviceInformationServiceUuid[] = "180A";
constexpr char kManufacturerNameUuid[] = "2A29";
constexpr char kModelNumberUuid[] = "2A24";
constexpr char kFirmwareRevisionUuid[] = "2A26";

// Compact live-data service for a companion phone app. It intentionally uses
// private UUIDs so Garmin continues to treat the standard CPS/CSC services as
// native sensors.
constexpr char kPhoneServiceUuid[] = "9f6c1000-5a7b-4fd0-9a9f-6c30a7e63110";
constexpr char kPhoneLiveDataUuid[] = "9f6c1001-5a7b-4fd0-9a9f-6c30a7e63110";
constexpr char kPhoneControlUuid[] = "9f6c1002-5a7b-4fd0-9a9f-6c30a7e63110";
constexpr char kPhoneHistoryUuid[] = "9f6c1003-5a7b-4fd0-9a9f-6c30a7e63110";
history::Archive gHistory;
int gHistoryDevice = -1;
NimBLECharacteristic* gHistoryData = nullptr;
portMUX_TYPE gHistoryCommandMux = portMUX_INITIALIZER_UNLOCKED;
bool gHistoryPageRequested = false, gHistoryFinishRequested = false;
uint32_t gHistoryOffset = 0, gHistoryTime = 0;
uint16_t gHistoryTransfer = 0;
String gHistorySnapshot;
uint16_t gHistorySnapshotTransfer = 0;
NimBLECharacteristic* gManagementData=nullptr;
uint8_t gArchiveCommand[20]={},gArchiveCommandSize=0;
uint32_t gSnapshotBefore=0,gLastManagementUpdate=0;

constexpr uint16_t kCpsWheelRevolutionPresent = 1U << 4;
constexpr uint16_t kCpsCrankRevolutionPresent = 1U << 5;
constexpr uint32_t kCpsWheelRevolutionSupported = 1UL << 2;
constexpr uint32_t kCpsCrankRevolutionSupported = 1UL << 3;
constexpr size_t kMaxScanEntries = 40;
constexpr uint8_t kNoPendingControlOpcode = 0xff;
constexpr uint8_t kFtmsRequestControlOpcode = 0x00;
constexpr uint8_t kFtmsStartResumeOpcode = 0x07;
constexpr uint8_t kFtmsResponseOpcode = 0x80;
constexpr uint8_t kFtmsSuccessResult = 0x01;
constexpr uint8_t kFtmsInvalidParameterResult = 0x03;

struct BikeMetrics {
  float speedKph = 0.0f;
  float cadenceRpm = 0.0f;
  int16_t powerWatts = 0, resistanceLevel=0;
  uint32_t resistanceAtMs=0, energyAtMs=0;
  uint16_t totalEnergyKcal = 0;
  uint32_t receivedAtMs = 0;
  bool hasSpeed = false;
  bool hasCadence = false;
  bool hasPower = false;
  bool hasEnergy = false,hasResistance=false;
};

struct ScanEntry {
  char name[48] = {};
  char address[18] = {};
  int16_t rssi = -127;
  uint8_t addressType = 0;
  bool advertisesFtms = false;
  uint32_t lastSeenMs = 0;
};

portMUX_TYPE gMetricsMux = portMUX_INITIALIZER_UNLOCKED;
BikeMetrics gMetrics;
portMUX_TYPE gScanEntriesMux = portMUX_INITIALIZER_UNLOCKED;
ScanEntry gScanEntries[kMaxScanEntries];
size_t gScanEntryCount = 0;

Preferences gPreferences;
DNSServer gDnsServer;
WebServer gWebServer(80);
String gConfiguredAddress;
String gConfiguredName;
uint8_t gConfiguredAddressType = 0;
bool gConfigPortalActive = false;
bool gWifiShutdownPending = false;
volatile bool gWifiDisableFromBleRequested = false;
uint32_t gWifiShutdownRequestedAtMs = 0;
uint32_t gButtonPressedAtMs = 0;
bool gButtonHandled = false;
bool gResetArmCleared = false;

volatile int gLastBikeRssi = -127;
volatile bool gLastBikeConnectable = false;
volatile uint8_t gLastBikeAdvType = 0;
volatile uint32_t gLastBikeSeenMs = 0;
uint32_t gConnectionAttempts = 0;
String gLastBikeError = "Noch kein Verbindungsversuch";
String gFtmsControlStatus = "Noch nicht verfügbar";

NimBLECharacteristic* gPowerMeasurement = nullptr;
NimBLECharacteristic* gCscMeasurement = nullptr;
NimBLECharacteristic* gPhoneLiveData = nullptr;
NimBLECharacteristic* gFtmsMirrorMeasurement = nullptr;
NimBLECharacteristic* gFtmsMirrorControlPoint = nullptr;
NimBLECharacteristic* gFtmsMirrorStatus = nullptr;
NimBLEClient* gBikeClient = nullptr;
NimBLERemoteCharacteristic* gFtmsControlPoint = nullptr;
NimBLEAddress gBikeCandidateAddress;
bool gBikeCandidateAvailable = false;
volatile uint8_t gPendingControlOpcode = kNoPendingControlOpcode;
volatile bool gAwaitingControlResponse = false;
volatile uint8_t gLastControlOpcode = kNoPendingControlOpcode;
volatile uint32_t gControlCommandSentAtMs = 0;
uint8_t gControlRetryCount = 0;

volatile bool gConnectRequested = false;
volatile bool gBikeConnected = false;
volatile bool gGarminConnected = false;
volatile bool gGarminSubscribed = false;
volatile bool gGarminSpeedSubscribed = false;
volatile bool gGarminFtmsSubscribed = false;
volatile bool gPhoneConnected = false;
volatile bool gPhoneSubscribed = false;
volatile uint16_t gGarminConnectionHandle = 0xffff;
volatile uint16_t gPhoneConnectionHandle = 0xffff;
volatile bool gScanFinished = false;
volatile uint32_t gFtmsPacketCount = 0;
volatile uint32_t gGarminNotificationCount = 0;
volatile uint32_t gGarminSpeedNotificationCount = 0;
volatile uint32_t gGarminFtmsNotificationCount = 0;

uint16_t gCumulativeCrankRevolutions = 0;
uint16_t gLastCrankEventTime = 0;
float gCrankRemainder = 0.0f;

uint32_t gCumulativeWheelRevolutions = 0;
uint16_t gLastWheelEventTime = 0;
uint16_t gCscLastWheelEventTime = 0;
float gWheelRemainder = 0.0f;

uint32_t gLastMeasurementMs = 0;
uint32_t gLastScanStartMs = 0;
uint32_t gSessionDurationSeconds = 0;
float gSessionDistanceKm = 0.0f;
float gSessionCaloriesKcal = 0.0f;

uint16_t readLe16(const uint8_t* data) {
  return static_cast<uint16_t>(data[0]) |
         (static_cast<uint16_t>(data[1]) << 8);
}

int16_t readLeS16(const uint8_t* data) {
  return static_cast<int16_t>(readLe16(data));
}

void appendLe16(uint8_t* buffer, size_t& offset, uint16_t value) {
  buffer[offset++] = static_cast<uint8_t>(value & 0xff);
  buffer[offset++] = static_cast<uint8_t>((value >> 8) & 0xff);
}

void appendLe32(uint8_t* buffer, size_t& offset, uint32_t value) {
  buffer[offset++] = static_cast<uint8_t>(value & 0xff);
  buffer[offset++] = static_cast<uint8_t>((value >> 8) & 0xff);
  buffer[offset++] = static_cast<uint8_t>((value >> 16) & 0xff);
  buffer[offset++] = static_cast<uint8_t>((value >> 24) & 0xff);
}

void appendLe24(uint8_t* buffer, size_t& offset, uint32_t value) {
  buffer[offset++] = static_cast<uint8_t>(value & 0xff);
  buffer[offset++] = static_cast<uint8_t>((value >> 8) & 0xff);
  buffer[offset++] = static_cast<uint8_t>((value >> 16) & 0xff);
}

bool consume(size_t& offset, size_t count, size_t length) {
  if (offset + count > length) {
    return false;
  }
  offset += count;
  return true;
}

std::string lowerCopy(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(),
                 [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  return value;
}

void setLed(bool on) {
  uint32_t level=on ? (255UL*gHistory.ledBrightness*gHistory.ledBrightness)/10000UL:0;
  ledcWrite(7,STATUS_LED_ACTIVE_LOW ? 255-level:level);
}

String htmlEscape(const char* input) {
  String output;
  while (*input != '\0') {
    switch (*input) {
      case '&': output += F("&amp;"); break;
      case '<': output += F("&lt;"); break;
      case '>': output += F("&gt;"); break;
      case '"': output += F("&quot;"); break;
      default: output += *input; break;
    }
    ++input;
  }
  return output;
}

String jsonEscape(const String& input) {
  String output;
  output.reserve(input.length() + 8);
  for (size_t i = 0; i < input.length(); ++i) {
    const char c = input[i];
    if (c == '\\' || c == '"') output += '\\';
    if (c == '\n') {
      output += F("\\n");
    } else if (c != '\r') {
      output += c;
    }
  }
  return output;
}

bool isConnectableAdvertisement(NimBLEAdvertisedDevice* device) {
  // NimBLE-Arduino 1.4.3 evaluates legacy ADV_IND (type 0) with extended-
  // advertising bit masks and reports it as non-connectable. Decode legacy
  // event types explicitly so the diagnostics remain accurate.
  if (device->isLegacyAdvertisement()) {
    const uint8_t type = device->getAdvType();
    return type == 0 || type == 1;
  }
  return device->isConnectable();
}

void recordScanEntry(NimBLEAdvertisedDevice* device, bool advertisesFtms) {
  const std::string address = device->getAddress().toString();
  const std::string name = device->getName();

  portENTER_CRITICAL(&gScanEntriesMux);
  size_t index = gScanEntryCount;
  for (size_t i = 0; i < gScanEntryCount; ++i) {
    if (strncmp(gScanEntries[i].address, address.c_str(),
                sizeof(gScanEntries[i].address)) == 0) {
      index = i;
      break;
    }
  }
  if (index == gScanEntryCount && gScanEntryCount < kMaxScanEntries) {
    ++gScanEntryCount;
  }
  if (index < kMaxScanEntries) {
    ScanEntry& entry = gScanEntries[index];
    strncpy(entry.name, name.c_str(), sizeof(entry.name) - 1);
    entry.name[sizeof(entry.name) - 1] = '\0';
    strncpy(entry.address, address.c_str(), sizeof(entry.address) - 1);
    entry.address[sizeof(entry.address) - 1] = '\0';
    entry.rssi = device->getRSSI();
    entry.addressType = device->getAddressType();
    entry.advertisesFtms = entry.advertisesFtms || advertisesFtms;
    entry.lastSeenMs = millis();
  }
  portEXIT_CRITICAL(&gScanEntriesMux);
}

String buildConfigPage() {
  ScanEntry entries[kMaxScanEntries];
  size_t count = 0;
  portENTER_CRITICAL(&gScanEntriesMux);
  count = gScanEntryCount;
  memcpy(entries, gScanEntries, sizeof(entries));
  portEXIT_CRITICAL(&gScanEntriesMux);

  std::sort(entries, entries + count,
            [](const ScanEntry& left, const ScanEntry& right) {
              if (left.advertisesFtms != right.advertisesFtms) {
                return left.advertisesFtms > right.advertisesFtms;
              }
              return left.rssi > right.rssi;
            });

  String page;
  page.reserve(15000);
  page += webText(F("<!doctype html><html lang='de'><head><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<title>PedalBridge</title><style>"
            ":root{--ink:#090909;--soft:#f1f1ef;--line:#d8d8d4;--ok:#168348;--bad:#b32929}"
            "*{box-sizing:border-box}body{margin:0;background:#fff;color:var(--ink);font-family:Arial,Helvetica,sans-serif}"
            ".wrap{max-width:980px;margin:auto;padding:30px 20px 60px}.brand{font-size:clamp(48px,12vw,108px);font-weight:200;letter-spacing:-7px;line-height:.9;margin:4px 0 38px}"
            ".brand b{font-weight:900;font-style:italic}.eyebrow{text-transform:uppercase;letter-spacing:.18em;font-size:11px;font-weight:700}"
            "h2{font-size:25px;margin:8px 0 18px}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:1px;background:var(--line);border:1px solid var(--line);margin:16px 0 28px}"
            ".metric{background:#fff;padding:20px;min-height:112px}.metric span{display:block;color:#666;font-size:12px;margin-bottom:12px;text-transform:uppercase;letter-spacing:.08em}.metric strong{font-size:clamp(25px,5vw,42px);font-variant-numeric:tabular-nums}"
            ".dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#999;margin-right:8px}.dot.ok{background:var(--ok)}.dot.bad{background:var(--bad)}"
            ".panel{border-top:3px solid #000;padding-top:16px;margin-top:30px}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:12px 8px;border-bottom:1px solid var(--line);font-size:14px}"
            "th{font-size:11px;text-transform:uppercase;letter-spacing:.08em}.muted{color:#666}.yes{font-weight:700}button{border:0;background:#000;color:#fff;padding:11px 15px;font-weight:700;cursor:pointer}"
            "button.secondary{background:#fff;color:#000;border:1px solid #000}.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:20px}.note{background:var(--soft);padding:15px;margin:18px 0;font-size:14px;line-height:1.5}"
            "@media(max-width:720px){.grid{grid-template-columns:1fr 1fr}.brand{letter-spacing:-4px}th:nth-child(2),td:nth-child(2){display:none}}"
            "</style></head><body><main class='wrap'><div class='brand'>Pedal<b>Bridge</b></div>"
            "<div class='eyebrow'>Live-Training</div><h2>Aktuelle Messwerte</h2>"
            "<div class='grid'><div class='metric'><span>Leistung</span><strong id='power'>–</strong></div>"
            "<div class='metric'><span>Trittfrequenz</span><strong id='cadence'>–</strong></div>"
            "<div class='metric'><span>Geschwindigkeit</span><strong id='speed'>–</strong></div>"
            "<div class='metric'><span>Distanz</span><strong id='distance'>–</strong></div>"
            "<div class='metric'><span>Zeit</span><strong id='duration'>–</strong></div>"
            "<div class='metric'><span>Kalorien</span><strong id='calories'>–</strong></div>"
            "<div class='metric'><span>Verbindungen</span><strong id='links' style='font-size:17px'>–</strong></div></div>"
            "<div class='note' id='detail'>Diagnose wird geladen …</div>"
            "<section class='panel'><div class='eyebrow'>Bluetooth-Geräte</div><h2>Bike auswählen</h2>"
            "<p class='muted'>Bike einschalten und treten. Die Liste wird beim Neuladen aktualisiert. FTMS und ein Signal über −85 dBm sind ideal.</p>"
            "<table><thead><tr><th>Gerät</th><th>Adresse</th><th>Signal</th><th>FTMS</th><th></th></tr></thead><tbody>"),F("<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>PedalBridge</title><style>:root{--ink:#090909;--soft:#f1f1ef;--line:#d8d8d4;--ok:#168348;--bad:#b32929}*{box-sizing:border-box}body{margin:0;background:#fff;color:var(--ink);font-family:Arial,Helvetica,sans-serif}.wrap{max-width:980px;margin:auto;padding:30px 20px 60px}.brand{font-size:clamp(48px,12vw,108px);font-weight:200;letter-spacing:-7px;line-height:.9;margin:4px 0 38px}.brand b{font-weight:900;font-style:italic}.eyebrow{text-transform:uppercase;letter-spacing:.18em;font-size:11px;font-weight:700}h2{font-size:25px;margin:8px 0 18px}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:1px;background:var(--line);border:1px solid var(--line);margin:16px 0 28px}.metric{background:#fff;padding:20px;min-height:112px}.metric span{display:block;color:#666;font-size:12px;margin-bottom:12px;text-transform:uppercase;letter-spacing:.08em}.metric strong{font-size:clamp(25px,5vw,42px);font-variant-numeric:tabular-nums}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#999;margin-right:8px}.dot.ok{background:var(--ok)}.dot.bad{background:var(--bad)}.panel{border-top:3px solid #000;padding-top:16px;margin-top:30px}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:12px 8px;border-bottom:1px solid var(--line);font-size:14px}th{font-size:11px;text-transform:uppercase;letter-spacing:.08em}.muted{color:#666}.yes{font-weight:700}button{border:0;background:#000;color:#fff;padding:11px 15px;font-weight:700;cursor:pointer}button.secondary{background:#fff;color:#000;border:1px solid #000}.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:20px}.note{background:var(--soft);padding:15px;margin:18px 0;font-size:14px;line-height:1.5}@media(max-width:720px){.grid{grid-template-columns:1fr 1fr}.brand{letter-spacing:-4px}th:nth-child(2),td:nth-child(2){display:none}}</style></head><body><main class='wrap'><div class='brand'>Pedal<b>Bridge</b></div><div class='eyebrow'>Live workout</div><h2>Current metrics</h2><div class='grid'><div class='metric'><span>Power</span><strong id='power'>–</strong></div><div class='metric'><span>Cadence</span><strong id='cadence'>–</strong></div><div class='metric'><span>Speed</span><strong id='speed'>–</strong></div><div class='metric'><span>Distance</span><strong id='distance'>–</strong></div><div class='metric'><span>Time</span><strong id='duration'>–</strong></div><div class='metric'><span>Calories</span><strong id='calories'>–</strong></div><div class='metric'><span>Connections</span><strong id='links' style='font-size:17px'>–</strong></div></div><div class='note' id='detail'>Loading diagnostics …</div><section class='panel'><div class='eyebrow'>Bluetooth devices</div><h2>Select bike</h2><p class='muted'>Turn on the bike and start pedaling. Reload to refresh the list. FTMS and a signal above −85 dBm are ideal.</p><table><thead><tr><th>Device</th><th>Address</th><th>Signal</th><th>FTMS</th><th></th></tr></thead><tbody>"));

  if (count == 0) {
    page += webText(F("<tr><td colspan='5'>Noch keine Bluetooth-Geräte gefunden.</td></tr>"),F("<tr><td colspan='5'>No Bluetooth devices found yet.</td></tr>"));
  }
  for (size_t i = 0; i < count; ++i) {
    const ScanEntry& entry = entries[i];
    page += F("<tr><td>");
    page += entry.name[0] ? htmlEscape(entry.name) : String(webText(F("(ohne Namen)"),F("(unnamed)")));
    page += F("</td><td class='muted'>");
    page += entry.address;
    page += F("</td><td>");
    page += String(entry.rssi);
    page += F(" dBm</td><td class='");
    page += entry.advertisesFtms ? webText(F("yes'>ja"),F("yes'>yes")) : webText(F("'>nein"),F("'>no"));
    page += F("</td><td><form method='post' action='/save'>"
              "<input type='hidden' name='address' value='");
    page += entry.address;
    page += F("'><input type='hidden' name='type' value='");
    page += String(entry.addressType);
    page += F("'><input type='hidden' name='name' value=\"");
    page += htmlEscape(entry.name);
    page += webText(F("\"><button type='submit'>Auswählen</button></form></td></tr>"),F("\"><button type='submit'>Select</button></form></td></tr>"));
  }
  page += webText(F("</tbody></table></section><div class='actions'><button class='secondary' onclick='location.reload()'>Geräteliste aktualisieren</button>"
            "<form method='post' action='/session-reset'><button class='secondary' type='submit'>Training zurücksetzen</button></form>"
            "<form method='post' action='/wifi-off'><button type='submit'>WLAN ausschalten &amp; Betrieb starten</button></form></div>"
            "<p class='muted'>Hotspot: "),F("</tbody></table></section><div class='actions'><button class='secondary' onclick='location.reload()'>Refresh device list</button><form method='post' action='/session-reset'><button class='secondary' type='submit'>Reset workout</button></form><form method='post' action='/wifi-off'><button type='submit'>Turn off Wi-Fi &amp; continue riding</button></form></div><p class='muted'>Hotspot: "));
  page += CONFIG_AP_NAME;
  page += webText(F(" · Seite: 192.168.4.1 · Firmware 0.15.1</p>"
            "<script>function state(v){return '<i class=\"dot '+(v?'ok':'bad')+'\"></i>'+(v?'verbunden':'nicht verbunden')}"
            "function clock(v){const m=Math.floor(v/60),q=v%60;return String(m).padStart(2,'0')+':'+String(q).padStart(2,'0')}"
            "async function poll(){try{const r=await fetch('/api/status',{cache:'no-store'}),s=await r.json();"
            "power.textContent=s.power+' W';cadence.textContent=s.cadence.toFixed(1)+' rpm';speed.textContent=s.speed.toFixed(1)+' km/h';distance.textContent=s.distance.toFixed(2)+' km';duration.textContent=clock(s.duration);calories.textContent=s.calories.toFixed(0)+' kcal';"
            "links.innerHTML='Bike '+(s.bike?'✓':'–')+' · Garmin '+(s.garmin?'✓':'–')+' · Phone '+(s.phone?'✓':'–')+'<br>P:'+(s.garminSubscribed?'ja':'nein')+' · S:'+(s.speedSubscribed?'ja':'nein')+' · FTMS:'+(s.ftmsSubscribed?'ja':'nein');"
            "detail.textContent='Bike: '+s.configured+' | Signal: '+(s.rssi===-127?'–':s.rssi+' dBm')+' | FTMS-Pakete: '+s.ftmsPackets+' | Garmin Leistung: '+s.garminPackets+' | Garmin Speed: '+s.speedPackets+' | '+s.control;"
            "}catch(e){detail.textContent='Diagnoseverbindung zum ESP32 unterbrochen.'}}poll();setInterval(poll,1000)</script>"
            "</main></body></html>"),F(" · Page: 192.168.4.1 · Firmware 0.15.1</p><script>function state(v){return '<i class=\"dot '+(v?'ok':'bad')+'\"></i>'+(v?'connected':'disconnected')}function clock(v){const m=Math.floor(v/60),q=v%60;return String(m).padStart(2,'0')+':'+String(q).padStart(2,'0')}async function poll(){try{const r=await fetch('/api/status',{cache:'no-store'}),s=await r.json();power.textContent=s.power+' W';cadence.textContent=s.cadence.toFixed(1)+' rpm';speed.textContent=s.speed.toFixed(1)+' km/h';distance.textContent=s.distance.toFixed(2)+' km';duration.textContent=clock(s.duration);calories.textContent=s.calories.toFixed(0)+' kcal';links.innerHTML='Bike '+(s.bike?'✓':'–')+' · Garmin '+(s.garmin?'✓':'–')+' · Phone '+(s.phone?'✓':'–')+'<br>P:'+(s.garminSubscribed?'yes':'no')+' · S:'+(s.speedSubscribed?'yes':'no')+' · FTMS:'+(s.ftmsSubscribed?'yes':'no');detail.textContent='Bike: '+s.configured+' | Signal: '+(s.rssi===-127?'–':s.rssi+' dBm')+' | FTMS packets: '+s.ftmsPackets+' | Garmin Power: '+s.garminPackets+' | Garmin speed: '+s.speedPackets+' | '+s.control;}catch(e){detail.textContent='Diagnostic connection to the ESP32 interrupted.'}}poll();setInterval(poll,1000)</script></main></body></html>"));
  String languageForm="<form method='post' action='/language' class='actions'><label for='language'>";
  languageForm += gWebEnglish ? "Language" : "Sprache";
  languageForm += "</label><select id='language' name='language' onchange='this.form.submit()'><option value='en'";
  if(gWebEnglish)languageForm += " selected";
  languageForm += ">English</option><option value='de'";
  if(!gWebEnglish)languageForm += " selected";
  languageForm += ">Deutsch</option></select><noscript><button type='submit'>";
  languageForm += gWebEnglish ? "Save language" : "Sprache speichern";
  languageForm += "</button></noscript></form>";
  page.replace("<main class='wrap'>",String("<main class='wrap'>")+languageForm);
  return page;
}

String buildStatusJson() {
  BikeMetrics metrics;
  portENTER_CRITICAL(&gMetricsMux);
  metrics = gMetrics;
  portEXIT_CRITICAL(&gMetricsMux);
  const uint32_t now = millis();
  String lastSeen = "nie";
  if (gLastBikeSeenMs != 0) {
    lastSeen = String((now - gLastBikeSeenMs) / 1000UL) + " s";
  }
  String json;
  const uint32_t sessionDurationSeconds =
      gSessionDurationSeconds;
  json.reserve(520);
  json += F("{\"bike\":"); json += gBikeConnected ? F("true") : F("false");
  json += F(",\"garmin\":"); json += gGarminConnected ? F("true") : F("false");
  json += F(",\"garminSubscribed\":"); json += gGarminSubscribed ? F("true") : F("false");
  json += F(",\"speedSubscribed\":"); json += gGarminSpeedSubscribed ? F("true") : F("false");
  json += F(",\"ftmsSubscribed\":"); json += gGarminFtmsSubscribed ? F("true") : F("false");
  json += F(",\"phone\":"); json += gPhoneConnected ? F("true") : F("false");
  json += F(",\"phoneSubscribed\":"); json += gPhoneSubscribed ? F("true") : F("false");
  json += F(",\"rssi\":"); json += String(gLastBikeRssi);
  json += F(",\"power\":"); json += String(metrics.powerWatts);
  json += F(",\"cadence\":"); json += String(metrics.cadenceRpm, 1);
  json += F(",\"speed\":"); json += String(metrics.speedKph, 2);
  json += F(",\"distance\":"); json += String(gSessionDistanceKm, 3);
  json += F(",\"duration\":"); json += String(sessionDurationSeconds);
  json += F(",\"calories\":"); json += String(gSessionCaloriesKcal, 1);
  json += F(",\"attempts\":"); json += String(gConnectionAttempts);
  json += F(",\"ftmsPackets\":"); json += String(gFtmsPacketCount);
  json += F(",\"garminPackets\":"); json += String(gGarminNotificationCount);
  json += F(",\"speedPackets\":"); json += String(gGarminSpeedNotificationCount);
  json += F(",\"configured\":\""); json += jsonEscape(gConfiguredName + " [" + gConfiguredAddress + "]");
  json += F("\",\"lastSeen\":\""); json += webStatus(lastSeen);
  json += F("\",\"connectable\":"); json += gLastBikeConnectable ? F("true") : F("false");
  json += F(",\"advType\":"); json += String(gLastBikeAdvType);
  json += F(",\"error\":\""); json += jsonEscape(webStatus(gLastBikeError));
  json += F("\",\"control\":\""); json += jsonEscape(webStatus(gFtmsControlStatus));
  json += F("\"}");
  return json;
}

void startConfigPortal() {
  if (gConfigPortalActive) {
    return;
  }

  gConfigPortalActive = true;
  gPreferences.putBool("wifiPortal", true);

  WiFi.mode(WIFI_AP);
  WiFi.softAP(CONFIG_AP_NAME, CONFIG_AP_PASSWORD);
  const IPAddress apAddress = WiFi.softAPIP();
  gDnsServer.start(53, "*", apAddress);

  gWebServer.on("/", HTTP_GET, []() {
    gWebServer.sendHeader("Cache-Control", "no-store");
    gWebServer.send(200, "text/html; charset=utf-8", buildConfigPage());
  });
  gWebServer.on("/api/status", HTTP_GET, []() {
    gWebServer.sendHeader("Cache-Control", "no-store");
    gWebServer.send(200, "application/json; charset=utf-8", buildStatusJson());
  });
  gWebServer.on("/language", HTTP_POST, []() {
    const String language=gWebServer.arg("language");
    if(language!="en" && language!="de") {
      gWebServer.send(400,"text/plain; charset=utf-8",gWebEnglish?"Unsupported language":"Nicht unterstützte Sprache");return;
    }
    gPreferences.putString("language",language);
    gWebEnglish=language=="en";
    gWebServer.sendHeader("Location","/",true);
    gWebServer.send(303,"text/plain; charset=utf-8",gWebEnglish?"Language saved":"Sprache gespeichert");
  });
  gWebServer.on("/save", HTTP_POST, []() {
    const String address = gWebServer.arg("address");
    const String name = gWebServer.arg("name");
    const int addressType = gWebServer.arg("type").toInt();
    if (address.length() != 17) {
      gWebServer.send(400, "text/plain; charset=utf-8", webStatus("Ungültige Bluetooth-Adresse"));
      return;
    }
    gPreferences.putString("bikeAddress", address);
    gPreferences.putString("bikeName", name);
    gPreferences.putUChar("bikeAddrType", static_cast<uint8_t>(addressType));
    gPreferences.putBool("wifiPortal", true);
    gConfiguredAddress = address;
    gConfiguredName = name;
    gConfiguredAddressType = static_cast<uint8_t>(addressType);
    gBikeCandidateAddress = NimBLEAddress(std::string(address.c_str()),
                                         gConfiguredAddressType);
    gBikeCandidateAvailable = true;
    gConnectRequested = true;
    gLastBikeError = "Bike gespeichert; Verbindung wird aufgebaut";
    NimBLEDevice::getScan()->stop();
    gWebServer.sendHeader("Location", "/", true);
    gWebServer.send(303, "text/plain; charset=utf-8", webStatus("Gespeichert"));
  });
  gWebServer.on("/session-reset", HTTP_POST, []() {
    gHistory.tracker.finish(2);
    gHistory.save(true);
    gSessionDurationSeconds = 0;
    gSessionDistanceKm = 0.0f;
    gSessionCaloriesKcal = 0.0f;
    gWebServer.sendHeader("Location", "/", true);
    gWebServer.send(303, "text/plain; charset=utf-8", webStatus("Training zurückgesetzt"));
  });
  gWebServer.on("/wifi-off", HTTP_POST, []() {
    gPreferences.putBool("wifiPortal", false);
    gWebServer.send(200, "text/html; charset=utf-8",
                    String(webText(F("<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width'><style>body{font-family:Arial;margin:40px;max-width:650px}h1{font-size:42px}</style><h1>WLAN wird ausgeschaltet</h1><p>Die Bike-Bridge läuft weiter. Mit BOOT oder zweimal RST lässt sich die Diagnose wieder öffnen.</p>"),F("<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width'><style>body{font-family:Arial;margin:40px;max-width:650px}h1{font-size:42px}</style><h1>Turning off Wi-Fi</h1><p>The bike bridge keeps running. Hold BOOT or press RST twice to reopen diagnostics.</p>"))));
    gWifiShutdownPending = true;
    gWifiShutdownRequestedAtMs = millis();
  });
  gWebServer.onNotFound([]() {
    gWebServer.sendHeader("Location", "http://192.168.4.1/", true);
    gWebServer.send(302, "text/plain", "");
  });
  gWebServer.begin();

  Serial.printf("Setup hotspot active: %s / password: %s / http://%s\n",
                CONFIG_AP_NAME, CONFIG_AP_PASSWORD,
                apAddress.toString().c_str());
}

void parseIndoorBikeData(const uint8_t* data, size_t length) {
  if (data == nullptr || length < 2) {
    return;
  }

  const uint16_t flags = readLe16(data);
  size_t offset = 2;
  BikeMetrics update;
  bool malformed = false;

  // FTMS bit 0 is inverted: zero means instantaneous speed is present.
  if ((flags & (1U << 0)) == 0) {
    if (!consume(offset, 2, length)) {
      malformed = true;
    } else {
      update.speedKph = readLe16(data + offset - 2) / 100.0f;
      update.hasSpeed = true;
    }
  }
  if (!malformed && (flags & (1U << 1))) malformed = !consume(offset, 2, length);  // average speed
  if (!malformed && (flags & (1U << 2))) {
    if (!consume(offset, 2, length)) {
      malformed = true;
    } else {
      update.cadenceRpm = readLe16(data + offset - 2) / 2.0f;
      update.hasCadence = true;
    }
  }
  if (!malformed && (flags & (1U << 3))) malformed = !consume(offset, 2, length);  // average cadence
  if (!malformed && (flags & (1U << 4))) malformed = !consume(offset, 3, length);  // total distance
  if(!malformed && (flags & (1U << 5))) {
    if(!consume(offset,2,length)) malformed=true;
    else {update.resistanceLevel=readLeS16(data+offset-2);update.hasResistance=update.resistanceLevel!=INT16_MAX;}
  }
  if (!malformed && (flags & (1U << 6))) {
    if (!consume(offset, 2, length)) {
      malformed = true;
    } else {
      update.powerWatts = readLeS16(data + offset - 2);
      update.hasPower = true;
    }
  }
  if (!malformed && (flags & (1U << 7))) malformed = !consume(offset, 2, length);  // average power
  if (!malformed && (flags & (1U << 8))) {
    if (!consume(offset, 5, length)) {
      malformed = true;
    } else {
      update.totalEnergyKcal = readLe16(data + offset - 5);
      update.hasEnergy = update.totalEnergyKcal != UINT16_MAX;
    }
  }
  if (!malformed && (flags & (1U << 9))) malformed = !consume(offset, 1, length);  // heart rate
  if (!malformed && (flags & (1U << 10))) malformed = !consume(offset, 1, length); // metabolic equivalent
  if (!malformed && (flags & (1U << 11))) malformed = !consume(offset, 2, length); // elapsed time
  if (!malformed && (flags & (1U << 12))) malformed = !consume(offset, 2, length); // remaining time

  if (malformed) {
    Serial.printf("Malformed FTMS packet: flags=0x%04X, length=%u\n", flags,
                  static_cast<unsigned>(length));
    return;
  }

  update.receivedAtMs = millis();
  portENTER_CRITICAL(&gMetricsMux);
  if (update.hasSpeed) {
    gMetrics.speedKph = update.speedKph;
    gMetrics.hasSpeed = true;
  }
  if (update.hasCadence) {
    gMetrics.cadenceRpm = update.cadenceRpm;
    gMetrics.hasCadence = true;
  }
  if (update.hasPower) {
    gMetrics.powerWatts = update.powerWatts;
    gMetrics.hasPower = true;
  }
  if (flags & (1U << 8)) {
    gMetrics.totalEnergyKcal = update.totalEnergyKcal;
    gMetrics.hasEnergy = update.hasEnergy;
    gMetrics.energyAtMs = update.receivedAtMs;
  }
  if(flags & (1U<<5)){gMetrics.resistanceLevel=update.resistanceLevel;gMetrics.hasResistance=update.hasResistance;gMetrics.resistanceAtMs=update.receivedAtMs;}
  gMetrics.receivedAtMs = update.receivedAtMs;
  portEXIT_CRITICAL(&gMetricsMux);
}

void indoorBikeNotification(NimBLERemoteCharacteristic*, uint8_t* data,
                            size_t length, bool) {
  ++gFtmsPacketCount;
  parseIndoorBikeData(data, length);
}

void ftmsControlPointIndication(NimBLERemoteCharacteristic*, uint8_t* data,
                                size_t length, bool) {
  if (data == nullptr || length < 3 || data[0] != kFtmsResponseOpcode) {
    gFtmsControlStatus = "Ungültige Antwort vom FTMS-Control-Point";
    return;
  }

  const uint8_t requestedOpcode = data[1];
  const uint8_t result = data[2];
  gAwaitingControlResponse = false;
  Serial.printf("FTMS control response: request=0x%02X result=0x%02X\n",
                requestedOpcode, result);
  if (result != kFtmsSuccessResult) {
    gFtmsControlStatus = "Befehl 0x" + String(requestedOpcode, HEX) +
                         " abgelehnt, Ergebnis 0x" + String(result, HEX);
    return;
  }

  if (requestedOpcode == kFtmsRequestControlOpcode) {
    gControlRetryCount = 0;
    gFtmsControlStatus = "Kontrolle übernommen; Start/Resume folgt";
    gPendingControlOpcode = kFtmsStartResumeOpcode;
  } else if (requestedOpcode == kFtmsStartResumeOpcode) {
    gFtmsControlStatus = "Kontrolle aktiv, Training gestartet";
  }
}

void processFtmsControlCommand() {
  if (gAwaitingControlResponse &&
      millis() - gControlCommandSentAtMs >= 2500UL) {
    gAwaitingControlResponse = false;
    if (gLastControlOpcode == kFtmsRequestControlOpcode) {
      if (gControlRetryCount == 0) {
        ++gControlRetryCount;
        gFtmsControlStatus = "Keine Antwort; Request Control wird wiederholt";
        gPendingControlOpcode = kFtmsRequestControlOpcode;
      } else {
        gControlRetryCount = 0;
        gFtmsControlStatus = "Keine Control-Antwort; Start/Resume folgt trotzdem";
        gPendingControlOpcode = kFtmsStartResumeOpcode;
      }
    } else if (gLastControlOpcode == kFtmsStartResumeOpcode) {
      gFtmsControlStatus = "Start/Resume gesendet; Bike antwortet ohne Indication";
    }
  }

  const uint8_t opcode = gPendingControlOpcode;
  if (opcode == kNoPendingControlOpcode || !gBikeConnected ||
      gFtmsControlPoint == nullptr || gBikeClient == nullptr ||
      !gBikeClient->isConnected()) {
    return;
  }

  gPendingControlOpcode = kNoPendingControlOpcode;
  gFtmsControlStatus = opcode == kFtmsRequestControlOpcode
                           ? "Request Control wird gesendet"
                           : "Start/Resume wird gesendet";
  if (!gFtmsControlPoint->writeValue(&opcode, 1, true)) {
    gFtmsControlStatus = "Schreiben von Befehl 0x" + String(opcode, HEX) +
                         " fehlgeschlagen";
    Serial.printf("FTMS control write failed: opcode=0x%02X\n", opcode);
  } else {
    gLastControlOpcode = opcode;
    gControlCommandSentAtMs = millis();
    gAwaitingControlResponse = true;
    gFtmsControlStatus = opcode == kFtmsRequestControlOpcode
                             ? "Request Control gesendet; warte auf Antwort"
                             : "Start/Resume gesendet; warte auf Antwort";
    Serial.printf("FTMS control write sent: opcode=0x%02X\n", opcode);
  }
}

void startBikeScan();

class BikeClientCallbacks final : public NimBLEClientCallbacks {
 public:
  void onConnect(NimBLEClient*) override {
    gLastBikeError = "Mit Bike verbunden; FTMS-Dienste werden gelesen";
    Serial.println("Connected to SMB1; discovering FTMS data...");
  }

  void onDisconnect(NimBLEClient*) override {
    gBikeConnected = false;
    gConnectRequested = false;
    gFtmsControlPoint = nullptr;
    gPendingControlOpcode = kNoPendingControlOpcode;
    gAwaitingControlResponse = false;
    gLastControlOpcode = kNoPendingControlOpcode;
    gFtmsControlStatus = "Bike-Verbindung getrennt";
    gLastBikeError = "Bike hat die Bluetooth-Verbindung getrennt";
    Serial.println("SMB1 disconnected; scanning again.");
  }
};

class GarminServerCallbacks final : public NimBLEServerCallbacks {
 public:
  void onConnect(NimBLEServer* server, ble_gap_conn_desc* desc) override {
    Serial.printf("BLE client connected: handle=%u (server clients=%u).\n",
                  desc->conn_handle,
                  static_cast<unsigned>(server->getConnectedCount()));
    Serial.printf("BLE peer=%s encrypted=%u bonded=%u\n",
        NimBLEAddress(desc->peer_ota_addr).toString().c_str(),
        desc->sec_state.encrypted, desc->sec_state.bonded);
    server->updateConnParams(desc->conn_handle, 24, 48, 0, 600);
    // A BLE peripheral normally stops advertising after a connection. Start it
    // again so Garmin and the phone can be connected at the same time.
    // Reserve one of the four controller slots for our outgoing bike link.
    if (server->getConnectedCount() < 3) {
      NimBLEDevice::startAdvertising();
    }
  }

  void onAuthenticationComplete(ble_gap_conn_desc* desc) override {
    Serial.printf("BLE authentication: handle=%u encrypted=%u bonded=%u savedBonds=%d\n",
        desc->conn_handle, desc->sec_state.encrypted, desc->sec_state.bonded,
        NimBLEDevice::getNumBonds());
  }

  void onDisconnect(NimBLEServer*, ble_gap_conn_desc* desc) override {
    if (desc->conn_handle == gGarminConnectionHandle) {
      gGarminConnectionHandle = 0xffff;
      gGarminConnected = false;
      gGarminSubscribed = false;
      gGarminSpeedSubscribed = false;
      gGarminFtmsSubscribed = false;
      Serial.println("Garmin disconnected.");
    }
    if (desc->conn_handle == gPhoneConnectionHandle) {
      gPhoneConnectionHandle = 0xffff;
      gPhoneConnected = false;
      gPhoneSubscribed = false;
      Serial.println("Phone disconnected.");
    }
    NimBLEDevice::startAdvertising();
  }
};

class PowerMeasurementCallbacks final : public NimBLECharacteristicCallbacks {
 public:
  void onSubscribe(NimBLECharacteristic*, ble_gap_conn_desc* desc,
                   uint16_t subValue) override {
    gGarminSubscribed = (subValue & 0x01U) != 0;
    if (gGarminSubscribed) {
      gGarminConnected = true;
      gGarminConnectionHandle = desc->conn_handle;
    } else {
      gGarminConnected = gGarminSpeedSubscribed || gGarminFtmsSubscribed;
    }
    Serial.printf("Garmin Cycling Power subscription changed: 0x%04X (%s)\n",
                  subValue, gGarminSubscribed ? "notifications on" : "off");
  }
};

class SpeedMeasurementCallbacks final : public NimBLECharacteristicCallbacks {
 public:
  void onSubscribe(NimBLECharacteristic*, ble_gap_conn_desc* desc,
                   uint16_t subValue) override {
    gGarminSpeedSubscribed = (subValue & 0x01U) != 0;
    if (gGarminSpeedSubscribed) {
      gGarminConnected = true;
      gGarminConnectionHandle = desc->conn_handle;
    } else {
      gGarminConnected = gGarminSubscribed || gGarminFtmsSubscribed;
    }
    Serial.printf("Garmin CSC subscription changed: 0x%04X (%s)\n",
                  subValue,
                  gGarminSpeedSubscribed ? "notifications on" : "off");
  }
};

class FtmsMirrorCallbacks final : public NimBLECharacteristicCallbacks {
 public:
  void onSubscribe(NimBLECharacteristic*, ble_gap_conn_desc* desc,
                   uint16_t subValue) override {
    gGarminFtmsSubscribed = (subValue & 0x01U) != 0;
    if (gGarminFtmsSubscribed) {
      gGarminConnected = true;
      gGarminConnectionHandle = desc->conn_handle;
    } else {
      gGarminConnected = gGarminSubscribed || gGarminSpeedSubscribed;
    }
    Serial.printf("Garmin FTMS Indoor Bike subscription: 0x%04X (%s)\n",
                  subValue, gGarminFtmsSubscribed ? "on" : "off");
  }
};

class FtmsMirrorControlCallbacks final : public NimBLECharacteristicCallbacks {
 public:
  void onWrite(NimBLECharacteristic* characteristic,
               ble_gap_conn_desc* desc) override {
    const std::string value = characteristic->getValue();
    if (value.empty()) return;
    const uint8_t opcode = static_cast<uint8_t>(value[0]);
    // A controllable FTMS trainer must implement the target operations it
    // advertises. The SMB1 does not expose a documented mapping for these
    // targets, so the bridge accepts them locally while continuing to relay
    // the physical bike's measured output.
    const bool knownOpcode = opcode == 0x00 || opcode == 0x01 ||
                             opcode == 0x04 || opcode == 0x05 ||
                             opcode == 0x07 || opcode == 0x08 ||
                             opcode == 0x11;
    const bool validLength =
        (opcode == 0x00 && value.size() == 1) ||
        (opcode == 0x01 && value.size() == 1) ||
        (opcode == 0x04 && value.size() == 3) ||
        (opcode == 0x05 && value.size() == 3) ||
        (opcode == 0x07 && value.size() == 1) ||
        (opcode == 0x08 && value.size() == 2) ||
        (opcode == 0x11 && value.size() == 7);
    const uint8_t result = !knownOpcode
                               ? 0x02
                               : (validLength ? kFtmsSuccessResult
                                              : kFtmsInvalidParameterResult);
    const uint8_t response[] = {kFtmsResponseOpcode, opcode, result};
    characteristic->setValue(response, sizeof(response));
    characteristic->indicate();

    if (result == kFtmsSuccessResult && gFtmsMirrorStatus != nullptr) {
      uint8_t status[7] = {};
      size_t statusLength = 1;
      switch (opcode) {
        case 0x01:
          status[0] = 0x01;  // Reset.
          break;
        case 0x04:
          status[0] = 0x07;  // Target resistance changed.
          status[1] = static_cast<uint8_t>(value[1]);
          status[2] = static_cast<uint8_t>(value[2]);
          statusLength = 3;
          break;
        case 0x05:
          status[0] = 0x08;  // Target power changed.
          status[1] = static_cast<uint8_t>(value[1]);
          status[2] = static_cast<uint8_t>(value[2]);
          statusLength = 3;
          break;
        case 0x07:
          status[0] = 0x04;  // Started or resumed.
          break;
        case 0x08:
          status[0] = 0x02;  // Stopped or paused.
          status[1] = static_cast<uint8_t>(value[1]);
          statusLength = 2;
          break;
        case 0x11:
          status[0] = 0x12;  // Simulation parameters changed.
          for (size_t i = 1; i < 7; ++i) {
            status[i] = static_cast<uint8_t>(value[i]);
          }
          statusLength = 7;
          break;
        default:
          statusLength = 0;  // Request Control has no status event.
          break;
      }
      if (statusLength != 0) {
        gFtmsMirrorStatus->setValue(status, statusLength);
        gFtmsMirrorStatus->notify();
      }
    }
    gGarminConnected = true;
    gGarminConnectionHandle = desc->conn_handle;
    Serial.printf("Garmin FTMS control opcode 0x%02X -> result 0x%02X.\n",
                  opcode, result);
  }
};

class PhoneLiveDataCallbacks final : public NimBLECharacteristicCallbacks {
 public:
  void onSubscribe(NimBLECharacteristic*, ble_gap_conn_desc* desc,
                   uint16_t subValue) override {
    gPhoneSubscribed = (subValue & 0x01U) != 0;
    gPhoneConnected = gPhoneSubscribed;
    if (gPhoneSubscribed) {
      gPhoneConnectionHandle = desc->conn_handle;
    }
    Serial.printf("Phone live-data subscription: 0x%04X (%s)\n", subValue,
                  gPhoneSubscribed ? "on" : "off");
  }
};

class PhoneControlCallbacks final : public NimBLECharacteristicCallbacks {
 public:
  void onWrite(NimBLECharacteristic* characteristic,
               ble_gap_conn_desc* desc) override {
    const std::string value = characteristic->getValue();
    if (!value.empty() && static_cast<uint8_t>(value[0]) == 0x01) {
      portENTER_CRITICAL(&gHistoryCommandMux);
      gHistoryFinishRequested = true;
      portEXIT_CRITICAL(&gHistoryCommandMux);
      gPhoneConnected = true;
      gPhoneConnectionHandle = desc->conn_handle;
      Serial.println("Training session reset from phone.");
    } else if (!value.empty() && static_cast<uint8_t>(value[0]) == 0x02) {
      gWifiDisableFromBleRequested = true;
      gPhoneConnected = true;
      gPhoneConnectionHandle = desc->conn_handle;
      Serial.println("Wi-Fi shutdown requested from phone.");
    } else if (value.size()==7 && static_cast<uint8_t>(value[0])==0x10) {
      portENTER_CRITICAL(&gHistoryCommandMux);
      gHistoryTransfer = uint8_t(value[1]) | (uint16_t(uint8_t(value[2]))<<8);
      gHistoryOffset = uint8_t(value[3]) | (uint32_t(uint8_t(value[4]))<<8) |
          (uint32_t(uint8_t(value[5]))<<16) | (uint32_t(uint8_t(value[6]))<<24);
      gHistoryPageRequested = true;
      portEXIT_CRITICAL(&gHistoryCommandMux);
    } else if ((value.size()==11 && (uint8_t(value[0])==0x11 || uint8_t(value[0])==0x14 || uint8_t(value[0])==0x15)) ||
               (value.size()==13 && uint8_t(value[0])==0x12) ||
               (value.size()==5 && uint8_t(value[0])==0x20) ||
               (value.size()==1 && (uint8_t(value[0])==0x21 || uint8_t(value[0])==0x16))) {
      portENTER_CRITICAL(&gHistoryCommandMux);
      memcpy(gArchiveCommand,value.data(),value.size()); gArchiveCommandSize=value.size();
      portEXIT_CRITICAL(&gHistoryCommandMux);
    } else if (value.size()==5 && static_cast<uint8_t>(value[0])==0x03) {
      const uint32_t epoch = uint8_t(value[1]) | (uint32_t(uint8_t(value[2]))<<8) |
          (uint32_t(uint8_t(value[3]))<<16) | (uint32_t(uint8_t(value[4]))<<24);
      if(epoch>=1704067200UL && epoch<4102444800UL) {
        portENTER_CRITICAL(&gHistoryCommandMux);
        gHistoryTime = epoch;
        portEXIT_CRITICAL(&gHistoryCommandMux);
      }
    }
  }
};

class BikeScanCallbacks final : public NimBLEAdvertisedDeviceCallbacks {
 public:
  void onResult(NimBLEAdvertisedDevice* device) override {
    const std::string name = device->getName();
    const std::string filter = BIKE_NAME_FILTER;
    const bool advertisesFtms =
        device->isAdvertisingService(NimBLEUUID(kFtmsServiceUuid));
    const bool nameMatches =
        !filter.empty() &&
        lowerCopy(name).find(lowerCopy(filter)) != std::string::npos;
    const std::string address = device->getAddress().toString();
    const bool configuredAddressMatches =
        !gConfiguredAddress.isEmpty() &&
        lowerCopy(address) == lowerCopy(gConfiguredAddress.c_str());
    const bool configuredNameMatches =
        !gConfiguredName.isEmpty() && !name.empty() &&
        lowerCopy(name) == lowerCopy(gConfiguredName.c_str());

    recordScanEntry(device, advertisesFtms);

    if (configuredAddressMatches || configuredNameMatches || nameMatches) {
      gLastBikeRssi = device->getRSSI();
      gLastBikeConnectable = isConnectableAdvertisement(device);
      gLastBikeAdvType = device->getAdvType();
      gLastBikeSeenMs = millis();
    }

    // Some fitness consoles expose FTMS only after a GATT connection and do
    // not include service 0x1826 in their advertising packet. Match SMB1 by
    // either its advertised name or the standard FTMS service.
    if (!name.empty() || advertisesFtms) {
      Serial.printf("BLE seen: '%s' [%s], RSSI=%d, FTMS=%s, connectable=%s, advType=%u\n",
                    name.c_str(), device->getAddress().toString().c_str(),
                    device->getRSSI(), advertisesFtms ? "yes" : "no",
                    isConnectableAdvertisement(device) ? "yes" : "no",
                    device->getAdvType());
    }

    if (gConfigPortalActive && gConfiguredAddress.isEmpty()) {
      return;
    }

    const bool configuredDevice =
        configuredAddressMatches || configuredNameMatches;
    if (!gConfiguredAddress.isEmpty() && !configuredDevice) {
      return;
    }
    if (gConfiguredAddress.isEmpty() && !nameMatches && !advertisesFtms) {
      return;
    }

    Serial.printf("Bike candidate found: '%s' [%s] (saved=%s, name=%s, FTMS=%s)\n",
                  name.c_str(), device->getAddress().toString().c_str(),
                  configuredDevice ? "match" : "no match",
                  nameMatches ? "match" : "no match",
                  advertisesFtms ? "advertised" : "not advertised");
    // Copy the address. NimBLE owns the advertised-device object and may
    // invalidate it as soon as scanning stops.
    gBikeCandidateAddress = device->getAddress();
    gBikeCandidateAvailable = true;
    gConnectRequested = true;
    NimBLEDevice::getScan()->stop();
  }
};

BikeClientCallbacks gBikeClientCallbacks;
GarminServerCallbacks gGarminServerCallbacks;
PowerMeasurementCallbacks gPowerMeasurementCallbacks;
SpeedMeasurementCallbacks gSpeedMeasurementCallbacks;
FtmsMirrorCallbacks gFtmsMirrorCallbacks;
FtmsMirrorControlCallbacks gFtmsMirrorControlCallbacks;
PhoneLiveDataCallbacks gPhoneLiveDataCallbacks;
PhoneControlCallbacks gPhoneControlCallbacks;
BikeScanCallbacks gBikeScanCallbacks;

void scanEnded(NimBLEScanResults) {
  gScanFinished = true;
}

void startBikeScan() {
  if (gBikeConnected || gConnectRequested || NimBLEDevice::getScan()->isScanning()) {
    return;
  }

  gScanFinished = false;
  gLastScanStartMs = millis();
  Serial.printf("Scanning for FTMS bike named '%s'...\n", BIKE_NAME_FILTER);
  NimBLEDevice::getScan()->start(SCAN_DURATION_SECONDS, scanEnded, false);
}

bool connectToBike() {
  if (!gBikeCandidateAvailable) {
    return false;
  }

  if (gBikeClient == nullptr) {
    gBikeClient = NimBLEDevice::createClient();
    gBikeClient->setClientCallbacks(&gBikeClientCallbacks, false);
    gBikeClient->setConnectTimeout(20);
    // One-second FTMS data does not need aggressive radio scheduling. These
    // intervals leave airtime for Garmin and the companion phone.
    gBikeClient->setConnectionParams(24, 48, 0, 600);
  }

  ++gConnectionAttempts;
  gLastBikeError = "Verbindungsversuch läuft";
  Serial.printf("Connecting to saved bike address %s (type %u)...\n",
                gBikeCandidateAddress.toString().c_str(),
                gBikeCandidateAddress.getType());
  if (!gBikeClient->connect(gBikeCandidateAddress)) {
    const int error = gBikeClient->getLastError();
    if (error == 13) {
      gLastBikeError = "Timeout (13): Signal zu schwach, Bike belegt oder nicht mehr wach";
    } else if (error == 574) {
      gLastBikeError = "HCI 0x3E: Bike antwortet nicht auf den Verbindungsaufbau";
    } else {
      gLastBikeError = "BLE-Verbindungsfehler " + String(error);
    }
    Serial.printf("Could not connect to SMB1 (error %d).\n", error);
    return false;
  }

  NimBLERemoteService* ftms = gBikeClient->getService(kFtmsServiceUuid);
  if (ftms == nullptr) {
    gLastBikeError = gBikeClient->isConnected()
                         ? "Verbunden, aber FTMS-Service 1826 fehlt"
                         : "Bike trennte die Verbindung während der Diensterkennung";
    Serial.println("SMB1 connection has no FTMS service.");
    gBikeClient->disconnect();
    return false;
  }

  NimBLERemoteCharacteristic* indoorBike =
      ftms->getCharacteristic(kIndoorBikeDataUuid);
  if (indoorBike == nullptr || !indoorBike->canNotify()) {
    gLastBikeError = "Indoor-Bike-Daten 2AD2 fehlen oder senden keine Notifications";
    Serial.println("SMB1 has no notifiable Indoor Bike Data characteristic.");
    gBikeClient->disconnect();
    return false;
  }

  if (!indoorBike->subscribe(true, indoorBikeNotification, true)) {
    gLastBikeError = "FTMS-Abonnement auf 2AD2 fehlgeschlagen";
    Serial.println("Could not subscribe to SMB1 Indoor Bike Data.");
    gBikeClient->disconnect();
    return false;
  }

  gBikeConnected = true;
  gFtmsControlPoint = ftms->getCharacteristic(kFitnessMachineControlPointUuid);
  if (gFtmsControlPoint == nullptr) {
    gFtmsControlStatus = "Control-Point 2AD9 wird vom Bike nicht angeboten";
  } else if (!gFtmsControlPoint->canIndicate() ||
             (!gFtmsControlPoint->canWrite() &&
              !gFtmsControlPoint->canWriteNoResponse())) {
    gFtmsControlStatus = "Control-Point 2AD9 hat unerwartete Eigenschaften";
  } else if (!gFtmsControlPoint->subscribe(false,
                                            ftmsControlPointIndication,
                                            true)) {
    gFtmsControlStatus = "Control-Point-Indications konnten nicht aktiviert werden";
  } else {
    gFtmsControlStatus = "Control-Point bereit; Request Control folgt";
    gPendingControlOpcode = kFtmsRequestControlOpcode;
  }
  gLastBikeError = "FTMS-Datenstrom erfolgreich abonniert";
  gHistoryDevice = gHistory.tracker.device(gBikeCandidateAddress.toString().c_str(),
      gConfiguredName.isEmpty() ? "FTMS Bike" : gConfiguredName.c_str());
  if(gHistoryDevice<0) gHistory.error="Gerätelimit erreicht (16 Bikes)";
  gHistory.save(true);
  Serial.println("SMB1 FTMS stream subscribed successfully.");
  return true;
}

void createGarminPowerSensor() {
  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(&gGarminServerCallbacks);

  NimBLEService* cyclingPower = server->createService(kCyclingPowerServiceUuid);
  gPowerMeasurement = cyclingPower->createCharacteristic(
      kCyclingPowerMeasurementUuid, NIMBLE_PROPERTY::NOTIFY);
  gPowerMeasurement->setCallbacks(&gPowerMeasurementCallbacks);

  NimBLECharacteristic* feature = cyclingPower->createCharacteristic(
      kCyclingPowerFeatureUuid, NIMBLE_PROPERTY::READ);
  const uint32_t featureBits = kCpsWheelRevolutionSupported |
                               kCpsCrankRevolutionSupported;
  feature->setValue(reinterpret_cast<const uint8_t*>(&featureBits),
                    sizeof(featureBits));

  NimBLECharacteristic* location = cyclingPower->createCharacteristic(
      kSensorLocationUuid, NIMBLE_PROPERTY::READ);
  const uint8_t sensorLocationOther = 0;
  location->setValue(&sensorLocationOther, sizeof(sensorLocationOther));
  cyclingPower->start();

  NimBLEService* cyclingSpeedCadence =
      server->createService(kCyclingSpeedCadenceServiceUuid);
  gCscMeasurement = cyclingSpeedCadence->createCharacteristic(
      kCscMeasurementUuid, NIMBLE_PROPERTY::NOTIFY);
  gCscMeasurement->setCallbacks(&gSpeedMeasurementCallbacks);
  NimBLECharacteristic* cscFeature = cyclingSpeedCadence->createCharacteristic(
      kCscFeatureUuid, NIMBLE_PROPERTY::READ);
  const uint16_t cscWheelRevolutionSupported = 1U << 0;
  cscFeature->setValue(
      reinterpret_cast<const uint8_t*>(&cscWheelRevolutionSupported),
      sizeof(cscWheelRevolutionSupported));
  NimBLECharacteristic* cscLocation =
      cyclingSpeedCadence->createCharacteristic(kSensorLocationUuid,
                                                NIMBLE_PROPERTY::READ);
  const uint8_t sensorLocationRearWheel = 12;
  cscLocation->setValue(&sensorLocationRearWheel,
                        sizeof(sensorLocationRearWheel));
  cyclingSpeedCadence->start();

  NimBLEService* ftmsMirror = server->createService(kFtmsServiceUuid);
  NimBLECharacteristic* ftmsFeature = ftmsMirror->createCharacteristic(
      kFitnessMachineFeatureUuid, NIMBLE_PROPERTY::READ);
  // Cadence, total distance, resistance, expended energy, elapsed time, power.
  const uint32_t ftmsMachineFeatures = (1UL << 1) | (1UL << 2) |
                                       (1UL << 7) | (1UL << 9) |
                                       (1UL << 12) | (1UL << 14);
  // Resistance, power and indoor-bike simulation target settings make this a
  // controllable FTMS trainer rather than a read-only fitness machine.
  const uint32_t ftmsTargetFeatures =
      (1UL << 2) | (1UL << 3) | (1UL << 13);
  uint8_t ftmsFeaturePacket[8];
  size_t ftmsFeatureOffset = 0;
  appendLe32(ftmsFeaturePacket, ftmsFeatureOffset, ftmsMachineFeatures);
  appendLe32(ftmsFeaturePacket, ftmsFeatureOffset, ftmsTargetFeatures);
  ftmsFeature->setValue(ftmsFeaturePacket, ftmsFeatureOffset);

  NimBLECharacteristic* resistanceRange = ftmsMirror->createCharacteristic(
      kSupportedResistanceRangeUuid, NIMBLE_PROPERTY::READ);
  // Minimum 0.0, maximum 100.0 and increment 0.1 (all in 0.1 units).
  const uint8_t resistanceRangeValue[] = {0x00, 0x00, 0xe8,
                                          0x03, 0x01, 0x00};
  resistanceRange->setValue(resistanceRangeValue,
                            sizeof(resistanceRangeValue));

  NimBLECharacteristic* powerRange = ftmsMirror->createCharacteristic(
      kSupportedPowerRangeUuid, NIMBLE_PROPERTY::READ);
  // Minimum 0 W, maximum 2000 W and increment 1 W.
  const uint8_t powerRangeValue[] = {0x00, 0x00, 0xd0,
                                     0x07, 0x01, 0x00};
  powerRange->setValue(powerRangeValue, sizeof(powerRangeValue));

  gFtmsMirrorMeasurement = ftmsMirror->createCharacteristic(
      kIndoorBikeDataUuid, NIMBLE_PROPERTY::NOTIFY);
  gFtmsMirrorMeasurement->setCallbacks(&gFtmsMirrorCallbacks);
  gFtmsMirrorControlPoint = ftmsMirror->createCharacteristic(
      kFitnessMachineControlPointUuid,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::INDICATE);
  gFtmsMirrorControlPoint->setCallbacks(&gFtmsMirrorControlCallbacks);
  gFtmsMirrorStatus = ftmsMirror->createCharacteristic(
      kFitnessMachineStatusUuid, NIMBLE_PROPERTY::NOTIFY);
  ftmsMirror->start();

  NimBLEService* phoneService = server->createService(kPhoneServiceUuid);
  gPhoneLiveData = phoneService->createCharacteristic(
      kPhoneLiveDataUuid, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  gPhoneLiveData->setCallbacks(&gPhoneLiveDataCallbacks);
  NimBLECharacteristic* phoneControl = phoneService->createCharacteristic(
      kPhoneControlUuid, NIMBLE_PROPERTY::WRITE);
  phoneControl->setCallbacks(&gPhoneControlCallbacks);
  gHistoryData = phoneService->createCharacteristic(kPhoneHistoryUuid, NIMBLE_PROPERTY::READ);
  const uint8_t emptyHistory[10] = {};
  gHistoryData->setValue(emptyHistory,sizeof(emptyHistory));
  gManagementData=phoneService->createCharacteristic(
      "9f6c1004-5a7b-4fd0-9a9f-6c30a7e63110",NIMBLE_PROPERTY::READ);
  phoneService->start();

  NimBLEService* deviceInfo = server->createService(kDeviceInformationServiceUuid);
  deviceInfo->createCharacteristic(kManufacturerNameUuid, NIMBLE_PROPERTY::READ)
      ->setValue("OpenAI / local build");
  deviceInfo->createCharacteristic(kModelNumberUuid, NIMBLE_PROPERTY::READ)
      ->setValue("PedalBridge-FTMS-CPS-Bridge");
  deviceInfo->createCharacteristic(kFirmwareRevisionUuid, NIMBLE_PROPERTY::READ)
      ->setValue("0.15.1");
  deviceInfo->start();

  NimBLEAdvertising* advertising = NimBLEDevice::getAdvertising();
  // Explicit 29-byte payload: flags (3), UUID list (6), service data (7),
  // full name (13). FTMS Service Data requires a flags byte BEFORE the
  // uint16 machine type: available=1, indoor bike=0x0020. Versions 0.9.2/3
  // omitted that flags byte, producing a malformed discovery record.
  NimBLEAdvertisementData advertisementData;
  advertisementData.setFlags(0x06);
  advertisementData.setCompleteServices16(
      {NimBLEUUID(kFtmsServiceUuid), NimBLEUUID(kCyclingPowerServiceUuid)});
  advertisementData.setServiceData(NimBLEUUID(kFtmsServiceUuid),
                                   std::string("\x01\x20\x00", 3));
  advertisementData.setName("PedalBridge");
  const std::string payload = advertisementData.getPayload();
  Serial.printf("BLE advertising payload (%u bytes):", unsigned(payload.size()));
  for (uint8_t byte : payload) Serial.printf(" %02X", byte);
  Serial.println();
  advertising->setAdvertisementData(advertisementData);
  advertising->setScanResponse(false);
  advertising->start();

  Serial.printf("Advertising '%s' as Cycling Power, CSC, and FTMS trainer.\n",
                GARMIN_SENSOR_NAME);
}

BikeMetrics snapshotMetrics() {
  BikeMetrics snapshot;
  portENTER_CRITICAL(&gMetricsMux);
  snapshot = gMetrics;
  portEXIT_CRITICAL(&gMetricsMux);
  return snapshot;
}

void publishPowerMeasurement(uint32_t nowMs) {
  const BikeMetrics metrics = snapshotMetrics();
  const bool fresh = metrics.receivedAtMs != 0 &&
                     nowMs - metrics.receivedAtMs <= BIKE_DATA_TIMEOUT_MS;

  const float cadence = fresh && metrics.hasCadence
                            ? std::max(0.0f, metrics.cadenceRpm)
                            : 0.0f;
  const float speed = fresh && metrics.hasSpeed
                          ? std::max(0.0f, metrics.speedKph)
                          : 0.0f;
  const int16_t power = fresh && metrics.hasPower
                            ? std::max<int16_t>(0, metrics.powerWatts)
                            : 0;

  history::Sample historySample;
  historySample.fresh = fresh && gBikeConnected;
  historySample.power = power; historySample.cadence = cadence; historySample.speed = speed;
  historySample.hasPower = metrics.hasPower; historySample.hasCadence = metrics.hasCadence;
  historySample.hasSpeed = metrics.hasSpeed;
  historySample.hasEnergy = fresh && metrics.hasEnergy && nowMs-metrics.energyAtMs<=BIKE_DATA_TIMEOUT_MS;
  historySample.energy = metrics.totalEnergyKcal;
  const bool resistanceFresh=fresh && metrics.hasResistance && nowMs-metrics.resistanceAtMs<=BIKE_DATA_TIMEOUT_MS;
  historySample.hasResistance=resistanceFresh;historySample.resistance=metrics.resistanceLevel;
  const bool historyWasActive = gHistory.tracker.state.active;
  gHistory.tracker.tick(gHistoryDevice,historySample,nowMs);
  gHistory.record(historySample,nowMs);
  gHistory.save(historyWasActive && !gHistory.tracker.state.active);

  // Live display and archive share one lifecycle and the same integrated totals.
  const auto& tracker = gHistory.tracker;
  gSessionDurationSeconds = tracker.state.active ? tracker.elapsedMs()/1000 : 0;
  gSessionDistanceKm = tracker.state.active ? tracker.state.current.stats.km : 0;
  gSessionCaloriesKcal = tracker.state.active ? tracker.state.current.stats.kcal : 0;

  const float elapsedSeconds = gLastMeasurementMs == 0
                                   ? GARMIN_UPDATE_INTERVAL_MS / 1000.0f
                                   : (nowMs - gLastMeasurementMs) / 1000.0f;

  gCrankRemainder += cadence * elapsedSeconds / 60.0f;
  const uint16_t newCrankRevolutions = static_cast<uint16_t>(gCrankRemainder);
  if (newCrankRevolutions > 0) {
    gCumulativeCrankRevolutions += newCrankRevolutions;
    gCrankRemainder -= newCrankRevolutions;
    const float crankRevolutionsPerSecond = cadence / 60.0f;
    const uint32_t lastEventMs = crankRevolutionsPerSecond > 0.0f
                                     ? nowMs - static_cast<uint32_t>(
                                                   1000.0f * gCrankRemainder /
                                                   crankRevolutionsPerSecond)
                                     : nowMs;
    gLastCrankEventTime =
        static_cast<uint16_t>((lastEventMs * 1024ULL) / 1000ULL);
  }

  const float circumferenceMeters = VIRTUAL_WHEEL_CIRCUMFERENCE_MM / 1000.0f;
  if (circumferenceMeters > 0.0f) {
    gWheelRemainder += (speed / 3.6f) * elapsedSeconds / circumferenceMeters;
  }
  const uint32_t newWheelRevolutions = static_cast<uint32_t>(gWheelRemainder);
  if (newWheelRevolutions > 0) {
    gCumulativeWheelRevolutions += newWheelRevolutions;
    gWheelRemainder -= newWheelRevolutions;
    const float wheelRevolutionsPerSecond =
        circumferenceMeters > 0.0f
            ? (speed / 3.6f) / circumferenceMeters
            : 0.0f;
    const uint32_t lastEventMs = wheelRevolutionsPerSecond > 0.0f
                                     ? nowMs - static_cast<uint32_t>(
                                                   1000.0f * gWheelRemainder /
                                                   wheelRevolutionsPerSecond)
                                     : nowMs;
    gLastWheelEventTime =
        static_cast<uint16_t>((lastEventMs * 2048ULL) / 1000ULL);
    gCscLastWheelEventTime =
        static_cast<uint16_t>((lastEventMs * 1024ULL) / 1000ULL);
  }

  uint8_t packet[14];
  size_t offset = 0;
  appendLe16(packet, offset,
             kCpsWheelRevolutionPresent | kCpsCrankRevolutionPresent);
  appendLe16(packet, offset, static_cast<uint16_t>(power));
  appendLe32(packet, offset, gCumulativeWheelRevolutions);
  appendLe16(packet, offset, gLastWheelEventTime);
  appendLe16(packet, offset, gCumulativeCrankRevolutions);
  appendLe16(packet, offset, gLastCrankEventTime);

  gPowerMeasurement->setValue(packet, offset);
  if (gGarminConnected && gGarminSubscribed) {
    gPowerMeasurement->notify();
    ++gGarminNotificationCount;
  }

  uint8_t cscPacket[7];
  size_t cscOffset = 0;
  cscPacket[cscOffset++] = 1U << 0;  // Wheel revolution data present.
  appendLe32(cscPacket, cscOffset, gCumulativeWheelRevolutions);
  appendLe16(cscPacket, cscOffset, gCscLastWheelEventTime);
  gCscMeasurement->setValue(cscPacket, cscOffset);
  if (gGarminConnected && gGarminSpeedSubscribed) {
    gCscMeasurement->notify();
    ++gGarminSpeedNotificationCount;
  }

  const uint32_t sessionSeconds = gSessionDurationSeconds;

  // Compact FTMS Indoor Bike Data record. Keeping it at 18 bytes works with
  // the default 23-byte BLE MTU and always supplies distance even when the
  // SMB1 omits its optional Total Distance field.
  uint8_t ftmsPacket[18];
  size_t ftmsOffset = 0;
  const uint16_t ftmsFlags = (1U << 2) | (1U << 4) | (1U << 6) |
                             (1U << 8) | (1U << 11);
  appendLe16(ftmsPacket, ftmsOffset, ftmsFlags);
  appendLe16(ftmsPacket, ftmsOffset,
             static_cast<uint16_t>(std::round(speed * 100.0f)));
  appendLe16(ftmsPacket, ftmsOffset,
             static_cast<uint16_t>(std::round(cadence * 2.0f)));
  appendLe24(ftmsPacket, ftmsOffset,
             static_cast<uint32_t>(std::round(gSessionDistanceKm * 1000.0f)));
  appendLe16(ftmsPacket, ftmsOffset, static_cast<uint16_t>(power));
  appendLe16(ftmsPacket, ftmsOffset,
             static_cast<uint16_t>(std::round(gSessionCaloriesKcal)));
  const float caloriesPerHour = power * 3600.0f / (4184.0f * 0.24f);
  appendLe16(ftmsPacket, ftmsOffset,
             static_cast<uint16_t>(std::round(caloriesPerHour)));
  ftmsPacket[ftmsOffset++] = static_cast<uint8_t>(std::min(
      255.0f, std::round(caloriesPerHour / 60.0f)));
  appendLe16(ftmsPacket, ftmsOffset,
             static_cast<uint16_t>(sessionSeconds & 0xffffU));
  gFtmsMirrorMeasurement->setValue(ftmsPacket, ftmsOffset);
  if (gGarminFtmsSubscribed) {
    gFtmsMirrorMeasurement->notify();
    ++gGarminFtmsNotificationCount;
  }

  // Versioned 20-byte companion protocol. All multi-byte values are little
  // endian: flags, watts, cadence*10, km/h*100, distance*100000, seconds,
  // calories*10, signed resistance level (unitless, resolution 1).
  uint8_t phonePacket[20];
  size_t phoneOffset = 0;
  phonePacket[phoneOffset++] = 3;
  phonePacket[phoneOffset++] = (fresh ? 1U : 0U) |
                              (gBikeConnected ? 1U << 1 : 0U) |
                              ((gGarminSubscribed || gGarminFtmsSubscribed)
                                   ? 1U << 2
                                   : 0U) |
                              (gGarminFtmsSubscribed ? 1U << 3 : 0U) | (resistanceFresh?1U<<4:0U);
  appendLe16(phonePacket, phoneOffset, static_cast<uint16_t>(power));
  appendLe16(phonePacket, phoneOffset,
             static_cast<uint16_t>(std::round(cadence * 10.0f)));
  appendLe16(phonePacket, phoneOffset,
             static_cast<uint16_t>(std::round(speed * 100.0f)));
  appendLe32(phonePacket, phoneOffset,
             static_cast<uint32_t>(std::round(gSessionDistanceKm * 100000.0f)));
  appendLe32(phonePacket, phoneOffset, sessionSeconds);
  appendLe16(phonePacket, phoneOffset,
             static_cast<uint16_t>(std::round(gSessionCaloriesKcal * 10.0f)));
  appendLe16(phonePacket,phoneOffset,static_cast<uint16_t>(resistanceFresh?metrics.resistanceLevel:0));
  gPhoneLiveData->setValue(phonePacket, phoneOffset);
  if (gPhoneSubscribed) {
    gPhoneLiveData->notify();
  }

  Serial.printf("Bike=%s Garmin=%s Phone=%s | %d W, %.1f rpm, %.2f km/h, %.1f kcal%s\n",
                gBikeConnected ? "connected" : "searching",
                gGarminConnected ? "connected" : "waiting",
                gPhoneConnected ? "connected" : "waiting", power, cadence,
                speed, gSessionCaloriesKcal,
                fresh ? "" : " (stale/no data)");
  gLastMeasurementMs = nowMs;
}

void processHistoryCommands(uint32_t now) {
  portENTER_CRITICAL(&gHistoryCommandMux);
  const bool finish=gHistoryFinishRequested;
  bool page=gHistoryPageRequested;
  const uint32_t epoch=gHistoryTime;
  uint32_t offset=gHistoryOffset,before=0,rawId=0; bool importPage=false;
  uint16_t transfer=gHistoryTransfer;
  uint8_t command[20]; const uint8_t commandSize=gArchiveCommandSize;
  memcpy(command,gArchiveCommand,commandSize); gArchiveCommandSize=0;
  gHistoryFinishRequested=false; gHistoryPageRequested=false; gHistoryTime=0;
  portEXIT_CRITICAL(&gHistoryCommandMux);
  auto le32=[](const uint8_t* p)->uint32_t{return p[0]|uint32_t(p[1])<<8|uint32_t(p[2])<<16|uint32_t(p[3])<<24;};
  if(commandSize) {
    if(command[0]==0x11 || command[0]==0x14 || command[0]==0x15) {
      transfer=command[1]|uint16_t(command[2])<<8; offset=le32(command+3);
      importPage=command[0]==0x15;
      if(command[0]==0x11) rawId=le32(command+7); else before=le32(command+7);
      page=true;
    } else if(command[0]==0x12) {
      gHistory.acknowledge(le32(command+1),le32(command+5),le32(command+9));
    } else if(command[0]==0x16) {
      // Legacy phone recovery command: do not invalidate Garmin's GATT cache.
      Serial.println("Phone requested cache recovery; recover locally on phone.");
    } else if(command[0]==0x20) {
      gHistory.settings(command[1],command[2],command[3],command[4]!=0);
    } else if(command[0]==0x21 && !gConfigPortalActive) {
      gPreferences.putBool("wifiPortal",true); startConfigPortal();
    }
  }
  if(epoch) gHistory.tracker.setTime(epoch,now);
  if(finish) {
    gHistory.tracker.finish(2); gHistory.save(true);
    gSessionDurationSeconds=0;
    gSessionDistanceKm=0; gSessionCaloriesKcal=0;
  }
  if(page && gHistoryData) {
    if(!rawId && !importPage && offset==0 && (transfer!=gHistorySnapshotTransfer || before!=gSnapshotBefore || gHistorySnapshot.isEmpty())) {
      gHistorySnapshot=gHistory.json(before,gConfigPortalActive); gHistorySnapshotTransfer=transfer; gSnapshotBefore=before;
    }
    File imported; if(importPage) imported=LittleFS.open("/mybodytone.json","r");
    const uint32_t total=importPage?(imported?imported.size():0):rawId ? gHistory.rawSize(rawId):gHistorySnapshot.length();
    uint8_t packet[510]; size_t cursor=0;
    appendLe16(packet,cursor,(rawId||importPage)?transfer:gHistorySnapshotTransfer);
    appendLe32(packet,cursor,offset);
    appendLe32(packet,cursor,total);
    const uint32_t bytes=offset<=total ? std::min<uint32_t>((rawId||importPage)?500:180,total-offset):0;
    size_t actual=bytes;
    if(bytes) {
      if(importPage) { if(imported.seek(offset)) actual=imported.read(packet+cursor,bytes); else actual=0; }
      else if(rawId) actual=gHistory.readRaw(rawId,offset,packet+cursor,bytes);
      else memcpy(packet+cursor,gHistorySnapshot.c_str()+offset,bytes);
    }
    gHistoryData->setValue(packet,cursor+actual);
  }
  if(gManagementData && (commandSize || now-gLastManagementUpdate>=5000)) {
    String status=gHistory.statusJson(gConfigPortalActive);
    gManagementData->setValue(reinterpret_cast<const uint8_t*>(status.c_str()),status.length()); gLastManagementUpdate=now;
  }
}

}  // namespace

void setup() {
  pinMode(STATUS_LED_PIN, OUTPUT);
  ledcSetup(7,5000,8); ledcAttachPin(STATUS_LED_PIN,7);
  pinMode(CONFIG_BUTTON_PIN, INPUT_PULLUP);
  setLed(false);

  Serial.begin(115200);
  delay(500);
  Serial.println();
  Serial.println("PedalBridge FTMS -> Garmin Cycling Power Bridge 0.15.1");
#if defined(HISTORY_SELF_TEST) || defined(SESSION_SELF_TEST)
  Serial.println(historySelfTest() ? "HISTORY SELF TEST PASS" : "HISTORY SELF TEST FAIL");
#endif

#ifdef FTMS_PARSER_SELF_TEST
  {
    const BikeMetrics saved=gMetrics;
    const uint8_t valid[]={0x65,0,172,0,23,0,182,0};parseIndoorBikeData(valid,sizeof(valid));
    bool ok=gMetrics.hasResistance && gMetrics.resistanceLevel==23 && gMetrics.cadenceRpm==86 && gMetrics.powerWatts==182;
    const uint8_t truncated[]={0x65,0,172,0,99};parseIndoorBikeData(truncated,sizeof(truncated));
    ok=ok && gMetrics.resistanceLevel==23;
    const uint8_t missing[]={0x21,0,0xff,0x7f};parseIndoorBikeData(missing,sizeof(missing));ok=ok && !gMetrics.hasResistance;
    Serial.println(ok?"FTMS RESISTANCE TEST PASS":"FTMS RESISTANCE TEST FAIL");
    const uint8_t energy[]={0x01,0x01,0x2c,0x01,0,0,0};parseIndoorBikeData(energy,sizeof(energy));
    bool energyOk=gMetrics.hasEnergy && gMetrics.totalEnergyKcal==300;
    const uint32_t energyAt=gMetrics.energyAtMs;
    const uint8_t split[]={0x01,0};parseIndoorBikeData(split,sizeof(split));
    energyOk=energyOk && gMetrics.hasEnergy && gMetrics.totalEnergyKcal==300 && gMetrics.energyAtMs==energyAt;
    const uint8_t badEnergy[]={0x01,0x01,99};parseIndoorBikeData(badEnergy,sizeof(badEnergy));
    energyOk=energyOk && gMetrics.hasEnergy && gMetrics.totalEnergyKcal==300 && gMetrics.energyAtMs==energyAt;
    const uint8_t unavailable[]={0x01,0x01,0xff,0xff,0,0,0};parseIndoorBikeData(unavailable,sizeof(unavailable));
    energyOk=energyOk && !gMetrics.hasEnergy;
    parseIndoorBikeData(energy,sizeof(energy));energyOk=energyOk && gMetrics.hasEnergy && gMetrics.totalEnergyKcal==300;
    gMetrics=saved;Serial.println(energyOk?"FTMS ENERGY TEST PASS":"FTMS ENERGY TEST FAIL");
  }
#endif
  gPreferences.begin("smb1bridge", false);
  gWebEnglish = gPreferences.getString("language", "en") != "de";
  gHistory.begin(gPreferences);
  if(gHistory.ready) {
    File imported=LittleFS.open("/mybodytone.json","r"); uint8_t block[512]; uint32_t hash=2166136261U;
    while(imported && imported.available()) {size_t n=imported.read(block,sizeof(block));if(!n)break;hash=history::hashBytes(block,n,hash);}
    gHistory.importChecksum=imported?hash:0; imported.close();
  }
#ifdef HISTORY_SELF_TEST
  Serial.println(archiveSelfTest()?"ARCHIVE SELF TEST PASS":"ARCHIVE SELF TEST FAIL");
#endif
  Serial.printf("History storage: %s; devices=%u sessions=%u generation=%lu\n",
      gHistory.ready ? "ready" : gHistory.error.c_str(),
      gHistory.tracker.state.deviceCount,gHistory.tracker.state.count,
      static_cast<unsigned long>(gHistory.tracker.state.generation));
  // The EN/RST button on some ESP32 clones clears RTC memory. Store the short
  // reset window in NVS so a second hardware reset can always be recognized.
  const bool doubleResetRequested = gPreferences.getBool("resetArmed", false);
  gPreferences.putBool("resetArmed", true);
  gConfiguredAddress = gPreferences.getString("bikeAddress", "");
  gConfiguredName = gPreferences.getString("bikeName", "");
  gConfiguredAddressType = gPreferences.getUChar("bikeAddrType", 0);
  const bool wifiPortalRequested = gPreferences.getBool("wifiPortal", true);

  NimBLEDevice::init(GARMIN_SENSOR_NAME);
  // Optional pairing is retained across reconnects. Public sensor/app services
  // remain usable without pairing; neither the bridge nor app initiates it.
  NimBLEDevice::setSecurityAuth(true, false, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
  NimBLEDevice::setCustomGapHandler([](ble_gap_event* event, void*) -> int {
    if (event->type == BLE_GAP_EVENT_DISCONNECT)
      Serial.printf("BLE disconnect: handle=%u reason=%d\n", event->disconnect.conn.conn_handle, event->disconnect.reason);
    if (event->type == BLE_GAP_EVENT_ENC_CHANGE)
      Serial.printf("BLE encryption: handle=%u status=%d\n", event->enc_change.conn_handle, event->enc_change.status);
    return 0;
  });
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);

  createGarminPowerSensor();

  NimBLEScan* scan = NimBLEDevice::getScan();
  scan->setAdvertisedDeviceCallbacks(&gBikeScanCallbacks, false);
  scan->setInterval(97);
  scan->setWindow(67);
  scan->setActiveScan(true);

  if (doubleResetRequested) {
    Serial.println("Double reset detected: enabling Wi-Fi diagnostics.");
    gPreferences.putBool("wifiPortal", true);
  }
  if (gConfiguredAddress.isEmpty() || wifiPortalRequested ||
      doubleResetRequested) {
    startConfigPortal();
  } else {
    WiFi.mode(WIFI_OFF);
    Serial.printf("Configured bike: '%s' [%s]\n", gConfiguredName.c_str(),
                  gConfiguredAddress.c_str());
  }

  startBikeScan();
}

void loop() {
  const uint32_t now = millis();

  if (now > DOUBLE_RESET_WINDOW_MS && !gResetArmCleared) {
    gResetArmCleared = true;
    gPreferences.putBool("resetArmed", false);
    Serial.println("Double-reset window closed.");
  }

  if (digitalRead(CONFIG_BUTTON_PIN) == LOW) {
    if (gButtonPressedAtMs == 0) {
      gButtonPressedAtMs = now;
    } else if (!gButtonHandled &&
               now - gButtonPressedAtMs >= CONFIG_BUTTON_HOLD_MS) {
      gButtonHandled = true;
      startConfigPortal();
    }
  } else {
    gButtonPressedAtMs = 0;
    gButtonHandled = false;
  }

  if (gConfigPortalActive) {
    gDnsServer.processNextRequest();
    gWebServer.handleClient();
  }

  if (gWifiDisableFromBleRequested) {
    gWifiDisableFromBleRequested = false;
    gPreferences.putBool("wifiPortal", false);
    if (gConfigPortalActive) {
      gWifiShutdownPending = true;
      gWifiShutdownRequestedAtMs = now;
    } else {
      WiFi.mode(WIFI_OFF);
    }
  }

  if (gWifiShutdownPending &&
      now - gWifiShutdownRequestedAtMs >= 1200UL) {
    gWifiShutdownPending = false;
    gConfigPortalActive = false;
    gWebServer.stop();
    gDnsServer.stop();
    WiFi.softAPdisconnect(true);
    WiFi.mode(WIFI_OFF);
    Serial.println("Wi-Fi diagnostics disabled by user; BLE bridge remains active.");
  }

  if (gConnectRequested && !gBikeConnected) {
    gConnectRequested = false;
    if (!connectToBike()) {
      gBikeCandidateAvailable = false;
      gScanFinished = true;
    }
  }

  if (!gBikeConnected && !gConnectRequested &&
      (gScanFinished || now - gLastScanStartMs >
                            (SCAN_DURATION_SECONDS + 2) * 1000UL)) {
    startBikeScan();
  }

  if (gBikeClient != nullptr && gBikeConnected && !gBikeClient->isConnected()) {
    gBikeConnected = false;
    gScanFinished = true;
  }

  processFtmsControlCommand();
  processHistoryCommands(now);

  if (now - gLastMeasurementMs >= GARMIN_UPDATE_INTERVAL_MS) {
    publishPowerMeasurement(now);
  }

  // Solid while both links are established, slow blink while waiting.
  if(gHistory.ledPattern==3) setLed(false);
  else if(gHistory.ledPattern==1) setLed(now%2000<120);
  else if(gHistory.ledPattern==2) setLed(now%3000<80 || (now%3000>=240 && now%3000<320));
  else setLed((gBikeConnected && gGarminConnected) || now%2000<120);

  delay(10);
}
