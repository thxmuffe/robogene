function createCoalescedRunner(task) {
  let inflight = false;
  let queued = false;

  const run = () => {
    if (inflight) {
      queued = true;
      return Promise.resolve(false);
    }

    inflight = true;
    return Promise.resolve()
      .then(() => task())
      .finally(() => {
        inflight = false;
        if (queued) {
          queued = false;
          void run();
        }
      });
  };

  return run;
}

module.exports = {
  createCoalescedRunner,
};
