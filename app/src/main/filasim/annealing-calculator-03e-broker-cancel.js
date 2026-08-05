  cancelRun = function cancelAnnealingThroughBroker() {
    const cancel = input("cancel");
    cancel.disabled = true;
    if (broker.cancelActive()) {
      setProgress("Cancelling", Number(input("progress-bar").value) / 100, "Waiting for a safe solver cancellation checkpoint…");
      return;
    }
    if (broker.terminateCurrent()) {
      engineWorker = null;
      cancelFlag = null;
      setProgress("Cancelling and restarting", Number(input("progress-bar").value) / 100, "Threaded cancellation is unavailable. The filaSim worker will restart and reload the model.");
      setTimeout(() => window.location.reload(), 100);
    }
  };
