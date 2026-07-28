# Calibration audit

This branch fixes the calibration-generator issues found while inspecting a generated firmware-retraction G-code file.

- Compact defaults: 4 mm sections and 16 mm nominal width.
- Smaller bases for temperature and fan models.
- Calibration-only support, adaptive-layer, arc-overhang, ironing and coasting overrides; normal adhesion settings remain available.
- Cura minimum-layer-time slowdown is disabled only for calibration slices, so compact speed tests exercise the requested speed rather than a cooling throttle.
- Temperature tests start at their requested first temperature.
- Fan tests own fan commands after the first calibration event instead of being overwritten by Cura cooling/bridge commands.
- Retraction tests force real retracting travel between isolated posts by disabling combing and the minimum-travel gate for that temporary slice.
- Empty Cura transition layers are skipped when resolving calibration heights.
- M207 changes are deferred until after a matching G11 when Cura ended the previous layer with G10.
- Speed and flow factors are restored to 100% at EOF, fan is forced off, and retraction restores the configured M207 distance/speed.
- Firmware retraction for the generated retraction model remains slice-local instead of being written into the user's persisted printer/profile settings.
- Calibration mode is armed only after the generated geometry validates successfully.
- Regression coverage includes event ordering, empty layers, fan ownership, forced retraction travel, modifier restoration and compact geometry.
