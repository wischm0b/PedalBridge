# Storage and privacy

The ESP stores devices, summaries and optional per-second recordings in LittleFS. Recording capacity depends on duration and free flash. The app shows storage usage and configuration options.

A session starts only when movement is detected. After manually finishing, the bike must first become stationary before movement starts another session. Automatic completion follows the configured inactivity interval.

The ESP may release per-second data after verified transfer and confirmation from the app. Under storage pressure, the oldest completed recordings are released first. Summaries and recordings already saved on the phone remain independent. Summary limits are defined in the firmware.

The app uses local SQLite storage, requires no Internet permission and offers ZIP backup. Losing a phone without a backup can permanently lose recordings already released by the ESP. Export the archive regularly.

MyBodytone files are imported through the app. For compatibility, the format remains `smb1-training-import`, version 1. Stable source IDs prevent duplicates. Normalized values and original source fields are preserved. Unknown time zones are not guessed. Language changes affect display labels and formatting, never the imported source values or stored records.

This repository contains only synthetic test data. Personal imports, device backups, diagnostic logs and signing keys must not be committed. The personal one-time provisioning file `bodytone_seed.h` is intentionally excluded.

## Calories

The bridge accumulates differences from the bike's cumulative energy counter only across observed riding intervals. Counter resets establish a new baseline; implausible jumps are rejected rather than added to the workout. Valid delayed counter updates remain supported. A separate energy freshness timestamp prevents unrelated FTMS packets from indefinitely refreshing an old calorie value.

Missing or unavailable bike energy uses the existing estimate from measured power and 24% efficiency. Returning bike energy establishes a new baseline so earlier calories are not counted twice. Garmin uses its own estimate and recording duration, so totals can still differ. Older stored sessions are not automatically rewritten; correcting an affected session requires its underlying measurements.
