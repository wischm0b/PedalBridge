# Daten und Archiv

Der ESP speichert Geräte, Zusammenfassungen und optional Sekundenwerte in LittleFS. Die Anzahl möglicher Sekundenaufzeichnungen hängt von Dauer und freiem Flash ab. Die App zeigt Speicherbelegung und Konfigurationsoptionen an.

Ein Training beginnt erst mit Bewegung. Nach manuellem Abschluss muss das Bike zunächst stillstehen, bevor Bewegung ein neues Training eröffnet. Automatischer Abschluss erfolgt nach dem eingestellten Stillstandsintervall.

Nach geprüftem Transfer und Bestätigung durch die App können Sekundenwerte auf dem ESP gelöscht werden. Alte abgeschlossene Sekundenaufzeichnungen werden bei Speicherbedarf zuerst entfernt. Zusammenfassungen und auf dem Handy gespeicherte Daten bleiben unabhängig davon erhalten. Grenzen des Zusammenfassungsarchivs sind im Firmware-Code festgelegt.

Die App speichert alles lokal in SQLite, benötigt keine Internetberechtigung und bietet einen ZIP-Export. Ein Verlust des Handys ohne Backup kann synchronisierte Sekundenwerte unwiederbringlich verlieren. Regelmäßig das Archiv exportieren.

MyBodytone-Dateien werden über die Importfunktion übernommen. Das Format heißt aus Kompatibilitätsgründen weiterhin `smb1-training-import`, Version 1. Stabile Quell-IDs vermeiden doppelte Einträge. Normalisierte Werte und Originalangaben bleiben erhalten. Unbekannte Zeitzonen werden nicht geraten.

Dieses Repository enthält ausschließlich synthetische Testdaten. Persönliche Importe, Geräte-Backups, Diagnoseprotokolle und Signierschlüssel gehören nicht in Git. Die persönliche `bodytone_seed.h` zur einmaligen Bereitstellung ist nicht enthalten.
