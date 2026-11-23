# External Lua Runtime security audit

Baseline: `0c04b5d`. The owner verified APK 10173 on Pixel 9 with Android 17. This audit keeps execution restricted to the explicitly enabled, publisher-pinned Hello Tile 1.0.0. DroidFetch continues to use the existing LuaJ runtime.

## Findings and fixes

| Boundary | Finding and fix |
| --- | --- |
| Worker request routing | A foreign or repeated dispatch previously sent an error to the callback of the legitimate session. The worker now admits one session and one event, ignores mismatched IDs and replays, and closes all received shared memory handles. Invalid initial state or descriptor sizes terminate the one-shot worker. |
| Core response routing | The private callback previously checked the session ID before decoding but did not atomically claim the single response. The receiver now accepts at most one response and checks its ID, request number, calling UID, byte bounds and validated UI/state. |
| Removal and revocation | Previously cancellation depended on recomposition, with timing windows while waiting for a worker. An operation now races the trust generation directly. Core checks the generation before binding, before sending work, before accepting a result and during state commit. A removed package's tile becomes inert while its persisted state remains associated with that tile. Reinstalling the pinned, verified version can restore it. Final tile deletion removes the record. |
| Core availability | Binder work is dispatched off the UI thread. The two-worker limit now has a bounded wait. Repeated unexpected worker deaths suppress launches for the sliding failure window. Core still fails closed after unconfirmed death of a connected worker. Cancelling before connection cannot submit Lua and leaves later verified bindings available. |
| Package integrity | The existing installer and launch-time checks verify the Ed25519 publisher and catalogue, signed package ID and version, archive SHA-256 and signature, installed entrypoint bytes, API compatibility and `ui.tile`. Core passes a sealed snapshot of the checked bytes. The worker receives no private path and cannot reopen the source from a package installation directory. |

## Resource limits and residual risks

- The Lua allocator enforces a fixed 2 MiB arena, including parsing. JNI and the Android process also use native and managed allocations. Source and state are bounded before copying, frame decoding is bounded, and descriptor sizes are checked before mapping. The 96 MiB PSS sampler is approximate and may miss transient peaks. ART OOM and whole-process pressure do not have a strict per-worker cap in this architecture.
- Lua instruction, thread CPU and independent kernel wall-time limits are unchanged. Core observes Binder death and releases one of two slots after cleanup. Four unexpected deaths within ten seconds suppress new bindings until the window permits another. The normal one-shot worker lifecycle does not count as a failure.
- Isolation depends on the Android isolated UID, non-exported service and Binder capability model. The callback Binder is given to one worker and requires a matching random session ID and request number. The Lua parser and JNI bridge are native code within the worker trust boundary.
- Test coverage uses typed Binder calls and oversized shared-memory descriptors. Kernel transaction-buffer stress, arbitrary malformed Parcel fuzzing, measured ART OOM and long-running device memory profiling remain open. The absence of those results limits conclusions about enabling new publishers or versions.
- While offline, removal or revocation in a remote catalogue becomes visible after a successful trusted refresh. The pinned exact archive blocks substitutions and version changes even while using cached data.
- Package publication policy stays unchanged. First-party packages require owner approval, CI, verification and signing. Community packages require independent review, CI and publisher signing. This APK still admits only the pinned first-party Hello Tile release.

## Pixel 9 with Android 17

1. Install the signed Core APK over 10173. Keep the same data and select HyprDroid as Home if needed.
2. Run `hypr repo update`, `hypr install hello-tile` and `hypr tile add hello-tile` twice. Give both tiles distinct values. Rapidly alternate Increment and Reset across both tiles.
3. Switch Android apps and workspaces, resize the tiles, show and hide the terminal keyboard and relaunch HyprDroid. Verify each counter independently.
4. Run `hypr remove hello-tile` with both tiles present, including immediately after an Increment. Confirm a controlled error in the tiles and rejection of a new `hypr tile add hello-tile`. Reinstall the same signed package and confirm state recovery for the retained tiles. Remove one tile and add a fresh one to check initial state and isolation.
5. Check HOME, inline prompt, history, `demo install`, DroidFetch and the repository commands. If using ADB, collect operational logs with `adb logcat -s HyprLuaTile`.

[General runtime test instructions](SAFE_EXTERNAL_LUA_TESTING.md)
