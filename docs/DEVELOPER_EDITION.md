# HyprDroid Developer Edition

The isolation build uses application ID `dev.hyprdroid.launcher.developer` and launches from the app drawer. It has its own icon, label and persistent workspace banner. Ordinary HyprDroid uses application ID `dev.hyprdroid.launcher` and registers HOME as before. The developer Activity and its resources live in the developer source set. Android assigns the apps different UIDs and app-private directories. Both use their own workspace, package registry and tile state directories. There is no key import or transfer between them.

## Runtime release gate

The developer variant currently builds with the external Lua runtime disabled. Core 10189 passed the owner's Pixel 9 Android 17 checks for the three exact official artifacts. Those results do not establish that an arbitrary locally signed Lua workload is safe. The existing [external Lua audit](EXTERNAL_LUA_SECURITY_AUDIT.md) documents the worker's isolated UID, a fixed 2 MiB Lua arena, bounded shared memory, IPC validation, an execution watchdog, two concurrent worker operations and a circuit breaker. The sampled 96 MiB PSS threshold cannot prevent transient JNI, Binder or ART allocations or provide a whole-process memory limit.

Before enabling local execution, verify Core availability during worker OOM, sustained multi-tile scheduling, Binder buffer pressure and malformed IPC on Android. Measure aggregate timer, queued-work and persisted-state usage, and enforce budgets across tile instances. Exercise trust revocation during in-flight state commit and UI publication and check the absence of side effects. The existing removal generation is tied to the official package model and is insufficient on its own for publisher-key revocation.

Core currently caps persisted tile state at 64 records with at most 512 UTF-8 bytes per record. The worker semaphore bounds simultaneous work, while callers can still reach its wait queue after preparing package and state snapshots. For local modules, verify the full path, including admission before preparation and timeout behavior across multiple tiles. The existing state transaction checks the package generation in its DataStore transform. A local key-revocation design needs a defined ordering with the completed disk write and frame publication, using the same authority to serialize both sides of that race.

A follow-up should reuse the package verifier and transaction path for the pair of SAF inputs, archive and detached SDK signature metadata. Trust a key only after displaying and confirming its fingerprint. Separately approve exact package ID, publisher, version, archive hash and capabilities. Recheck the entrypoint before execution and seal the verified bytes using the existing read-only SharedMemory path. Couple revocation with state commit and use one generation to invalidate running work, pending events and timers. Keep official package namespace and publisher continuity checks. Same-version replacement should require a fresh confirmation of the old and new hashes and preserve state only when compatible.

## Build and Android verification

Run `./gradlew :app:assembleDeveloper` to produce the isolation build. It is unsigned by default. The Developer signing configuration accepts a dedicated PKCS12 file via `HYPRDROID_DEVELOPER_KEYSTORE_FILE` and its password via `HYPRDROID_DEVELOPER_KEYSTORE_PASSWORD`. Use the alias `hyprdroid-developer`. Keep the private file outside source control, retain it securely between updates, and keep it distinct from the normal APK signing key and from plugin Ed25519 keys.

CI checks both packaged manifests and DEX code, then installs both apps on Android 15 under different UIDs. Its disposable emulator certificate is for that job only. The manual workflow uses the separate persistent key stored in the branch-scoped developer-signing environment. It publishes the signed isolation APK only after an Android 15 update check. The Developer signing certificate SHA-256 is 5c30c0c6b00e6170775331db79585f667870092fea380110ac1434ece20a7bff.

### Pixel 9

После получения APK, подписанного постоянным сертификатом Developer Edition, установите его рядом с обычным HyprDroid. Проверьте, что Android показывает две иконки, у Developer Edition есть подпись в рабочем пространстве, а в системном выборе домашнего приложения доступен обычный HyprDroid. Измените пространство в Developer Edition и убедитесь, что настройки обычного приложения не изменились.

Для создания и подписи пакета своим ключом используйте [инструкцию SDK](https://github.com/PseudoEF/HyprDroid-SDK/blob/v0.1.1/docs/GETTING_STARTED.md). Импорт ключей и пакетов, обновление и отзыв допуска ожидают реализации после закрытия проверок Runtime.
