  // Annealing needs the transient temperature solution, not the coupled
  // structural solve used by Thermal Integrity. Heating retains mesh-sized
  // fields for the visible 3D result; cooling returns only cell diagnostics.
  const thermalOnlyOptionsBase = thermalOptions;
  thermalOptions = function annealingThermalOnlyOptions(common, stage, initialField = null) {
    const options = thermalOnlyOptionsBase(common, stage, initialField);
    options.thermalOnly = true;
    options.includeVisualizationFields = stage === "heating";
    return options;
  };
