# Developer Runtime hardening

## Android 15 instrumentation triage

The [previous CI log](https://github.com/PseudoEF/HyprDroid/actions/runs/45372031626) proves the failure symptoms below for one emulator run. A missing node or timeout establishes neither the product cause nor regression against the base commit. Re-run the same assertions on a fresh Pixel 6 emulator and compare with the base before changing product code.

| Test | Recorded failure | Diagnosis and follow-up |
| --- | --- | --- |
| CleanShellTest.sidebarHasNoHandleOpensInsideSafeBandAndPreservesHiddenModuleItems | Missing Open Sidebar content description | Inspect page and panel activation state before the tap. Compare on base. |
| CleanShellTest.cleanClassicPreviewRollbackDockAndReverseGesturesPreservePins | Compose timeout after 5 s | Reproduce with clean shell preferences and capture layout and selection on timeout. |
| EditorSidebarTest.dragPersistsAndSidebarEditsClosesAndRestores | Missing sidebar-open tag | Compare sidebar navigation fixture and current panel visibility. |
| GestureWorkspaceTest.blankGesturesOrderMoveAppearanceAndRestart | Compose timeout after 5 s | Check emulator navigation mode and which gesture assertion stalls. |
| QuickSettingsUiTest.homeShadeHasTwoColumnsSeparateControlsAndClosesWithoutStealingNavigation | Compose timeout after 10 s | Isolate shade setup and check launcher selection and gesture mode. |
| ShellInteractionTest.homeDrawerEditingContextMenuAndLauncherExit | HOME chooser not visible | Previous harness selected HyprDroid as HOME before this test. Compare fresh and preselected HOME; causality is unverified. |
| SidebarInteractionTest.longGridCapsHeightAndScrollsWithoutLosingItems | Compose timeout after 15 s | Compare clean and seeded kiosk workspace and capture sidebar semantics. |
| SidebarInteractionTest.compactGridDragMenuAndRecreationKeepAndroidShortcutIdentity | Compose timeout after 15 s | Inspect shortcut fixture and panel before and after Activity recreation. |
| UniversalPanelsTest.rootBackAndPanelCloseKeepMarkerDimensionsInCleanAndClassic | Missing sidebar-open tag | Compare initial layout mode and panel activation on base. |
| WidgetProcessRestoreTest.widgetIdPlacementAndRemoteViewsSurviveARealProcessRestart | Explicit widget phase required | Confirmed harness error. Use the shell UI runner to seed, force-stop, reinstall and verify with the required arguments. |

## Worker admission

The process-wide Lua worker budget is two active calls and four pending calls, with a five-second pending deadline. The coroutine semaphore serves pending calls in arrival order. Excess calls fail before binding Binder, and cancellation and timeouts release capacity. Four pending slots is an initial budget of two additional waves of work, not a throughput result. The current boundary is after the caller has prepared its inputs.

The deterministic JVM test holds both active slots, fills all pending slots, rejects 1,000 additional submissions and checks FIFO and cancellation recovery. A separate test checks deadline recovery. This measures admission logic, not Binder throughput or device memory.

The Lua arena remains 2 MiB. PSS is a periodic observation of process memory, with transient peaks and ART or JNI allocation failures still possible. Existing state caps are 64 records of at most 512 UTF-8 bytes per record. Before enabling local modules, measure timer fan-out and IPC pressure end to end, demonstrate worker OOM recovery, and serialize revocation with durable state commit and frame publication. Local package admission remains closed.

## Signed upgrade fixtures

The Developer upgrade installs the previously distributed version 14 APK and checks its existing signing certificate before replacing it. The ordinary upgrade uses signed build 10191 as its baseline and seeds the official Clock Tile state before replacement. After the update it verifies the stored setting by executing Clock through the isolated worker. The separate test-only to official package migration remains on its original 10185 fixture.

## Reproduction on a matched emulator

The full shell suite on a fresh Pixel 6 emulator with Android 15 passed on both the [official baseline](https://github.com/PseudoEF/HyprDroid/actions/runs/45382149127) and the [Developer branch](https://github.com/PseudoEF/HyprDroid/actions/runs/45381970971). This includes the nine UI assertions that failed in the original isolation workflow. Both runs also passed the separate widget seed and verify phases. The original job used a different emulator profile and selected HyprDroid as HOME before the full UI suite. Those conditions differed together, so no individual UI failure has an isolated root-cause reproduction. There were no assertion relaxations.

The full suite runner now carries the failure status forward while still running widget restoration after a clean reset of the disposable emulator's app data. This keeps the two failure signals independent.

## Prepare the existing signed package in Termux

From the my-tile project, use the same public key and detached metadata you already created with SDK 0.1.1. Adapt the public-key and metadata paths to your own files.

    public_key="$HOME/.hyp-my-tile-keys/developer.pub.pem"
    archive=dist/my-tile-1.0.0.hyp
    signature=dist/my-tile.signature.json
    hyp verify "$archive" --signature "$signature" --public "$public_key"
    termux-setup-storage
    cp "$public_key" "$archive" "$signature" "$HOME/storage/downloads/"

The existing private key stays in Termux. The three exported files are sufficient for the future import flow. The current isolation APK cannot import them.

A [second Developer-branch full run](https://github.com/PseudoEF/HyprDroid/actions/runs/45382526410) retained a failure in the separate AndroidWidgetsTest RemoteViews scenario. It was not among the original ten. Both widget restoration phases still ran and passed. One [isolation run](https://github.com/PseudoEF/HyprDroid/actions/runs/45383091946) separately timed out in the trusted LuaJ service. Its runtime budget was kept unchanged. These runs are retained as failures.

## Measured worker bursts

[Android 15 experimental runtime CI](https://github.com/PseudoEF/HyprDroid/actions/runs/45383815914) completed all 60 requests in 8,195 ms. Each of 10 rounds submitted six simultaneous Hello Tile operations. Recorded end-to-end p95 was 876 ms and the maximum was 905 ms, with all 60 independent state results checked. The same run passed the 10 Lua, Binder, and lifecycle tests and the v2 process-persistence cases. This short workload used the official Hello fixture. It did not measure process PSS peaks, Android worker OOM recovery, malicious Parcel traffic, or sustained aggregate timer load.

## Final validation scope

The ten-minute Android 15 fixture exercises two visible Clock Tile instances, each with an ongoing Core timer. It validates alternating user-selected state, checks that the second tile's state remains independent, switches workspaces and drives Activity through stopped and resumed states. Removal at the end invalidates the signed package during ongoing timer activity. Reinstall checks the recorded state. Event latency includes test-driver and UI dispatch time. PSS samples include the Core process and its instrumentation overhead.

The native pressure fixture touches bounded chunks outside the Lua allocator in the actual isolated worker. The test distinguishes the worker PSS guard, observed native allocation failure and watchdog termination through logs. This is a controlled process-pressure test. The 2 MiB Lua arena is a separate hard limit. A run in which the PSS guard stops the worker before allocation failure does not establish Linux LMK or full-process OOM recovery. The native fixtures are compiled only into the experimental instrumentation build. Release packaging scans native binaries for fixture markers.

The Binder suite uses real dispatches and file descriptors. It checks malformed descriptor and trailing Parcel data followed by a valid owner dispatch, plus the existing session, replay, oversized SharedMemory and Binder-death assertions. This suite does not establish exhaustive Parcel compatibility or protection against system-level resource pressure.

The managed OOM fixture asks ART for a single allocation that exceeds its managed heap capacity, in the actual isolated worker. The existing error handler must record the managed failure, terminate the worker and leave both persisted Clock Tile states intact. This does not simulate competing processes under global memory pressure.

Two fixes came out of the audit. The native watchdog fixture marker used to compile into the ordinary native library, even though admission could never select it from a signed package. It is now excluded at build time. Also, a service-disconnection callback could complete an operation before the Binder death recipient recorded an unexpected death. Both callback paths now account for the death once. The experimental circuit test requires four observed deaths to open the circuit, a subsequent refusal and recovery after the configured window.

The first on-device raw Parcel run found that the generated AIDL stub accepted trailing bytes on a primitive dispatch and let it complete the session. Core now enforces the exact payload length for dispatch and dispose before invoking their handlers. Keep the original failing run as evidence. The ten-minute workload runs as an independent emulator phase, even when a short test fails.

Early failures remain visible in CI. The first sustained runs stopped after timed-out user changes during concurrent timer activity and did not meet the duration target. The workload now measures retries when a click races with a newer frame, and still fails unless the desired state is confirmed. The first upgrade against 10199 found a test-linkage incompatibility in the previous workspace store default arguments. An overload retains that baseline signature while supporting atomic open-state saves.

A sustained run exposed a scheduling problem: repeated timer operations lasting about as long as their requested interval left almost no opportunity for user events, even after retries. Core now schedules the next tick for that timer no earlier than one full configured interval after the current operation completes. Idle time for input is preserved without replaying ticks or increasing the worker budget.


## Recorded results on Android 15

| Check | Result | Evidence |
| --- | --- | --- |
| Two Clock Tiles under timed workload | 602.030 s, 127 confirmed state changes and 4 retries. Event p95 1,221 ms, maximum 6,320 ms. Workspace switching, stopped/resumed Activity, independent state, removal during timer activity and reinstall passed. | [Sustained phase and logs](https://github.com/PseudoEF/HyprDroid/actions/runs/45434190931) |
| Worker activity in ten minutes | 230 bindings, 230 closures, zero logged admission declines, response timeouts or Binder deaths before disposal. The 2-active/4-pending cap remains separately asserted by deterministic unit tests. | [Worker counters](https://github.com/PseudoEF/HyprDroid/actions/runs/45434190931/artifacts/14941167741) |
| Core memory during the sustained phase | Startup PSS 158,238 KiB, later samples 62,779–76,316 KiB, last periodic sample 70,138 KiB. The samples include instrumentation. They do not prove the absence of leaks outside the measured window. | [PSS samples](https://github.com/PseudoEF/HyprDroid/actions/runs/45434190931) |
| Lua arena, native pressure, managed OOM | The 2 MiB arena test passed. The actual isolated worker touched 72 MiB outside the arena before the PSS monitor stopped it at 103,764 KiB. ART separately threw the expected managed OOM. Binder death was observed, and persisted Clock state and healthy workers recovered. Another run saw native pressure and death without a conclusive PSS or allocation-failure log. | [Completed short phase](https://github.com/PseudoEF/HyprDroid/actions/runs/45430004585) · [Inconclusive pressure reason](https://github.com/PseudoEF/HyprDroid/actions/runs/45430154766) |
| Short concurrent workload and circuit | 60/60 Hello calls in ten batches of six, total 10,031 ms, p95 1,110 ms, maximum 1,310 ms. Four abrupt worker deaths triggered rejection, followed by recovery after the breaker window. | [Burst and circuit](https://github.com/PseudoEF/HyprDroid/actions/runs/45430004585) |
| Binder and Parcel | Wrong descriptor, trailing and truncated primitive messages, foreign IDs, replay and oversized SharedMemory tested through actual Android Binder. The valid owner message still produced its expected response. | [Signed ordinary instrumentation](https://github.com/PseudoEF/HyprDroid/actions/runs/45434202600) |
| Ordinary Android UI | Full Android 15 shell suite and separate widget restoration phases passed. | [Shell CI](https://github.com/PseudoEF/HyprDroid/actions/runs/45429438321) |
| Ordinary signed update | 10199 → 10209 passed with the existing certificate, HOME and data retained. Official package tests and binary fixture exclusion passed. | [Signed Core CI](https://github.com/PseudoEF/HyprDroid/actions/runs/45434202600) |
| Developer | 80 → 110 passed with the Developer certificate and separate UID, storage and HOME status. Developer workspace and local import regressions also passed on the branch. | [Signed Developer CI](https://github.com/PseudoEF/HyprDroid/actions/runs/45434291459) · [Developer instrumentation](https://github.com/PseudoEF/HyprDroid/actions/runs/45434187510) |

The combined latest experiment is red. Its sustained phase passed, but its short phase timed out in `ExternalTileLifecycleTest.countsSurviveBackgroundWorkspaceChangeAndActivityRecreation`. The earlier successful short phase is reported separately and does not replace this failure. The early [Parcel bypass](https://github.com/PseudoEF/HyprDroid/actions/runs/45428125435), [timer starvation](https://github.com/PseudoEF/HyprDroid/actions/runs/45432435485), and [upgrade test linkage failure](https://github.com/PseudoEF/HyprDroid/actions/runs/45430162204) remain in the CI record.

## Readiness decision

Keep local Lua execution disabled. The run establishes controlled recovery from Lua arena exhaustion, observed native pressure, managed OOM and the tested primitive Parcel violations. Whole-process memory still has no hard quota. Recovery under Android LMK and competing device-wide pressure was not induced, malformed shared-memory-bearing creation and callback Parcels were not exhaustively tested, and the lifecycle timeout needs diagnosis. The sustained test exercised a user event stream against concurrent timer work. It did not establish arbitrary simultaneous multi-user input or protection against Binder buffer exhaustion.

## Pixel 9 smoke check

Install ordinary 10209 over 10199 and Developer 110 over 80. Verify the ordinary app remains available as HOME, compare existing workspaces and open a first tile and terminal in Classic and Clean. Keep two official clocks visible for at least ten minutes, change one clock format, switch workspaces and apps, and verify the other clock remains independent. After force-stop confirm the stored format. In Developer, inspect an existing signed local package and verify execution remains blocked. Revoke its key and confirm re-adding the key does not restore the old grant.

## Runtime release gate

The owner reported 20/20 checks on a physical Pixel 9 with Android 17. Both APKs were built from `bc85622`.

- HyprDroid 10209 passed 12/12. The signed update preserved data and HOME. Classic and Clean could create their first tile, and the terminal worked. Hello Tile accepted repeated Increment events and retained the count after backgrounding. Two Clock Tiles ran continuously for ten minutes with independent formats. Workspace changes, returning from the background and force-stop preserved state.
- HyprDroid Developer 110 passed 8/8. The signed update preserved data. Developer Edition stayed out of the HOME list. Window tiles and the terminal worked. Imported my-tile remained visible through `hypr dev info` and execution was denied. Revocation persisted and reimporting the key did not restore artifact trust.

[CI 45434190931](https://github.com/PseudoEF/HyprDroid/actions/runs/45434190931) timed out waiting for the first Hello Tile Increment to become visible, before the first background transition. Its report lacks a correlated click, worker and state trace, so the cause of that occurrence is unproven. The release-gate runner repeats the first Increment on 20 fresh tile slots and checks the stored value and the displayed value after stop/resume and Activity recreation on each iteration. It retains the first failure and collects the experimental logs even when the test fails.

Fixture logs separate worker queue wait, dispatch handoff, worker connection, native execution, callback and teardown. For Hello Tile they also record the click, DataStore commit duration and publication. No script, state value or package data appears in the trace. The gate report captures Android exit information and lmkd logs. A force-stop, Lua arena failure, ART OOM or PSS guard termination cannot establish a system low-memory eviction. This remains a separate release condition.

### Release-gate measurements

| Android 15 run | Hello first Increment and Activity lifecycle | Sustained Clock workload |
| --- | --- | --- |
| [45434190931](https://github.com/PseudoEF/HyprDroid/actions/runs/45434190931), `bc85622` | Timed out before the first background transition | 602.03 s, 127 changes, four retries, p95 1221 ms, max 6320 ms |
| [45450550719](https://github.com/PseudoEF/HyprDroid/actions/runs/45450550719), `e8e586a` | 20/20; click-to-UI p95 1691 ms, max 1864 ms | 602.17 s, 130 changes, three retries, p95 602 ms, max 7272 ms; 225 bindings and 225 closures, zero rejects, timeouts or unexpected deaths |
| [45451038413](https://github.com/PseudoEF/HyprDroid/actions/runs/45451038413), `4e147ff` | 20/20; click-to-UI p95 2122 ms, max 2386 ms | Failed during the initial render of the second Clock, before the timed workload |

Timed Clock operations in the successful repeat had queue p95/max 1/1 ms, worker connection 825/3291 ms, dispatch-to-reply 178/554 ms (including native Lua 44/173 ms), and reply-to-close 48/520 ms. The latest Hello series had queue 1/1 ms, connection 1722/3445 ms, native Lua 161/299 ms and durable commit 20/173 ms. Publication is recorded separately in the logs. For the latest Clock phase, nine successful warm-up operations published in at most 6 ms. That sample did not include any user change under sustained load.

In the failed Clock run, the second resume took 4819 ms between requesting the isolated service and receiving the connection. The existing 5000 ms combined deadline then expired 272 ms after dispatch and before the Lua completion trace. Queue admission took 0 ms on that operation. This establishes cold worker connection as the dominant contributor to this specific failure. The original Hello failure has no comparable trace, so a common cause remains a hypothesis. The previous sustained maximum was measured across test-driver retries. Its largest inter-publication gap was 10.31 s, including a planned 4 s pause and a final worker operation that bound and closed in 224 ms. Logs from that run do not identify exactly why the earlier clicks in that interval did not result in publication. The newer series has no demonstrated tail-latency improvement and no deadline or quota change.

The short runtime suite passed on both repeat runs. Binder tests added three mismatches between the SharedMemory header and backing size (`-1`, `0`, and `2` for a one-byte body). Real isolated Binder workers exited on each invalid request without invoking the callback. Existing oversized memory, foreign identifier, replay, malformed scalar message and post-death checks also passed. Closed or foreign descriptors on the wire, malformed callback frames and stale callbacks delivered across Binder after revocation still need dedicated coverage. Native pressure at 72 MiB touched prompted the periodic PSS guard at 103647 KiB on the latest persistence test. A separate managed OOM was logged and both persistent-state phases passed. Android exit information recorded normal isolated-worker disposal; it did not confirm LMK eviction. The disposable test runner has no controlled and independently attributable system-pressure fixture for that scenario.

The signed [ordinary update and protocol regression](https://github.com/PseudoEF/HyprDroid/actions/runs/45451038859) and [Developer update and parallel install](https://github.com/PseudoEF/HyprDroid/actions/runs/45451037820) passed. The [unsigned Developer push run](https://github.com/PseudoEF/HyprDroid/actions/runs/45450984188) retained a 15 s timeout after selecting the signature file in DocumentsUI. Waiting for roots to return removes the premature missing-root exception from the earlier [push run](https://github.com/PseudoEF/HyprDroid/actions/runs/45438583394); the picker scenario is still intermittent. Local unit tests passed for Debug (233) and Developer (239). Debug and Developer app and instrumentation APKs assembled locally. Experimental native assembly ran in CI because the local Termux environment lacks NDK 28.2 and its AIDL tool is unusable.

**Release decision: NO-GO for local execution.** The original Hello incident is still unattributed, a subsequent Clock worker startup missed the aggregate deadline, and descriptor/callback and LMK cases remain open. Keep the exact official artifact policy and local execution block. For the next gate, capture process creation and service publication alongside the existing phase trace. Compare simultaneous and staggered worker cold starts under identical emulator settings while retaining the existing limits. Separately exercise app-level recovery from a documented Android LMK eviction, if the environment can produce one.

## Final stabilization with cold workers

The reserve experiment remains on [its own branch](https://github.com/PseudoEF/HyprDroid/blob/fed0f12/docs/EXTERNAL_LUA_PREWARM.md). A ready, empty process occupied about 29 MiB PSS. The experiment did not raise the eight-tile success count. The production architecture stays with cold, isolated, single-use bindings. The owner verified 11/11 checks on the previously signed Pixel 9 Android 17 APKs: five ordinary checks including the first Hello Increment, ten minutes with two clocks, HOME and data retention, and six Developer checks covering upgrade, HOME exclusion, import, info, the execution block and trust revocation.

### What failed in the previous eight-tile test

The cold arm of [CI 45466845505](https://github.com/PseudoEF/HyprDroid/actions/runs/45466845505/artifacts/14957861247) activated eight tiles together twice. Each activation wave logged two refusals. Only six initial requests could enter the previous two-active/four-pending scheduler, and a refused tile with no frame had no timer or automatic way to obtain a frame. Three of six interactions produced a visible change; the saved logs do not identify every individual missed click. The prior UI also silently discarded an event if a timer became busy or published a new revision between the rendered frame and event dispatch.

At the five-second observation in that cold-arm trial, Lua had not yet received its first request. Activity creation to tile activation took 6,449 ms. The first activation-to-binding interval was 112 ms. From binding to connection took 397 ms, connection to dispatch 1 ms, and measured native Lua ran for 16 ms. The first published frame arrived 7,072 ms after Activity creation. The closest resume telemetry recorded 15 ms in the commit stage and 0 ms in publication at millisecond resolution. The sample places the five-second failure before worker admission. It is separate from the older observed 4,819 ms Service-connection outlier.

Across all 63 cold operations in that eight-tile trial, admission p95/max was 1,003/1,073 ms, bind 481/580 ms, dispatch-to-reply 103/118 ms, and Lua 35/48 ms. The existing combined worker deadline remains five seconds. Splitting the budget into independently expiring connection and execution timers would not address the pre-admission delay. A split within the same hard cap would also still expire on the earlier connection outlier before a long enough execution window. Changing the total budget would require new evidence.

### Scheduler and validation changes

The queue remains two active and four pending. User input overtakes queued background work for at most two consecutive grants before the oldest background request receives a turn. Timer work cannot occupy the last pending slot and waits at most one second for admission. Existing per-tile timer logic coalesces missed intervals and waits after completion. Capacity-refused initial renders get up to three delayed retries while the generation and composition remain active. Rejected input reports a reason on the tile and still requires a fresh user action.

A real isolated Binder callback with a malformed frame must fail decoding and close its SharedMemory. An authorization test invalidates the operation before the worker sends its reply and checks the callback was received and discarded. Both tests follow with a healthy operation. The descriptor tests exercise rejection of a closed SharedMemory by AIDL marshalling and a regular file by the public framework constructor. A valid Binder session still succeeds on that connection. The regular-file check exercises the typed framework boundary, so it does not establish receiver behavior for an arbitrary descriptor injected into a Binder transaction. The test explicitly closes the detached descriptor after Android 15 rejects it.

The original Hello incident has insufficient historical traces for an exact attribution. The DocumentsUI regression uses the existing process-restart import test, which tolerates the window hand-off without extending its deadline and still checks the resulting confirmation. No claim of deterministic DocumentsUI behavior or genuine Android LMK recovery follows from these checks.

### Verification and decision

- Local unit tests passed for Debug (234) and Developer (240). Both app APKs and the Debug instrumentation APK assembled locally. The [Developer isolation and DocumentsUI run](https://github.com/PseudoEF/HyprDroid/actions/runs/45572317153) passed, including the process-restart import flow. Native and Binder tests also passed on Android 15. The earlier [lifecycle run](https://github.com/PseudoEF/HyprDroid/actions/runs/45570793978/artifacts/15003150740) passed 20/20 first-Increment scenarios (p95 1085 ms, maximum 1355 ms).
- [The first pressure run](https://github.com/PseudoEF/HyprDroid/actions/runs/45569299645), [descriptor follow-up](https://github.com/PseudoEF/HyprDroid/actions/runs/45569473340) and [tap-guard run](https://github.com/PseudoEF/HyprDroid/actions/runs/45570793978) each restored eight frames and timed out on the second input. The Android Compose implementation of `performClick()` injects a touch, and the control could be present in semantics while clipped. The current test scrolls the switch into view before injecting a touch. The tile now keeps its refusal message outside the scroll area.
- In the [latest pressure run](https://github.com/PseudoEF/HyprDroid/actions/runs/45572317663/artifacts/15002977000), all eight frames became available in 10058 ms. The diagnostic confirmed that the second switch was clipped before scrolling, and the first three touches then committed across a workspace and Activity stop/resume cycle. The fourth touch still reached neither a visible state change nor an explicit refusal within seven seconds, despite the test's visibility check. There is no corresponding interaction admission or commit trace, so its exact cause remains open. The test continues to fail on that condition.
- In that latest eight-tile run, measured queue p95/max was 3349/4199 ms, service connection 1688/2238 ms, dispatch-to-reply 663/700 ms, Lua 255/310 ms and reply-to-release 237/331 ms. The three confirmed input changes took 2930, 3517 and 804 ms. Core PSS at first availability was 95304 KiB, and Core used 9075 ms of CPU time over the measured 40177 ms. These figures include instrumentation and are not a matched long-run memory or latency comparison. No hard whole-process memory quota follows from PSS.

**NO-GO for the next local-execution stage.** An input can still go unacknowledged at eight tiles. The typed AIDL tests passed, including a revoked frame observed on the Core Binder callback, but they do not establish the receiver-side outcome of an arbitrary foreign descriptor. Genuine Android LMK is still unconfirmed. Keep local execution disabled and withhold signed stabilization APKs until the gate passes.

## Targeted input reliability

The exact failure in CI 45572317663 cannot be attributed retrospectively because that run did not log hit targets or handler entry. In the [instrumented reproduction](https://github.com/PseudoEF/HyprDroid/actions/runs/45580624506), the fourth action reached the expected Clock switch. In one trial it passed the frame guard, entered the worker queue, committed and appeared after 915 ms. In another it arrived while a timer owned that tile's execution slot. The handler set the busy refusal before queue admission. The test nevertheless timed out, as it did for the other traced busy refusals. All 14 injected actions in those three trials reached the intended handler. The first trial also retained a separate workspace-restoration timeout after its second action.

The old refusal matcher assumed its text would be a descendant in the merged semantics tree. The clickable tile can merge that text into its own semantics. The current driver selects the tile's status element in the unmerged tree and requires it to be displayed. Test touches are injected through Android into the measured visible bounds after scrolling. Fixture-only diagnostics correlate injection, Activity touch, Compose hit, callback, frame gate, admission, Binder send/reply, durable state and frame publication or refusal. A user event with an uncertain Runtime result displays an explicit status and is never replayed by Core. Initial frame recovery retains a bounded schedule of admission retries to handle the observed separate restore failure.

The typed IPC boundary uses `in SharedMemory`. The closed handle is rejected when AIDL marshals it. A regular-file descriptor cannot be turned into a SharedMemory argument using the public Android framework constructor. The fixture closes the detached descriptor upon rejection and then runs a valid transaction on the same worker. These checks cover the public typed path and subsequent operation, while receiver-side behavior for arbitrary raw transactions has not been established. LMK remains unconfirmed.

### Targeted results

[Android 15 CI 45583425172](https://github.com/PseudoEF/HyprDroid/actions/runs/45583425172/artifacts/15008816325) passed the native/Binder suite and three independent eight-tile trials with reset app data between them.

| Trial | Confirmed success | Visible busy refusal | Missing or did not reach the handler | Successful input range | Refusal range |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 3 | 3 | 0 | 1142–3681 ms | 104–483 ms |
| 2 | 5 | 1 | 0 | 452–680 ms | 175 ms |
| 3 | 5 | 1 | 0 | 283–588 ms | 135 ms |

All 18 input IDs had an Android down event and a callback from the expected tile. Thirteen ran and published a confirmed change. Five lost the race with a timer and displayed the busy status before worker admission. There were zero unexplained losses. The fourth event in trial 3 is a concrete example of the now-visible refusal. There is no automatic retry of that event. Local Debug and Developer unit tests passed (234 and 240), as did the local Debug, Developer and Debug instrumentation builds. The final Android CI also built the fixture application and instrumentation APK.

**NO-GO for enabling local execution.** The targeted input gate passes. The latest [Developer isolation run](https://github.com/PseudoEF/HyprDroid/actions/runs/45583419693) failed separately with `DocumentsUI roots unavailable` while choosing the public key, and that failure is retained. The AIDL check remains limited to what the typed API can marshal. No cold worker architecture, signature policy, or local execution flag was changed.
