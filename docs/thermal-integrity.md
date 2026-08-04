# Thermal Integrity simulation

EnderSlicerCura adds an Android-only **Thermal Integrity** workspace to the pinned offline filaSim runtime. It is separate from filaSim's **Build Simulation**:

- **Build Simulation** estimates shrink-driven distortion and bed reactions during printing and cooldown.
- **Thermal Integrity** estimates the temperature field and coupled structural response of the finished part under a user-defined service heat source, cooling environment and mechanical load case.

Both are experimental engineering aids. Neither is certification or a substitute for instrumented physical testing.

## Analysis pipeline

1. filaSim voxelizes the exact transformed STL using its existing FEA grid.
2. Shell and infill material fractions are reconstructed from the selected wall, top/bottom, line-width and infill assumptions.
3. When requested and available, the most recent optimized Smart Infill density field replaces the fallback uniform infill in the design region.
4. A cell-centred finite-volume heat equation is assembled on active material cells.
5. Steady mode solves the nonlinear radiation boundary iteratively. Transient mode advances heat capacity with implicit Euler.
6. The solved local temperature reduces local stiffness and allowable strength between the material-property reference temperature and the literature-seeded service limit.
7. Local XY/Z thermal expansion is assembled as structural eigenstrain.
8. filaSim's existing structural multigrid solver combines thermal strain with the current mechanical load case.
9. The Android host validates and stores an auditable report bound to the exact STL SHA-256, cumulative filaSim transform, material, boundaries, grid and print-property inputs.

## Heat equation

For each active voxel cell, the implementation solves an energy balance of the form

```text
ρ cp V (Tⁿ⁺¹ − Tⁿ) / Δt
+ Σfaces Gij (Tiⁿ⁺¹ − Tjⁿ⁺¹)
+ Σsurface h A (Tiⁿ⁺¹ − Tamb)
+ Σsurface ε σ A (Ti⁴ − Tamb⁴)
= Qsurface + Qvolume
```

The storage term is omitted in steady-state mode.

### Internal conduction

- Separate X, Y and Z conductivity inputs represent print anisotropy.
- Conductivity between neighboring cells uses a harmonic face average.
- Conductivity inside partial-density cells is blended between air and bulk material using the selected density exponent.
- Geometric cut-cell occupancy is separated from intrinsic printed-material density. Occupancy reduces face area and heat capacity; it does not incorrectly turn a fully solid boundary cell into low-density polymer.
- Internal conductance uses the shared cut-cell face fraction, reducing staircase artifacts at curved voxel boundaries.
- Solver units are W, mm and K/°C; SI conductivity and convection coefficients are converted at the assembly boundary.

### Surface boundaries

The user selects one of six axis-aligned global face groups for each role:

- X−, X+, Y−, Y+, Z− or Z+
- **Heated face:** total surface heat power is distributed by exposed voxel-face area on the selected global extreme plane.
- **Fixed-temperature face:** a Dirichlet boundary is coupled through the cell half-distance on the selected global extreme plane.
- Other exterior surfaces reject heat through convection and radiation.

A downward-facing step, overhang underside or cavity wall with the same normal is not silently treated as the selected heater or mount. Only the global extreme face receives that special boundary.

Ambient convection and radiation are applied only to void connected to the outside of the padded voxel grid. A sealed internal cavity is treated as sealed rather than receiving fictitious ambient airflow. An internal channel that is actually open to the exterior remains exterior-connected.

A separate volumetric power input distributes internal heat over material volume. Surface and volumetric power can be combined.

The face abstraction is useful for mounts, electronics contact patches and hot/cold interfaces, but it is not a geometric contact solver. Complex real contact patches should be represented conservatively and physically validated.

## Steady and transient modes

### Steady state

Steady mode solves the final equilibrium temperature. Radiation is nonlinear, so the Stefan–Boltzmann boundary is linearized at the current surface temperature and iterated to consistency.

Use steady state when the heat source remains active long enough for the part to approach equilibrium.

### Transient

Transient mode uses implicit Euler, which remains stable for large time steps but can lose temporal accuracy when the step is too coarse. The workspace limits a run to 2,000 time steps and reports maximum and material-volume-weighted mean temperature history.

The coupled structural solve is evaluated using the **final temperature field at the requested duration**. The temperature history still reports earlier thermal peaks, but this first implementation does not run a full structural solve at every time step. To inspect an intermediate structural state, rerun transient analysis with the duration set to that time. For a constant heating case, increase duration until both temperature and structural outputs converge toward steady state.

Refine the time step until peak temperature and time-to-peak stop changing materially.

## Thermo-mechanical coupling

The local free thermal strain is

```text
εthermal = [αXY ΔT, αXY ΔT, αZ ΔT]
```

where `ΔT` is measured from the material-property reference temperature.

The same local temperature field controls:

- modulus retention;
- allowable-strength retention;
- thermal eigenstrain;
- local thermal-mechanical von Mises stress.

Retention is conservatively interpolated from 100% at the reference temperature to the selected floor fraction at the literature-seeded service limit. Above that limit, the floor is retained and the report is marked as property extrapolation.

The structural stiffness operator remains occupancy-scaled, but reported material stress and strength use occupancy-decoupled printed density. This prevents partially occupied surface voxels from producing false safety-factor bands around curved geometry.

This is intentionally transparent and bounded. It does not pretend that one universal glass-transition or heat-deflection value fully describes every filament, raster and load duration.

## Structural constraints

Two structural modes are available:

### Free expansion

- User supports are removed.
- Current force, pressure, bearing, moment and remote-mass loads remain.
- A minimal 3-2-1 grounding removes rigid-body motion without intentionally suppressing free thermal expansion.

Use this for a loose or unconstrained finished part.

### Constrained

- Current filaSim supports and loads are retained.
- The normal pre-solve rigid-body check must pass.

Use this when the part is bolted, clamped, bonded or otherwise constrained in service. The selected filaSim supports must represent the real mount.

## Material and infill model

The Android workspace provides literature-seeded PLA, PETG and ABS presets. Every value remains editable.

Inputs include:

- X/Y/Z conductivity;
- density and specific heat;
- XY/Z coefficient of thermal expansion;
- reference Young's modulus, Poisson ratio and strength;
- reference and service-limit temperatures;
- stiffness and strength retention floors;
- conductivity, stiffness and strength density exponents;
- wall, line-width, top/bottom, layer-height and fallback infill assumptions.

The presets are starting points, not universal material cards. Pigment, additives, moisture, annealing, print temperature, raster direction, extrusion width, layer adhesion and supplier formulation all change the real properties.

## Reported results

The workspace and native Markdown report include:

- minimum, mean and maximum temperature;
- hotspot coordinates;
- peak transient temperature and time;
- heat input, heat rejection and transient stored-energy rate;
- energy-balance residual;
- exposed heated and cooled areas;
- maximum coupled deformation at equilibrium or final transient time;
- maximum thermal-mechanical von Mises stress at equilibrium or final transient time;
- minimum modulus and strength retention;
- conservative temperature- and density-reduced safety factor;
- temperature margin to the selected material preset limit;
- thermal and structural iteration/residual diagnostics;
- grid dimensions, voxel size and active-cell count;
- whether optimized Smart Infill density was actually used;
- whether material properties were extrapolated.

## Safety-factor interpretation

The reported material safety factor is the minimum over active material cells of

```text
local allowable strength / local von Mises stress
```

The local allowable is reduced by both temperature and printed material density. Geometric cut-cell occupancy is removed from both material stress and density allowable so the ratio represents the printed material rather than the voxelization boundary.

- A result below one is a warning under the entered assumptions.
- A result above one is not proof of service life, fatigue life, creep resistance, layer adhesion or regulatory compliance.
- Stress near idealized fixed supports and sharp voxel boundaries can be mesh-sensitive.
- Compare multiple grid resolutions and validate critical regions physically.

## Energy balance

The report calculates

```text
imbalance = |Qin − Qrejected − dU/dt| / max(|Qin|, |Qrejected|, |dU/dt|)
```

For steady state, `dU/dt` is zero. A high residual means the numerical result needs investigation—typically a finer grid, smaller transient time step or more appropriate boundaries. It is not an additional physical heat loss.

Heat rejection is evaluated against the same fixed-temperature, convection and radiation surfaces used by the solved operator. Sealed cavities are not included as ambient heat-rejection surfaces.

## Exact provenance

Before each run, the Android workspace queries filaSim's exact cumulative 3×4 model transform. The raw worker result is not read back from rounded DOM text.

The native analysis fingerprint includes:

- analysis and solver identity;
- pinned filaSim commit;
- STL SHA-256;
- exact transform, including orientation, scale and placement;
- complete material card;
- heat, cooling and structural boundary inputs;
- print/infill assumptions;
- voxel size and grid dimensions.

Changing any of these creates a separate report instead of overwriting a different analysis of the same STL.

If pose capture fails, the solve remains fail-open and can still be inspected on screen, but native report saving is disabled for that run.

## Applicability limits

The current implementation does **not** calculate:

- G-code/nozzle-path reheating or cooling history;
- interlayer weld kinetics or delamination probability;
- a structural solve at every transient time step;
- temperature-dependent creep or stress relaxation;
- fatigue or impact life;
- moisture, aging, chemical exposure or UV degradation;
- melting, phase change or annealing;
- enclosure airflow CFD;
- gas conduction or natural convection inside sealed cavities;
- certified thermal contact resistance;
- a probability of failure or regulatory verdict.

A sealed cavity is treated as adiabatic at its internal wall in this first implementation. Model cavity gas or inserts explicitly when they materially affect heat transfer.

The fixed face, convection and emissivity values must be selected for the actual environment. Natural-convection coefficients and interface temperatures can dominate the answer.

## Literature provenance

Initial presets and report provenance reference:

- Printed PLA/PET-G/ABS thermal conductivity across material, temperature, infill and pattern: DOI `10.3390/ma18173950`.
- Printed PLA coefficient of thermal expansion: DOI `10.3390/ma17184668`.
- Orientation-dependent printed ABS thermal expansion: DOI `10.3390/nano8010049`.

These references seed the model. They do not calibrate a particular spool, printer, profile, mounting interface or airflow environment.

## Recommended first validation

Use a simple rectangular bar or L-bracket with an obvious hot and cold end.

1. Start with a coarse or normal filaSim grid.
2. Choose PLA, steady state, X− heat and X+ fixed temperature.
3. Apply 1–3 W, ambient 23 °C, fixed surface 23 °C, convection 8 W/(m²·K), emissivity 0.9.
4. Use free expansion and no mechanical load for the first run.
5. Verify that temperature decreases monotonically from the heated global end toward the fixed-temperature global end.
6. Confirm heat rejected approximately equals heat input and the reported energy imbalance is small.
7. Rotate the same STL 90° and repeat; the analysis fingerprint must change and anisotropic conductivity should change the field when X/Y/Z values differ.
8. Run transient mode and reduce the time step until peak temperature and time-to-peak converge.
9. For an intermediate transient structural state, repeat with the duration ending at that time.
10. Add a real support/load case and compare free-expansion with constrained thermal stress.
11. Test a model containing a sealed cavity and confirm it does not act as an ambient-cooled internal surface.
12. Print a non-critical coupon, instrument it with temperature sensors and calibrate conductivity, convection and contact assumptions before relying on a real design.

## Build and regression gates

The thermal-integrity branch workflow validates:

- deterministic patching of the exact pinned filaSim commit;
- Rust finite-volume unit tests;
- sealed-cavity exterior-connectivity behavior;
- global-extreme heater/fixed-face selection;
- cut-cell conductivity and occupancy-decoupled material stress;
- WASM and TypeScript compilation;
- raw JavaScript syntax;
- Android/native schema lockstep;
- report tamper, pose, range and time-history validation;
- the complete Android unit suite;
- debug APK assembly;
- packaged asset format, source manifest and thermal workspace presence.
