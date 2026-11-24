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

## Android 15 results

The [first prototype run](https://github.com/PseudoEF/HyprDroid/actions/runs/45465278011) and the [follow-up with reserve lifecycle assertions](https://github.com/PseudoEF/HyprDroid/actions/runs/45465707896) passed their short Runtime suites and separate 20-round cold Hello lifecycle check. The prewarm-only instrumentation passed single-use assignment, two shared permits, trimming, loss of visibility, and idle expiry without a refill. The short cold Runtime burst completed 60/60 requests in 8.671 s (p95 915 ms, max 948 ms). Local builds succeeded for Debug, Developer and Debug instrumentation, and unit tests ran for Debug (233) and Developer (239).

Both matrix runs retained failures. The original startup probe treated a disabled control as a missing frame and sampled at five seconds. In the follow-up 2-tile trace, Activity creation to the first pair of tile activations took about 6.1 s and the first frame was published about 6.6 s after creation. The same run exercised six successful user changes in both modes at 1, 2 and 4 tiles. At 8 tiles both modes missed three of six changes and each declined four operations at the admission cap. Neither mode recorded a worker response timeout in that matrix. The follow-up matrix also measures time spent waiting for an enabled control inside its input figures. The later benchmark separates the two timings.

| Visible Clock Tiles | Input p50, cold → reserve | Input p95/p99/max, cold → reserve | Observed launches, cold → reserve | Hits / unused bind attempts |
| ---: | ---: | ---: | ---: | ---: |
| 0 | — | — | 0 → 0 | 0 / 0 |
| 1 | 318 → 152 ms | 743 → 955 ms | 8 → 10 | 4 / 4 |
| 2 | 371 → 135 ms | 956 → 1034 ms | 13 → 13 | 4 / 4 |
| 4 | 435 → 144 ms | 1126 → 1085 ms | 21 → 18 | 3 / 2 |
| 8 | 1313 → 1322 ms | 1381 → 1427 ms | 65 → 62 | 1 / 1 |

Source: [follow-up benchmark artifact, including failed instrumentation](https://github.com/PseudoEF/HyprDroid/actions/runs/45465707896/artifacts/14956797031). The last row includes only the three successful input events per mode. Worker launch observations come from service entry; speculative binds cancelled before service entry can exceed this count.

Across seven samples taken while a ready reserve was held, aggregate worker PSS was **29,537–30,641 KiB** (28.8–29.9 MiB). The 0-tile trials and final idle samples showed zero worker PSS. Core sampled peaks with 1–8 tiles were 169–172 MiB and varied by trial. At 8 tiles the cold aggregate worker peak sampled 54.5 MiB; at 1 and 2 tiles the cold sampler missed short-lived workers. These observations do not establish a worst-case device-wide peak or a stable Core memory delta.

Core CPU for cold → reserve was 2871→2706 ms (0 tiles), 3553→3541 ms (1), 3889→3565 ms (2), 4080→3980 ms (4), and 6134→6171 ms (8). CPU summed from observed worker exit records was 1683→2224 ms (1), 1487→2363 ms (2), 4168→2771 ms (4), and 9976→10248 ms (8). Some Android-disposed worker processes emitted no exit record, so these totals are partial. They provide no evidence of a consistent system CPU reduction.

### Follow-up separating readiness and click latency

The [final matrix](https://github.com/PseudoEF/HyprDroid/actions/runs/45466845505/artifacts/14957861247) measured click-to-visible change after detecting an enabled control. Both 0-tile controls passed with zero worker launches. All populated trials still missed the five-second first-frame probe. The 1-, 2- and 4-tile trials subsequently completed 6/6 user changes in both modes. The 8-tile trials repeated 3/6 in both modes, with four admission refusals per mode. There were no worker response timeouts. The short Runtime suite passed separately.

| Tiles | Click p50, cold → reserve | Click p95/p99/max, cold → reserve | Ready-control wait p95, cold → reserve | Launches, cold → reserve | Hits / unused |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | — | — | — | 0 → 0 | 0 / 0 |
| 1 | 621 → 323 ms | 827 → 699 ms | 468 → 586 ms | 11 → 11 | 3 / 5 |
| 2 | 508 → 168 ms | 945 → 592 ms | 661 → 644 ms | 14 → 13 | 3 / 4 |
| 4 | 604 → 345 ms | 1114 → 857 ms | 556 → 881 ms | 22 → 21 | 2 / 2 |
| 8 | 1400 → 967 ms | 1587 → 1392 ms | 549 → 1007 ms | 63 → 67 | 0 / 0 |

The 8-tile latency result has just three successful inputs per mode and no reserve hits; the latency difference cannot be attributed to prewarming. For 1–4 tiles the ready worker occupied about 29 MiB and eight hits cost eleven unused preparations. Across the 1/2/4/8 trials, Core CPU was 5022/5137/5669/8307 ms cold versus 4884/4629/5569/8421 ms with the reserve. Observed worker CPU was 3159/3574/4270/13869 versus 2612/3144/4964/15023 ms, with some Android exits unobserved. Final samples found zero worker PSS after backgrounding, with Core around 87–92 MiB for populated trials. The sampled Core peaks were about 170–173 MiB in both modes, including the test workload.

**Recommendation: retain cold workers.** The faster median on 1–4 tiles does not justify roughly 29 MiB of standby PSS, unused bindings, and an inconclusive tail-latency and CPU benefit. At 8 tiles the reserve produced no hits in the final run and did not improve admission or the success count. The previous 4.819 s cold binding did not recur in this matrix. The prototype still begins cold when no reserve exists, and the historical Hello timeout and DocumentsUI issue remain open. Keep the branch separate and local package execution disabled. No signed experimental APK was produced.
