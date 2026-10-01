# Changelog

## Firmware 0.15.1

- Prevent a transient bike energy reset (for example 300 → 0 → 300) from doubling workout calories.
- Reject implausible counter increases using observed riding time, while allowing legitimate delayed integer-counter updates.
- Rebase energy after pauses, disconnects and long blocked sampling intervals to avoid counting unobserved history.
- Treat FTMS unavailable energy as missing data and use power fallback. Cached energy now has its own freshness timestamp.
- Add executable production-model regressions to CI and parser checks to the FTMS self-test.
- Archive formats and the existing 24% power-estimation assumption remain unchanged. Display 0.10.1 remains compatible.

## Display 0.10.1

- Directional slide transitions for swipes and tab taps, with clean cancellation during rapid navigation and configuration changes.
- Consistent heading size, baseline and alignment across all three tabs in portrait and landscape.
- Settings grouped into App & archive, Garmin & heart rate, and PedalBridge, with distinct category icons and device-scoped saving.
- Settings navigation now uses a symmetric cog icon.
- Existing Bluetooth connections, live readings and pending device settings are preserved. System animation preferences are respected.
- Firmware remains at 0.15.0.

## Display 0.10.0 / Firmware 0.15.0

- Complete English and German app UI, including dialogs, charts, accessibility labels, errors and Bluetooth status.
- Persistent app language selection under Settings → Appearance.
- Language switching preserves BLE clients, live readings, synchronization and unsaved device settings.
- Locale-aware dates and numbers; imported source data remains unchanged.
- English and German ESP setup page and diagnostics with a persistent web language selector.
- Repository documentation and GitHub release descriptions translated to English.

## Display 0.9.2

- Extended the upper connection arrow in the app icon and balanced spacing around the pedal.
- Refined circular radius, stroke weight and arrowheads.
- Firmware remained at 0.14.1.

## Display 0.9.1 / Firmware 0.14.1

- Shared PedalBridge and PedalBridge Display branding.
- Adaptive Android icon and monochrome variant for themed launcher icons.
- New BLE names, retaining recognition of earlier names.
- Existing package ID, database, archive format and UUID compatibility retained.
- Combined repository, build instructions and synthetic import fixtures.

Power and calorie calculations are unchanged by these branding and language updates.
