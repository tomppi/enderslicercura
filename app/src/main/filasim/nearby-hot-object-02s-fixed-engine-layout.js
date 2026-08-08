  // Fixed engine assembly with hot-component layout presets. The engine is a
  // rigid cluster of hot components (engine block plus a closely coupled
  // turbocharger) defined at fixed world-space offsets from an engine anchor.
  // The whole engine is placed once (free X/Y/Z anchor controls or a single
  // drag) and every component moves with it, so the two sources never separate.
  // The block maps to the existing primary source and the turbo to the existing
  // secondary source, reusing the calibrated two-source radiative exchange.
  const ENGINE_LAYOUT_PRESETS = Object.freeze({
    custom: Object.freeze({ label: "Custom engine layout", components: [] }),
    engine_turbo_cluster: Object.freeze({
      label: "Engine + turbocharger cluster",
      description: "Engine block with a closely coupled turbocharger on the exhaust side.",
      components: [
        Object.freeze({
          slot: "primary",
          label: "Engine block",
          offsetMm: Object.freeze([0, 0, 0]),
          temperatureC: 110,
          endTemperatureC: 125,
          diameterMm: 180,
          emissivity: 0.82,
          gapMm: 25,
        }),
        Object.freeze({
          slot: "secondary",
          label: "Turbocharger",
          offsetMm: Object.freeze([160, 60, 40]),
          temperatureC: 850,
          endTemperatureC: 950,
          diameterMm: 120,
          emissivity: 0.88,
          gapMm: 40,
        }),
      ],
    }),
  });

  let engineAnchorMm = [0, 0, 0];

  function engineLayoutName() {
    return value("engineLayout") || "custom";
  }

  function engineLayoutActive() {
    const name = engineLayoutName();
    return name !== "custom" && Boolean(ENGINE_LAYOUT_PRESETS[name]);
  }

  function engineLayoutComponents() {
    const preset = ENGINE_LAYOUT_PRESETS[engineLayoutName()];
    return preset?.components || [];
  }

  function engineLayoutComponent(slot) {
    return engineLayoutComponents().find((component) => component.slot === slot) || null;
  }

  function engineLayoutOptions() {
    return Object.entries(ENGINE_LAYOUT_PRESETS)
      .map(([key, preset]) => `<option value="${key}">${preset.label}</option>`)
      .join("");
  }

  function engineAnchorVector() {
    return [
      finite(value("engineAnchorXMm"), "engine X position", -100000, 100000),
      finite(value("engineAnchorYMm"), "engine Y position", -100000, 100000),
      finite(value("engineAnchorZMm"), "engine Z position", -100000, 100000),
    ];
  }

  function engineBlockTarget() {
    if (!selected || !Array.isArray(selected.point) || selected.point.length !== 3) return null;
    const anchor = engineAnchorVector();
    return selected.point.map((coordinate, axis) => Number(coordinate) + Number(anchor[axis]));
  }

  function engineComponentTarget(slot) {
    const component = engineLayoutComponent(slot);
    const blockTarget = engineBlockTarget();
    if (!component || !blockTarget) return null;
    return blockTarget.map((coordinate, axis) => Number(coordinate) + Number(component.offsetMm[axis]));
  }

  function engineSourceMarker(slot) {
    const component = engineLayoutComponent(slot);
    if (!component || !selected?.normal) return null;
    const target = engineComponentTarget(slot);
    if (!target) return null;
    const base = {
      target,
      normal: selected.normal.map(Number),
      gapMm: Number(component.gapMm),
      diameterMm: Number(component.diameterMm),
    };
    if (slot === "primary") {
      return {
        ...base,
        shape: "engine",
        blockLengthMm: Number(component.blockLengthMm ?? component.diameterMm * 1.6),
        blockWidthMm: Number(component.blockWidthMm ?? component.diameterMm),
        blockHeightMm: Number(component.blockHeightMm ?? component.diameterMm * 0.8),
      };
    }
    return {
      ...base,
      shape: "turbo",
      turboDiameterMm: Number(component.turboDiameterMm ?? component.diameterMm),
      turboLengthMm: Number(component.turboLengthMm ?? component.diameterMm * 0.7),
    };
  }

  const engineLayoutCreateGroupBase = createGroup;
  createGroup = function createGroupWithFixedEngineLayout() {
    const group = engineLayoutCreateGroupBase();
    const scenarioSelect = group.querySelector("#ti-engineScenario")?.closest("label");
    scenarioSelect?.insertAdjacentHTML("afterend", `
      <label class="ti-select"><span>Engine layout preset</span>
        <select id="ti-engineLayout">${engineLayoutOptions()}</select>
      </label>
      <div id="ti-engine-layout-fields" class="ti-hidden">
        <div class="ti-status dim"><b>Fixed engine assembly.</b> The block and turbo are positioned at fixed offsets from the engine anchor. Move the engine as one unit below; drag either source sphere to re-position the whole engine.</div>
        <div class="ti-grid">
          ${field("engineAnchorXMm", "Engine anchor X position (mm)", 0, 1)}
          ${field("engineAnchorYMm", "Engine anchor Y position (mm)", 0, 1)}
          ${field("engineAnchorZMm", "Engine anchor Z position (mm)", 0, 1)}
        </div>
        <div class="ti-actions">
          <button id="ti-drag-engine" type="button">Move whole engine</button>
          <button id="ti-centre-engine" type="button">Centre engine on part</button>
        </div>
        <div id="ti-engine-layout-detail" class="ti-status dim">No engine layout selected.</div>
      </div>
    `);
    return group;
  };

  function syncEngineLayoutUi() {
    const active = engineLayoutActive();
    const fields = document.getElementById("ti-engine-layout-fields");
    if (fields) fields.classList.toggle("ti-hidden", !active);
    const detail = document.getElementById("ti-engine-layout-detail");
    if (!detail) return;
    if (!active) {
      detail.textContent = "No engine layout selected.";
      return;
    }
    const preset = ENGINE_LAYOUT_PRESETS[engineLayoutName()];
    const parts = engineLayoutComponents().map((component) => {
      const offset = component.offsetMm.map((n) => format(n, 1)).join(", ");
      return `${component.label} @ +${offset} mm · ${format(component.temperatureC, 0)} °C · ${format(component.diameterMm, 0)} mm`;
    });
    detail.textContent = `${preset.label}: ${parts.join(" | ")}`;
  }

  function applyEngineLayout(name) {
    const preset = ENGINE_LAYOUT_PRESETS[name];
    if (!preset || name === "custom") {
      syncEngineLayoutUi();
      return;
    }
    const block = engineLayoutComponent("primary");
    const turbo = engineLayoutComponent("secondary");
    if (block) {
      applySourceType("source", "engine_surface");
      setValue("sourceTemperatureC", block.temperatureC);
      setValue("sourceEndTemperatureC", block.endTemperatureC);
      setValue("sourceDiameterMm", block.diameterMm);
      setValue("sourceEmissivity", block.emissivity);
      setValue("sourceGapMm", block.gapMm);
    }
    if (turbo) {
      const enabled = document.getElementById("ti-source2Enabled");
      if (enabled) enabled.checked = true;
      applySourceType("source2", "turbo_moderate");
      setValue("source2TemperatureC", turbo.temperatureC);
      setValue("source2EndTemperatureC", turbo.endTemperatureC);
      setValue("source2DiameterMm", turbo.diameterMm);
      setValue("source2Emissivity", turbo.emissivity);
      setValue("source2GapMm", turbo.gapMm);
      syncSource2Ui();
    }
    syncEngineLayoutUi();
    renderCombinedHeatSourceMarkers();
  }

  function centreEngine() {
    setValue("engineAnchorXMm", 0);
    setValue("engineAnchorYMm", 0);
    setValue("engineAnchorZMm", 0);
    renderCombinedHeatSourceMarkers();
  }

  function beginEngineDrag() {
    if (!selected) {
      const status = input("status");
      status.className = "ti-status ti-warning";
      status.textContent = "Select or place the engine block point before dragging the engine.";
      return;
    }
    renderCombinedHeatSourceMarkers();
    window.dispatchEvent(new CustomEvent(HEAT_SOURCE_DRAG_MODE_EVENT, { detail: 1 }));
    const status = input("status");
    status.className = "ti-status dim";
    status.textContent = "Drag the orange engine block sphere in the 3D viewer. The whole engine moves together.";
  }

  const engineLayoutCollectOptionsBase = collectOptions;
  collectOptions = function collectOptionsWithFixedEngineLayout() {
    const options = engineLayoutCollectOptionsBase();
    options.engineLayout = engineLayoutName();
    if (!engineLayoutActive()) return options;
    const block = engineLayoutComponent("primary");
    const turbo = engineLayoutComponent("secondary");
    const blockTarget = engineBlockTarget();
    if (!block || !blockTarget) {
      throw new Error("Select the model point nearest the engine block first.");
    }
    options.sourceTargetMm = blockTarget.map(Number);
    options.sourceNormal = selected.normal.map(Number);
    options.sourceGapMm = Number(block.gapMm);
    options.sourceDiameterMm = Number(block.diameterMm);
    options.sourceTemperatureC = Number(options.sourceTemperatureC);
    if (turbo) {
      const turboTarget = engineComponentTarget("secondary");
      if (!turboTarget) throw new Error("Unable to position the engine turbocharger.");
      options.source2Enabled = true;
      options.source2TargetMm = turboTarget.map(Number);
      options.source2Normal = selected.normal.map(Number);
      options.source2GapMm = Number(turbo.gapMm);
      options.source2DiameterMm = Number(turbo.diameterMm);
      options.source2TemperatureC = Number(options.source2TemperatureC);
    } else {
      options.source2Enabled = false;
    }
    options.engineAnchorMm = engineAnchorVector().map(Number);
    options.engineAssemblyModel = "fixed-engine-layout-rigid-cluster-v1";
    return options;
  };

  const engineLayoutMarkersBase = combinedHeatSourceMarkers;
  combinedHeatSourceMarkers = function combinedHeatSourceMarkersWithEngineLayout() {
    const toggle = document.getElementById("ti-showSourceMarkers");
    if (toggle && !toggle.checked) return null;
    if (!engineLayoutActive()) return engineLayoutMarkersBase();
    const markers = [];
    const blockMarker = engineSourceMarker("primary");
    if (blockMarker) markers.push({ ...blockMarker, sourceId: 1, label: "Engine block" });
    const turbo = engineLayoutComponent("secondary");
    if (turbo && checked("source2Enabled")) {
      const turboMarker = engineSourceMarker("secondary");
      if (turboMarker) markers.push({ ...turboMarker, sourceId: 2, label: "Turbocharger" });
    }
    return markers.length ? { markers } : null;
  };

  const engineLayoutRenderSelectionBase = renderSelection;
  renderSelection = function renderSelectionWithEngineLayout() {
    engineLayoutRenderSelectionBase();
    if (!engineLayoutActive()) return;
    const box = document.getElementById("ti-source-selection");
    if (!box || !selected) return;
    const blockTarget = engineBlockTarget();
    const p = blockTarget?.map((n) => Number(n).toFixed(2)).join(", ");
    box.textContent = `Engine block anchor: ${p} mm (engine placement + selected point). The turbo is offset from this anchor.`;
  };

  const engineLayoutBindBase = bind;
  bind = function bindWithFixedEngineLayout(group) {
    engineLayoutBindBase(group);
    group.querySelector("#ti-engineLayout")?.addEventListener("change", (event) => {
      applyEngineLayout(event.target.value);
      invalidate("Engine layout changed; calculate again.");
    });
    group.querySelector("#ti-drag-engine")?.addEventListener("click", beginEngineDrag);
    group.querySelector("#ti-centre-engine")?.addEventListener("click", () => {
      centreEngine();
      invalidate("Engine centred; calculate again.");
    });
    for (const id of ["engineAnchorXMm", "engineAnchorYMm", "engineAnchorZMm"]) {
      group.querySelector(`#ti-${id}`)?.addEventListener("change", () => {
        renderCombinedHeatSourceMarkers();
        invalidate("Engine position changed; calculate again.");
      });
    }
  };

  const engineLayoutRestoreDraftBase = restoreDraft;
  restoreDraft = function restoreDraftWithEngineLayout() {
    engineLayoutRestoreDraftBase();
    applyEngineLayout(engineLayoutName());
  };

  const engineLayoutRenderResultsBase = renderResults;
  renderResults = function renderResultsWithEngineLayout() {
    engineLayoutRenderResultsBase();
    if (!latest || !latest.options?.engineLayout || latest.options.engineLayout === "custom") return;
    const preset = ENGINE_LAYOUT_PRESETS[latest.options.engineLayout];
    input("kpis").insertAdjacentHTML("beforeend", [
      kpi("Engine layout", preset?.label || latest.options.engineLayout),
      kpi("Engine anchor", latest.options.engineAnchorMm
        ? latest.options.engineAnchorMm.map((n) => format(n, 1)).join(", ")
        : "—"),
      kpi("Engine movement", "Rigid assembly (block + turbo stay fixed together)"),
    ].join(""));
    const note = input("result-note");
    note.textContent += `\n${preset?.description || "Fixed engine assembly layout."} The layout offsets, temperatures, sizes and emissivities are editable engineering starting assumptions, not vehicle-specific measured values.`;
  };

  const engineLayoutCollectReportBase = collectReport;
  collectReport = function collectReportWithEngineLayout() {
    const report = engineLayoutCollectReportBase();
    if (latest?.options?.engineLayout && latest.options.engineLayout !== "custom") {
      const preset = ENGINE_LAYOUT_PRESETS[latest.options.engineLayout];
      report.engineLayout = {
        name: latest.options.engineLayout,
        label: preset?.label || latest.options.engineLayout,
        model: latest.options.engineAssemblyModel || "fixed-engine-layout-rigid-cluster-v1",
        anchorMm: latest.options.engineAnchorMm || [0, 0, 0],
        components: engineLayoutComponents().map((component) => ({
          slot: component.slot,
          label: component.label,
          offsetMm: [...component.offsetMm],
          temperatureC: component.temperatureC,
          endTemperatureC: component.endTemperatureC,
          diameterMm: component.diameterMm,
          emissivity: component.emissivity,
          gapMm: component.gapMm,
        })),
      };
    }
    return report;
  };

  // A drag on either source sphere moves the whole engine: re-derive the
  // secondary marker from the primary so the cluster never separates.
  window.addEventListener(HEAT_SOURCE_DRAG_EVENT, (event) => {
    if (!engineLayoutActive()) return;
    const sourceId = Number(event?.detail?.sourceId);
    if (sourceId !== 1 && sourceId !== 2) return;
    renderCombinedHeatSourceMarkers();
    invalidate("Engine moved; calculate again.");
  });

  window.EnderSlicerEngineLayoutTestApi = Object.freeze({
    ENGINE_LAYOUT_PRESETS,
    engineLayoutActive,
    engineLayoutComponents,
    engineLayoutComponent,
    engineAnchorVector,
    engineBlockTarget,
    engineComponentTarget,
    engineSourceMarker,
  });
