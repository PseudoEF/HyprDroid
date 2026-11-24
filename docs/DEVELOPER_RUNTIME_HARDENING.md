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
