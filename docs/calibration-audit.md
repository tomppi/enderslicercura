# Calibration audit

This branch fixes the calibration-generator issues found while inspecting a generated firmware-retraction G-code file.

- Compact defaults: 4 mm sections and 16 mm nominal width.
- Smaller bases for temperature and fan models.
- Calibration-only support/adaptive-layer/arc-overhang/ironing/coasting/adhesion overrides.
- Minimum-layer-time slowdown disabled only for calibration slices.
- Temperature tests start at their requested first temperature.
- Fan tests own fan commands after the first calibration event.
- Empty Cura transition layers are skipped when resolving calibration heights.
- M207 changes are deferred until after a matching G11 when Cura ended the previous layer with G10.
- Regression coverage added for event ordering, empty layers, fan ownership and compact geometry.
