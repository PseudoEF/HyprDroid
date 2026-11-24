# Single-use isolated worker reserve

## Pixel 9 baseline

The owner reported **11/11** successful checks on a physical Pixel 9 running Android 17. These results apply to the previously signed release-gate APKs, without the reserve prototype.

- [HyprDroid 10211](https://github.com/PseudoEF/HyprDroid/actions/runs/45451038859/artifacts/14947463556), 5/5. In-place update preserved data, HOME remained available, the first Increment worked after cold launch, two Clock Tiles ran for ten minutes, and force-stop preserved state.
- [HyprDroid Developer](https://github.com/PseudoEF/HyprDroid/actions/runs/45451037820/artifacts/14947938494), 6/6. In-place update preserved data, HOME remained unavailable, local import and `hypr dev info my-tile` worked, my-tile execution was blocked, and reimporting the public key preserved revocation.

## Prototype

The experiment is confined to the Android instrumentation configuration. The application has no performance setting. The comparison uses the same APK and binding implementation, with instrumentation selecting either cold-only or reserve mode before launching Activity.

Both normal and prepared bindings acquire a permit from the same two-permit process budget. The existing 2-active/4-pending operation admission still applies. A prepared binding supplies a permit to its new owner and is detached from the reserve immediately. A miss takes the cold path and retires an unfinished reserve. Binding setup has a five-second deadline and a ready reserve has a three-second lifetime. Releasing the last visible registration, Activity stop, memory callbacks and Runtime circuit shutdown retire the reserve. A mount or completed interactive operation may prepare another while admission is idle. Timer events and expiry do not refill it.

The reserve sends no session, archive, Lua bytes, state or data. On assignment, authorization is tested again. For the official tile flow, enqueueing session creation and dispatch shares the existing generation fence. Callback acceptance checks the current operation, then the existing generation and persistence checks protect state and UI publication. Every assigned worker is disposed after its first operation. Released published Binder handles are accounted for until death is observed. If death cannot be confirmed, new work is blocked. Android owns process teardown for binds cancelled before a Binder was published. Sampling process PSS is approximate; the Lua VM's 2 MiB arena is a separate bound.

A fresh launch with no ready reserve still starts cold. The policy deliberately leaves an idle workspace and a pre-existing queue alone. It does not claim to eliminate the initial concurrent Clock startup or provide a scheduler that changes the order of existing user and timer work.

## Measurement

The Android 15 comparison runs 0, 1, 2, 4 and 8 Clock Tiles in both modes on the same emulator profile, with alternate order and cleared fixture app data per trial. The window includes foreground timers, up to six UI interaction attempts, a workspace switch, an Activity stop/resume and a final background window. Failed interactions and missing initial frames fail the trial; the remaining matrix and raw results are retained.

Reports contain user latency percentiles, pipeline timing, worker bindings, worker PID observations, clean/observed exits, reserve hits, cold attempts and unused candidates. Worker CPU is summed from exit events observed in the isolated process. A worker without an exit log is not represented in that sum. Core CPU is process CPU time within the measured test window. PSS snapshots are sampled from Android every two seconds and include instrumentation overhead for Core. Sampled peaks may miss transient allocations. With six user samples, a trial's p95 and p99 are both its largest observed latency.

The remaining release gate includes the original Hello lifecycle incident, the DocumentsUI timeout, malformed or closed descriptors, malformed worker responses and late callbacks across Binder after revocation. The experiment adds callback checks and phase telemetry. The existing audit remains the reference for what has actually been exercised.
