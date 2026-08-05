/* Keep Anneal search durations within the Android 2,000-step stage budget. */
(() => {
  "use strict";

  const MAX_STAGE_STEPS = 2000;
  const WORKSPACE_EVENT = "enderslicer-annealing-workspace";
  const HINT_ID = "ac-step-budget-hint";

  function positiveNumber(value, label) {
    const number = Number(value);
    if (!Number.isFinite(number) || number <= 0) {
      throw new Error(`${label} must be greater than zero`);
    }
    return number;
  }

  function planStageBudget(values) {
    const maxHeatingHours = positiveNumber(values.maxHeatingHours, "maximum heating search");
    const maxCoolingHours = positiveNumber(values.maxCoolingHours, "maximum cooling search");
    const requestedTimeStepSeconds = positiveNumber(values.requestedTimeStepSeconds, "time step");
    const simulateCooling = values.simulateCooling !== false;
    const limitingHours = Math.max(maxHeatingHours, simulateCooling ? maxCoolingHours : 0);
    // Use whole seconds because the Android control exposes a 1 s step and a
    // small upward round is safer than landing just over 2,000 through FP error.
    const minimumTimeStepSeconds = Math.max(
      1,
      Math.ceil((limitingHours * 3600) / MAX_STAGE_STEPS)
    );
    const effectiveTimeStepSeconds = Math.max(
      requestedTimeStepSeconds,
      minimumTimeStepSeconds
    );
    return {
      requestedTimeStepSeconds,
      minimumTimeStepSeconds,
      effectiveTimeStepSeconds,
      adjusted: effectiveTimeStepSeconds !== requestedTimeStepSeconds,
      heatingSteps: Math.ceil((maxHeatingHours * 3600) / effectiveTimeStepSeconds),
      coolingSteps: simulateCooling
        ? Math.ceil((maxCoolingHours * 3600) / effectiveTimeStepSeconds)
        : 0,
      maxStageSteps: MAX_STAGE_STEPS,
    };
  }

  function element(id) {
    return document.getElementById(id);
  }

  function ensureHint() {
    let hint = element(HINT_ID);
    if (hint) return hint;
    const timeStep = element("ac-timeStepSeconds");
    const grid = timeStep?.closest?.(".ac-grid");
    if (!grid?.parentElement) return null;
    hint = document.createElement("div");
    hint.id = HINT_ID;
    hint.className = "ac-status dim";
    grid.parentElement.insertBefore(hint, grid.nextSibling);
    return hint;
  }

  function currentBudget() {
    const timeStep = element("ac-timeStepSeconds");
    const heating = element("ac-maxHeatingHours");
    const cooling = element("ac-maxCoolingHours");
    const simulateCooling = element("ac-simulateCooling");
    if (!timeStep || !heating || !cooling || !simulateCooling) return null;
    return planStageBudget({
      maxHeatingHours: heating.value,
      maxCoolingHours: cooling.value,
      requestedTimeStepSeconds: timeStep.value,
      simulateCooling: simulateCooling.checked,
    });
  }

  function renderBudget(plan, committed) {
    const hint = ensureHint();
    if (!hint) return;
    const counts = plan.coolingSteps > 0
      ? `${plan.heatingSteps} heating / ${plan.coolingSteps} cooling steps`
      : `${plan.heatingSteps} heating steps`;
    if (plan.adjusted) {
      hint.className = "ac-status ac-warning";
      hint.textContent = committed
        ? `Time step automatically increased from ${plan.requestedTimeStepSeconds} s to ${plan.effectiveTimeStepSeconds} s to fit the ${plan.maxStageSteps}-step Android limit (${counts}).`
        : `These search durations require at least ${plan.minimumTimeStepSeconds} s per step (${counts} after adjustment). The value will be corrected when calculation starts.`;
    } else {
      hint.className = "ac-status dim";
      hint.textContent = `Transient workload: ${counts}; limit ${plan.maxStageSteps} per stage.`;
    }
  }

  function applyBudget(commitAdjustment) {
    let plan;
    try {
      plan = currentBudget();
    } catch (_) {
      return false; // The main form validator owns malformed input messages.
    }
    if (!plan) return false;
    if (commitAdjustment && plan.adjusted) {
      const timeStep = element("ac-timeStepSeconds");
      if (timeStep) timeStep.value = String(plan.effectiveTimeStepSeconds);
    }
    renderBudget(plan, Boolean(commitAdjustment && plan.adjusted));
    return true;
  }

  function scheduleMountSync() {
    // React may commit the panel after the workspace event returns. A short
    // retry sequence handles both synchronous and deferred Android WebView
    // commits without observing mutations inside the panel.
    for (const delay of [0, 16, 80]) {
      window.setTimeout(() => applyBudget(true), delay);
    }
  }

  document.addEventListener(
    "click",
    (event) => {
      if (!event.target?.closest?.("#ac-run")) return;
      // Capture runs before the calculator's button listener, so collectCommon
      // sees the corrected value and never throws for the shipped defaults.
      applyBudget(true);
    },
    true
  );
  document.addEventListener(
    "input",
    (event) => {
      const id = event.target?.id || "";
      if (["ac-timeStepSeconds", "ac-maxHeatingHours", "ac-maxCoolingHours", "ac-simulateCooling"].includes(id)) {
        applyBudget(false);
      }
    },
    true
  );
  document.addEventListener(
    "change",
    (event) => {
      if (event.target?.id === "ac-simulateCooling") applyBudget(false);
    },
    true
  );
  window.addEventListener(WORKSPACE_EVENT, (event) => {
    if (event?.detail) scheduleMountSync();
  });

  window.EnderSlicerAnnealingStepBudgetTestApi = Object.freeze({
    maxStageSteps: MAX_STAGE_STEPS,
    planStageBudget,
  });
})();
