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
        const guardedCallback =
          typeof callback === "function" && callback.name === CALLBACK_NAME
            ? (records, observer) => {
                // React adding the dedicated thermal mount is the only change
                // that requires installation. Mutations made inside the panel
                // must not call installUi again or the WebView enters a feedback
                // loop and freezes when the T station is opened.
                if (recordsAddThermalMount(records)) callback(records, observer);
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
