/* Bind the existing Thermal Integrity form to filaSim's live material library. */
(() => {
  "use strict";
  const profiles = window.EnderSlicerFilaSimProfiles;
  if (!profiles) return;
  const GROUP_ID = "enderslicer-thermal-integrity";
  const SOURCE_ID = "ti-material-source";
  let observer = null;
  let initialized = false;
  let selectedMaterialName = null;

  function setValue(id, value) {
    const element = document.getElementById(`ti-${id}`);
    if (element && value != null) element.value = String(value);
  }
  function draftMaterialName() {
    try {
      const draft = JSON.parse(localStorage.getItem("enderslicer.thermalIntegrity.v1") || "null");
      return typeof draft?.materialName === "string" ? draft.materialName : null;
    } catch (_) { return null; }
  }
  function sourceNote(group) {
    let note = document.getElementById(SOURCE_ID);
    if (note) return note;
    note = document.createElement("div");
    note.id = SOURCE_ID;
    note.className = "ti-status dim";
    const details = group.querySelector("details");
    if (details) group.insertBefore(note, details);
    else group.appendChild(note);
    return note;
  }
  function markInherited() {
    for (const id of ["densityKgM3", "alphaXyPerK", "alphaZPerK", "youngsModulusMpa", "poissonRatio", "referenceStrengthMpa"]) {
      const element = document.getElementById(`ti-${id}`);
      if (!element) continue;
      element.readOnly = true;
      element.dataset.filasimSource = "true";
      element.title = "Inherited from the selected filaSim material profile";
    }
  }
  function applyMaterial(name, resetComplements) {
    const resolved = profiles.resolveMaterial(name);
    setValue("densityKgM3", resolved.densityKgM3);
    setValue("alphaXyPerK", resolved.alphaXyPerK);
    setValue("alphaZPerK", resolved.alphaZPerK);
    setValue("youngsModulusMpa", resolved.youngsModulusMpa);
    setValue("poissonRatio", resolved.poissonRatio);
    setValue("referenceStrengthMpa", resolved.referenceStrengthMpa);
    if (resetComplements) {
      setValue("conductivityXWmK", resolved.conductivityXWmK);
      setValue("conductivityYWmK", resolved.conductivityYWmK);
      setValue("conductivityZWmK", resolved.conductivityZWmK);
      setValue("specificHeatJkgK", resolved.specificHeatJkgK);
      setValue("serviceLimitC", resolved.serviceLimitC);
      setValue("emissivity", resolved.emissivity);
    }
    markInherited();
    const group = document.getElementById(GROUP_ID);
    if (group) {
      const material = resolved.filaSim;
      sourceNote(group).textContent =
        `Material source: ${resolved.profileSource} · ${resolved.name}. ` +
        `filaSim supplies density, E, ν, strengths, shrink (${(Number(material.shrink || 0) * 100).toFixed(2)}% XY / ${(Number(material.shrinkZ || 0) * 100).toFixed(2)}% Z), locking temperature and CTE. ` +
        `Conductivity, heat capacity, through-layer CTE when absent, and service limit are ${resolved.family} complements.`;
    }
    return resolved;
  }
  function sync(preferActive = false) {
    const group = document.getElementById(GROUP_ID);
    const select = document.getElementById("ti-preset");
    if (!group || !select) return false;
    const snapshot = profiles.getSnapshot();
    const materials = profiles.fdmMaterials(snapshot);
    const draftName = draftMaterialName();
    const desired = preferActive
      ? snapshot.activeMaterialName
      : (draftName || select.value || snapshot.activeMaterialName);
    const selected = profiles.populateSelect(select, materials, desired);
    const materialChanged = selected !== selectedMaterialName;
    const resetComplements = initialized ? materialChanged : !draftName;
    applyMaterial(selected, resetComplements);
    initialized = true;
    selectedMaterialName = selected;
    if (!select.dataset.filasimBound) {
      select.dataset.filasimBound = "true";
      select.addEventListener("change", () => {
        profiles.setActiveMaterial(select.value);
        applyMaterial(select.value, true);
        initialized = true;
        selectedMaterialName = select.value;
      });
    }
    return true;
  }

  window.addEventListener(profiles.eventName, () => sync(true));
  sync(false);
  observer = new MutationObserver(() => sync(false));
  observer.observe(document.documentElement, { childList: true, subtree: true });
})();
