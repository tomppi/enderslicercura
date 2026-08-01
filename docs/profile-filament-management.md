# Profile and filament management

EnderSlicerCura stores two independent kinds of named user presets:

- **Print profiles** contain quality, layer-height, wall, seam, infill, speed, support, travel, adhesion, arc-overhang and ironing settings.
- **Filament profiles** contain filament diameter, nozzle and bed temperatures, flow, cooling, retraction, Z-hop, firmware-retraction and coasting settings.

Printer dimensions, nozzle size, G-code flavor, printhead geometry and custom start/end G-code remain machine settings. Applying a filament therefore cannot replace machine geometry or print-quality choices, and applying a print profile cannot replace the selected filament's temperatures, flow or retraction behavior.

## Using presets

Open **Profiles & filament** from the persistent action above the OctoPrint control. Select **Print profiles** or **Filaments** and then:

1. Adjust the normal slicer settings.
2. Select **Save current as…**.
3. Enter a name for the new preset.
4. Apply a saved preset at any time.

The selected preset is marked active. Further settings changes mark it **Active · modified**. **Save changes** replaces that preset's stored category values. **Rename** changes only its display name. **Delete** removes the saved preset but deliberately leaves the current slicer settings unchanged.

When switching away from modified settings, the app offers three choices:

- discard the unsaved category changes and apply the selected preset;
- save the active preset first and then apply the selected preset;
- cancel the switch.

## Cura baseline and persistence

Named presets use the existing `SlicerSettings` override lifecycle. Applying a preset is one atomic settings update, invalidates stale G-code and layer previews, persists the resulting settings, and keeps Cura's imported baseline and dependency-resolution behavior intact.

The preset library is stored as a versioned private JSON document under the app's persistent-state directory. Writes use a staged file and rollback copy. Names are normalized, limited to 60 characters and unique within their preset kind. Up to 100 print profiles and 100 filament profiles can be stored.

Older partial presets remain usable if a later EnderSlicerCura release adds new settings. Only recognized, correctly typed values are applied; newer values that are absent from the older preset remain unchanged. Saving changes upgrades the preset to a complete snapshot for the current release.

## Safety behavior

- Applying a preset never changes settings outside its category.
- Applying a preset invalidates previously sliced G-code before it can be exported or uploaded.
- Repeated save/apply/rename/delete taps are serialized.
- Corrupt records, missing active IDs and presets without any usable values are ignored or rejected.
- Integer values must be integral and finite; other numeric values must be finite.
- Optimized builds retain the stable `SlicerSettings` backing-field names used by preset serialization.
- Deleting an active preset clears its active marker without resetting the current settings.

## Current limitation

Presets are local to the app. Import/export of the named preset library and cloud synchronization are not included in this first implementation. Cura `.3mf` and `.curaprofile` import continue to work independently as the underlying baseline configuration.
