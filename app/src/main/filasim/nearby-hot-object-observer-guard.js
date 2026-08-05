/* Prevent Nearby Hot Object MutationObserver feedback loops on Android. */
(() => {
  "use strict";

  const MOUNT_ID = "enderslicer-thermal-integrity-mount";
  const CALLBACK_NAME = "installUi";

  function nodeContainsThermalMount(node) {
    if (!node || typeof node !== "object") return false;
    if (node.id === MOUNT_ID) return true;
    return typeof node.querySelector === "function" && Boolean(node.querySelector(`#${MOUNT_ID}`));
  }

  function recordsAddThermalMount(records) {
    return Array.from(records || []).some((record) =>
      Array.from(record?.addedNodes || []).some(nodeContainsThermalMount)
    );
  }

  function installObserverGuard() {
    const NativeMutationObserver = window.MutationObserver;
    if (!NativeMutationObserver || NativeMutationObserver.__enderSlicerNearbyObserverGuard) return;

    const WrappedMutationObserver = new Proxy(NativeMutationObserver, {
      construct(Target, args) {
        const callback = args[0];
        // installUi is called once directly before its observer is created. Keep
        // the mount identity that was already installed so mutations made by
        // that installation cannot immediately trigger a second installation.
        let installedMount = document.getElementById(MOUNT_ID);
        const guardedCallback =
          typeof callback === "function" && callback.name === CALLBACK_NAME
            ? (records, observer) => {
                const currentMount = document.getElementById(MOUNT_ID);
                if (!currentMount) {
                  // React reuses the same panel element for T and A. Reset when
                  // the thermal id disappears so that the same DOM node can be
                  // installed again when the user switches back to T.
                  installedMount = null;
                  return;
                }
                if (currentMount === installedMount) return;
                installedMount = currentMount;
                callback(records, observer);
              }
            : callback;
        return Reflect.construct(Target, [guardedCallback]);
      },
    });

    Object.defineProperty(WrappedMutationObserver, "__enderSlicerNearbyObserverGuard", {
      value: true,
    });
    window.MutationObserver = WrappedMutationObserver;
  }

  window.EnderSlicerNearbyObserverTestApi = Object.freeze({
    recordsAddThermalMount,
  });

  installObserverGuard();
})();
