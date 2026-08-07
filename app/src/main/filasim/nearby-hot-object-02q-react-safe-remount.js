  // Keep the imperative Nearby Hot Object panel from being moved between React
  // workspace roots. Reparenting a live custom subtree while React is replacing
  // StepPanel can leave React with a stale host-sibling reference and trigger:
  // "insertBefore: the node before which the new node is to be inserted is not
  // a child of this node". If the mount identity changes, wait until the next
  // animation frame, discard only our stale panel, then let the normal composed
  // installer create a fresh panel in the stable mount.
  const REACT_SAFE_THERMAL_MOUNT_ID = "enderslicer-thermal-integrity-mount";
  let reactSafeInstallScheduled = false;
  const reactSafeInstallUiBase = installUi;

  function scheduleReactSafeThermalInstall() {
    if (reactSafeInstallScheduled) return;
    reactSafeInstallScheduled = true;
    const schedule = typeof window.requestAnimationFrame === "function"
      ? window.requestAnimationFrame.bind(window)
      : (callback) => window.setTimeout(callback, 0);
    schedule(() => {
      reactSafeInstallScheduled = false;
      const mount = document.getElementById(REACT_SAFE_THERMAL_MOUNT_ID);
      if (!mount) return;
      const staleGroup = document.getElementById(GROUP_ID);
      if (staleGroup && staleGroup.parentElement !== mount) {
        staleGroup.remove();
      }
      installUi();
    });
  }

  installUi = function installUiWithoutCrossRootReparent() {
    const mount = document.getElementById(REACT_SAFE_THERMAL_MOUNT_ID);
    if (!mount) return false;
    const group = document.getElementById(GROUP_ID);
    if (group && group.parentElement !== mount) {
      scheduleReactSafeThermalInstall();
      return false;
    }
    return reactSafeInstallUiBase();
  };

  window.EnderSlicerNearbyReactSafeMountTestApi = Object.freeze({
    scheduleReactSafeThermalInstall,
  });
