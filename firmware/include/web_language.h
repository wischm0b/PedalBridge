#pragma once
#include <Arduino.h>

// The web language preference is independent of the companion app language.
bool gWebEnglish=true;
const __FlashStringHelper* webText(const __FlashStringHelper* de,const __FlashStringHelper* en) { return gWebEnglish?en:de; }
String webStatus(String value) {
  if(!gWebEnglish)return value;
  value.replace(F("Verbunden, aber FTMS-Service 1826 fehlt"),F("Connected, but FTMS service 1826 is missing"));
  value.replace(F("Bike einschalten und treten. Die Liste wird beim Neuladen aktualisiert. FTMS und ein Signal über −85 dBm sind ideal."),F("Turn on the bike and start pedaling. Reload to refresh the list. FTMS and a signal above −85 dBm are ideal."));
  value.replace(F("Timeout (13): Signal zu schwach, Bike belegt oder nicht mehr wach"),F("Timeout (13): signal too weak, bike busy or asleep"));
  value.replace(F("Mit BOOT oder zweimal RST lässt sich die Diagnose wieder öffnen."),F("Hold BOOT or press RST twice to reopen diagnostics."));
  value.replace(F("Indoor-Bike-Daten 2AD2 fehlen oder senden keine Notifications"),F("Indoor Bike Data 2AD2 missing or not sending notifications"));
  value.replace(F("HCI 0x3E: Bike antwortet nicht auf den Verbindungsaufbau"),F("HCI 0x3E: bike is not responding to the connection request"));
  value.replace(F("Control-Point-Indications konnten nicht aktiviert werden"),F("Could not enable control point indications"));
  value.replace(F("Bike trennte die Verbindung während der Diensterkennung"),F("Bike disconnected during service discovery"));
  value.replace(F("Start/Resume gesendet; Bike antwortet ohne Indication"),F("Start/Resume sent; bike responds without an indication"));
  value.replace(F("Keine Control-Antwort; Start/Resume folgt trotzdem"),F("No control response; proceeding with Start/Resume"));
  value.replace(F("Control-Point 2AD9 wird vom Bike nicht angeboten"),F("Bike does not provide control point 2AD9"));
  value.replace(F("Mit Bike verbunden; FTMS-Dienste werden gelesen"),F("Connected to bike; discovering FTMS services"));
  value.replace(F("Keine Antwort; Request Control wird wiederholt"),F("No response; retrying Request Control"));
  value.replace(F("Bike gespeichert; Verbindung wird aufgebaut"),F("Bike saved; connecting"));
  value.replace(F("Request Control gesendet; warte auf Antwort"),F("Request Control sent; waiting for response"));
  value.replace(F("Control-Point bereit; Request Control folgt"),F("Control point ready; Request Control follows"));
  value.replace(F("Diagnoseverbindung zum ESP32 unterbrochen."),F("Diagnostic connection to the ESP32 interrupted."));
  value.replace(F("Bike hat die Bluetooth-Verbindung getrennt"),F("Bike disconnected Bluetooth"));
  value.replace(F("Ungültige Antwort vom FTMS-Control-Point"),F("Invalid response from the FTMS control point"));
  value.replace(F("Kontrolle übernommen; Start/Resume folgt"),F("Control acquired; Start/Resume follows"));
  value.replace(F("Start/Resume gesendet; warte auf Antwort"),F("Start/Resume sent; waiting for response"));
  value.replace(F("FTMS-Abonnement auf 2AD2 fehlgeschlagen"),F("FTMS subscription to 2AD2 failed"));
  value.replace(F("WLAN ausschalten &amp; Betrieb starten"),F("Turn off Wi-Fi &amp; continue riding"));
  value.replace(F("Noch keine Bluetooth-Geräte gefunden."),F("No Bluetooth devices found yet."));
  value.replace(F("FTMS-Datenstrom erfolgreich abonniert"),F("FTMS data stream subscribed successfully"));
  value.replace(F("Kontrolle aktiv, Training gestartet"),F("Control active, workout started"));
  value.replace(F("Bike bietet keinen FTMS-Dienst 1826"),F("Bike does not provide FTMS service 1826"));
  value.replace(F("Gerätelimit erreicht (16 Bikes)"),F("Device limit reached (16 bikes)"));
  value.replace(F("Die Bike-Bridge läuft weiter. "),F("The bike bridge keeps running. "));
  value.replace(F("Request Control wird gesendet"),F("Sending Request Control"));
  value.replace(F("Noch kein Verbindungsversuch"),F("No connection attempt yet"));
  value.replace(F("Ungültige Bluetooth-Adresse"),F("Invalid Bluetooth address"));
  value.replace(F("Start/Resume wird gesendet"),F("Sending Start/Resume"));
  value.replace(F("Geräteliste aktualisieren"),F("Refresh device list"));
  value.replace(F("Verbindungsversuch läuft"),F("Connection attempt in progress"));
  value.replace(F("Diagnose wird geladen …"),F("Loading diagnostics …"));
  value.replace(F("WLAN wird ausgeschaltet"),F("Turning off Wi-Fi"));
  value.replace(F("Schreiben von Befehl 0x"),F("Writing command 0x"));
  value.replace(F(" abgelehnt, Ergebnis 0x"),F(" rejected, result 0x"));
  value.replace(F("Training zurückgesetzt"),F("Workout reset"));
  value.replace(F("BLE-Verbindungsfehler "),F("BLE connection error "));
  value.replace(F("Training zurücksetzen"),F("Reset workout"));
  value.replace(F("Noch nicht verfügbar"),F("Not available yet"));
  value.replace(F("Aktuelle Messwerte"),F("Current metrics"));
  value.replace(F("Bluetooth-Geräte"),F("Bluetooth devices"));
  value.replace(F("Geschwindigkeit"),F("Speed"));
  value.replace(F("nicht verbunden"),F("disconnected"));
  value.replace(F(" fehlgeschlagen"),F(" failed"));
  value.replace(F("Bike auswählen"),F("Select bike"));
  value.replace(F("Live-Training"),F("Live workout"));
  value.replace(F("Trittfrequenz"),F("Cadence"));
  value.replace(F("Garmin Speed:"),F("Garmin speed:"));
  value.replace(F("Verbindungen"),F("Connections"));
  value.replace(F("(ohne Namen)"),F("(unnamed)"));
  value.replace(F("FTMS-Pakete:"),F("FTMS packets:"));
  value.replace(F("Gespeichert"),F("Saved"));
  value.replace(F("Auswählen"),F("Select"));
  value.replace(F("verbunden"),F("connected"));
  value.replace(F("Befehl 0x"),F("Command 0x"));
  value.replace(F("Leistung"),F("Power"));
  value.replace(F("Kalorien"),F("Calories"));
  value.replace(F("Distanz"),F("Distance"));
  value.replace(F("Adresse"),F("Address"));
  value.replace(F("Seite:"),F("Page:"));
  value.replace(F("Gerät"),F("Device"));
  value.replace(F("Zeit"),F("Time"));
  value.replace(F("nie"),F("never"));
  return value;
}
