  // Preserve each environment preset's established air volume while giving it a visible shape.
  applyEnclosureBoxPreset = function applyEnclosureBoxPresetPreservingVolume(mode) {
    const ratios = {
      engine_running: [1.6, 1.2, 1],
      engine_heat_soak: [1.6, 1.2, 1],
      ventilated_enclosure: [1.25, 1, 1],
      sealed_enclosure: [1, 1, 1],
      custom: [1, 1, 1],
    }[mode];
    if (!ratios) return;
    const volumeL = Math.max(0.001, Number(value("enclosureVolumeL")) || 27);
    const scale = Math.cbrt(
      volumeL * 1_000_000 / (ratios[0] * ratios[1] * ratios[2]),
    );
    setValue("enclosureWidthMm", (scale * ratios[0]).toFixed(1));
    setValue("enclosureDepthMm", (scale * ratios[1]).toFixed(1));
    setValue("enclosureHeightMm", (scale * ratios[2]).toFixed(1));
    setValue("enclosureOffsetXmm", 0);
    setValue("enclosureOffsetYmm", 0);
    setValue("enclosureOffsetZmm", 0);
    syncCalculatedEnclosureVolume();
    renderEnclosureBox();
  };

  // Give the synchronous viewer event and its queued automatic projection one frame
  // to populate source geometry before the legacy option collector validates it.
  const spatialAutomaticProjectionRunBase = runAnalysis;
  runAnalysis = async function runAnalysisWithAutomaticSourceProjection() {
    renderCombinedHeatSourceMarkers();
    await new Promise((resolve) => window.requestAnimationFrame(resolve));
    return spatialAutomaticProjectionRunBase();
  };
