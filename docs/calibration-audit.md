# Calibration audit

The calibration pipeline uses compact support-free models, inserts the requested firmware or slicer value at each section, and keeps ordinary printer/profile behavior unless it directly invalidates the selected test.

- Compact defaults: 4 mm sections and 16 mm nominal width.
- Smaller bases for temperature and fan models.
- Generated support and support-interface material are disabled for every calibration model.
- Overrides are type-specific rather than globally disabling core print behavior.
- Temperature tests disable adaptive layers and arc-overhang replacement, retain normal cooling/coasting, and start at the requested first temperature.
- Flow tests disable adaptive layers, ironing and coasting so wall extrusion remains measurable; other profile behavior remains active.
- Speed tests disable adaptive layers and Cura minimum-layer-time slowdown so the requested speed factor can actually be reached.
- Fan tests disable adaptive layers, arc-overhang replacement and minimum-layer-time slowdown, and own fan commands after the first event instead of being overwritten by Cura cooling or bridge commands.
- Retraction tests retain profile cooling, coasting, wipe, hop and travel behavior. They force firmware retraction, allow short travel to retract, and disable combing so every post-to-post move exercises the selected M207 distance.
- Pressure-advance tests reuse the isolated-post model, disable adaptive layers, coasting and minimum-layer-time slowdown, and step Marlin `M900 K`.
- Junction-deviation tests reuse the sharp-corner speed star, disable adaptive layers and minimum-layer-time slowdown, and step Marlin `M205 J`.
- Empty Cura transition layers are skipped when resolving calibration heights.
- M207 changes are deferred until after a matching G11 when Cura ended the previous layer with G10.
- Speed and flow factors are restored to 100% at EOF; fan is forced off; retraction restores the configured M207 distance/speed; pressure advance and junction deviation restore their first requested values.
- Firmware retraction for the generated retraction model remains slice-local instead of being written into persisted printer/profile settings.
- Calibration mode is armed only after generated geometry validates successfully.
- Regression coverage includes event ordering, empty layers, fan ownership, type-specific override policies, firmware-state restoration, intentional geometry reuse and compact geometry.
