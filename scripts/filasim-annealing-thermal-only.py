#!/usr/bin/env python3
"""Add an explicit thermal-only output path for geometry-aware Annealing.

Thermal Integrity keeps the complete coupled thermo-mechanical solve. Annealing
only needs the transient temperature field, readiness diagnostics, material
fractions and (for the heating stage) mesh vertex temperatures. Skipping
structural matrix assembly, stress recovery and strength post-processing avoids
performing unused FEA for both heating and cooling.
"""

from __future__ import annotations

import pathlib

MARKER = "EnderSlicer annealing thermal-only solver v1"
SOURCE_MARKER = ".enderslicer-annealing-thermal-only-v1"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    wasm = source_root / "crates/filasim-wasm/src/lib.rs"
    client = source_root / "web/src/engine/EngineClient.ts"
    for path in (wasm, client):
        if not path.is_file():
            raise RuntimeError(f"Annealing thermal-only target is missing: {path}")

    replace_once(
        wasm,
        """    time_step_seconds: f64,
    fixed_surface_enabled: bool,
""",
        """    time_step_seconds: f64,
    /// Skip coupled structural assembly and stress recovery. This is used by
    /// Annealing, never by the Thermal Integrity workflow.
    thermal_only: bool,
    /// Heating needs temperatures mapped onto the STL for the 3D result;
    /// cooling only needs cell diagnostics and can omit mesh-sized arrays.
    include_visualization_fields: bool,
    fixed_surface_enabled: bool,
""",
        "WASM thermal-only options",
    )
    replace_once(
        wasm,
        """            time_step_seconds: 10.0,
            fixed_surface_enabled: true,
""",
        """            time_step_seconds: 10.0,
            thermal_only: false,
            include_visualization_fields: true,
            fixed_surface_enabled: true,
""",
        "WASM thermal-only defaults",
    )

    replace_once(
        wasm,
        """        let vertex_temperatures = map_cell_temperature_to_mesh(
            &self.mesh, &grid, &thermal.temperatures_c, &material_fraction,
        );
        let lower_property_limit = opts.reference_temperature_c - 50.0;
""",
        r'''        let vertex_temperatures = if opts.include_visualization_fields {
            map_cell_temperature_to_mesh(
                &self.mesh, &grid, &thermal.temperatures_c, &material_fraction,
            )
        } else {
            Vec::new()
        };
        if opts.thermal_only {
            emit_progress(
                "Thermal cycle complete", 0.98,
                "Annealing requested temperature fields without structural FEA",
            );
            let active_cells = material_fraction.iter().filter(|value| **value > 0.0).count();
            let effective_flux = if thermal.exposed_heated_area_mm2 > 0.0 {
                opts.heat_power_w / (thermal.exposed_heated_area_mm2 * 1e-6)
            } else { 0.0 };
            let stats = serde_json::json!({
                "materialName": opts.material_name,
                "mode": if opts.mode == "transient" { "transient" } else { "steady" },
                "minimumTemperatureC": thermal.minimum_temperature_c,
                "meanTemperatureC": thermal.mean_temperature_c,
                "maximumTemperatureC": thermal.maximum_temperature_c,
                "hotspotMm": thermal.hotspot_mm,
                "heatInputW": thermal.heat_input_w,
                "heatRejectedW": thermal.heat_rejected_w,
                "storageRateW": thermal.storage_rate_w,
                "energyBalanceRelative": thermal.energy_balance_relative,
                "thermalIterations": thermal.iterations,
                "thermalResidual": thermal.relative_residual,
                "timeSteps": thermal.time_steps,
                "finalTimeSeconds": thermal.final_time_seconds,
                "peakTemperatureC": thermal.peak_temperature_c,
                "peakTimeSeconds": thermal.peak_time_seconds,
                "readinessReachedTimeSeconds": thermal.readiness_reached_time_seconds,
                "readinessCompleteTimeSeconds": thermal.readiness_complete_time_seconds,
                "heatedAreaMm2": thermal.exposed_heated_area_mm2,
                "cooledAreaMm2": thermal.exposed_cooled_area_mm2,
                "effectiveHeatFluxWm2": effective_flux,
                "heaterBoundaryModel": "contact-all-exterior-faces-with-selected-normal",
                "sinkBoundaryModel": "fixed-temperature-global-extreme-plane",
                "maxDisplacementMm": Option::<f64>::None,
                "maxVonMisesMpa": Option::<f64>::None,
                "minimumModulusRetention": Option::<f64>::None,
                "minimumStrengthRetention": Option::<f64>::None,
                "conservativeSafetyFactor": Option::<f64>::None,
                "temperatureMarginC": opts.service_limit_c - thermal.maximum_temperature_c,
                "propertyExtrapolated": false,
                "structuralValid": false,
                "materialValidity": "not-requested",
                "materialValidityReason": "Annealing uses the validated thermal solver without coupled structural FEA",
                "densityAware": use_optimized,
                "activeCells": active_cells,
                "nx": grid.nx, "ny": grid.ny, "nz": grid.nz, "h": grid.h,
                "structuralIterations": 0,
                "structuralResidual": Option::<f64>::None,
                "structuralConverged": false,
            }).to_string();
            let zero_displacements = if opts.include_visualization_fields {
                vec![0.0f32; self.mesh.tris.len() * 9]
            } else {
                Vec::new()
            };
            let array = js_sys::Array::new();
            array.push(&JsValue::from(stats));
            array.push(&js_sys::Float32Array::from(thermal.temperatures_c.as_slice()));
            array.push(&js_sys::Float64Array::from(thermal.history.as_slice()));
            array.push(&js_sys::Float32Array::from(zero_displacements.as_slice()));
            array.push(&js_sys::Float32Array::from(material_fraction.as_slice()));
            array.push(&js_sys::Float32Array::from(vertex_temperatures.as_slice()));
            return Ok(array);
        }
        let lower_property_limit = opts.reference_temperature_c - 50.0;
''',
        "thermal-only structural bypass",
    )

    replace_once(
        client,
        """  timeStepSeconds: number;
  fixedSurfaceEnabled: boolean;
""",
        """  timeStepSeconds: number;
  /** Annealing-only: skip coupled structural FEA and stress recovery. */
  thermalOnly?: boolean;
  /** Return mesh-sized vertex/displacement fields when the result is visible in 3D. */
  includeVisualizationFields?: boolean;
  fixedSurfaceEnabled: boolean;
""",
        "TypeScript thermal-only options",
    )
    replace_once(
        client,
        '  materialValidity: "valid" | "outside-model";\n',
        '  materialValidity: "valid" | "outside-model" | "not-requested";\n',
        "thermal-only material validity type",
    )

    required = (
        (wasm, "thermal_only: bool"),
        (wasm, "include_visualization_fields: bool"),
        (wasm, "if opts.thermal_only"),
        (wasm, '"materialValidity": "not-requested"'),
        (client, "thermalOnly?: boolean"),
        (client, "includeVisualizationFields?: boolean"),
    )
    for path, contract in required:
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Annealing thermal-only contract {contract!r} is missing from {path}")

    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
