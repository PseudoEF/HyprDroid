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
