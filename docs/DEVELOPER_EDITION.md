# HyprDroid Developer Edition

The isolation build uses application ID `dev.hyprdroid.launcher.developer` and launches from the app drawer. It has its own icon, label and persistent workspace banner. Ordinary HyprDroid uses application ID `dev.hyprdroid.launcher` and registers HOME as before. The developer Activity and its resources live in the developer source set. Android assigns the apps different UIDs and app-private directories. Both use their own workspace, package registry and tile state directories. Developer keys and local package records are only available in the Developer source set and its private storage.

## Runtime release gate

The developer variant currently builds with the external Lua runtime disabled. Core 10189 passed the owner's Pixel 9 Android 17 checks for the three exact official artifacts. Those results do not establish that an arbitrary locally signed Lua workload is safe. The existing [external Lua audit](EXTERNAL_LUA_SECURITY_AUDIT.md) documents the worker's isolated UID, a fixed 2 MiB Lua arena, bounded shared memory, IPC validation, an execution watchdog, two concurrent worker operations and a circuit breaker. The sampled 96 MiB PSS threshold cannot prevent transient JNI, Binder or ART allocations or provide a whole-process memory limit.

Before enabling local execution, verify Core availability during worker OOM, sustained multi-tile scheduling, Binder buffer pressure and malformed IPC on Android. Measure aggregate timer, queued-work and persisted-state usage, and enforce budgets across tile instances. Exercise trust revocation during in-flight state commit and UI publication and check the absence of side effects. The existing removal generation is tied to the official package model and is insufficient on its own for publisher-key revocation.

Core caps persisted tile state at 64 records with at most 512 UTF-8 bytes per record. The worker currently admits two active and four waiting operations. Some inputs are prepared before admission. The official tile admission fence serializes the final Binder dispatch and result publication against invalidation. An already admitted DataStore transaction holds the fence through acknowledgement, including if the Compose caller is cancelled.

## Local package inspection

Run the Developer terminal commands `hypr dev trust add`, `hypr dev trust list`, and `hypr dev trust remove <fingerprint>`. Add uses Android's system picker. Compare the displayed SHA-256 fingerprint of the Ed25519 public key with your SDK verification result before confirming. Private keys and other public-key algorithms are rejected.

`hypr dev import` opens the system picker twice for the archive and detached signature metadata. Review package ID, version, permissions, publisher fingerprint and archive SHA-256 in the second confirmation. Core reads bounded snapshots and never requests the detached URL. It checks the signed archive with the existing archive and manifest verifier. The approved snapshot is verified again against the same trust grant, staged privately, and published by an atomic directory move. Local packages do not enter the official installation registry. Same-ID replacement requires an explicit removal before importing again.

`hypr dev list` and `hypr dev info <id>` show local records. A valid imported package shows `Imported / Verified / Execution disabled`. The command `hypr tile add <id>` rejects it. `hypr dev remove <id>` removes the local record. Revocation removes the public-key grant. Re-adding the same public key creates a new grant, so existing records stay revoked until explicitly removed and reimported.

### Revocation boundary

The completion point for official execution revocation is invalidation of the generation under the publication fence. Invalidation stops acceptance of new Core calls and Binder dispatches for the old generation, cancels tracked queued and running coroutines, and rejects further durable state writes and UI or timer publication. An admitted durable commit finishes before invalidation. Already sent Binder work is cancelled and disposed by the existing worker cleanup path. Worker process exit is asynchronous and cannot publish after invalidation.

Local key revocation is a separate, synchronous trust-store transaction. It removes one immutable trust-grant ID. The same store lock serializes it with import publication, and later imports cannot reuse a revoked preview. No local key or record grants Lua execution. Whole-process OOM, sustained timer load and malformed Binder traffic remain release gates before opening local execution.

## Build and Android verification

Run `./gradlew :app:assembleDeveloper` to produce the isolation build. It is unsigned by default. The Developer signing configuration accepts a dedicated PKCS12 file via `HYPRDROID_DEVELOPER_KEYSTORE_FILE` and its password via `HYPRDROID_DEVELOPER_KEYSTORE_PASSWORD`. Use the alias `hyprdroid-developer`. Keep the private file outside source control, retain it securely between updates, and keep it distinct from the normal APK signing key and from plugin Ed25519 keys.

CI checks both packaged manifests and DEX code, then installs both apps on Android 15 under different UIDs. Its disposable emulator certificate is for that job only. The manual workflow uses the separate persistent key stored in the branch-scoped developer-signing environment. It publishes the signed isolation APK only after an Android 15 update check. The Developer signing certificate SHA-256 is 5c30c0c6b00e6170775331db79585f667870092fea380110ac1434ece20a7bff.

### Pixel 9

После получения APK, подписанного постоянным сертификатом Developer Edition, установите его рядом с обычным HyprDroid. Проверьте, что Android показывает две иконки, у Developer Edition есть подпись в рабочем пространстве, а в системном выборе домашнего приложения доступен обычный HyprDroid. Измените пространство в Developer Edition и убедитесь, что настройки обычного приложения не изменились.

Из каталога уже подписанного проекта my-tile выполните команды ниже. Используйте существующий ключ, генерация нового не нужна.

    public_key="$HOME/.hyp-my-tile-keys/developer.pub.pem"
    archive=dist/my-tile-1.0.0.hyp
    signature=dist/my-tile.signature.json
    hyp verify "$archive" --signature "$signature" --public "$public_key"
    termux-setup-storage
    cp "$public_key" "$archive" "$signature" "$HOME/storage/downloads/"

1. Откройте терминал именно в HyprDroid Developer. Выполните `hypr dev trust add`, выберите `developer.pub.pem` в Downloads, сравните fingerprint с выводом SDK и подтвердите.
2. Выполните `hypr dev import`. Выберите `my-tile-1.0.0.hyp`, затем `my-tile.signature.json`. Сверьте SHA-256 и разрешения `ui.tile`, `state.local` и подтвердите импорт.
3. Проверьте `hypr dev list` и `hypr dev info my-tile`. Принудительно остановите приложение, откройте снова и убедитесь, что ключ и пакет сохранились.
4. Выполните `hypr tile add my-tile`. Терминал должен сообщить, что исполнение локальных пакетов отключено.
5. Выполните `hypr dev trust remove <fingerprint>`. В информации о пакете должен появиться статус `Trust revoked`. Повторное добавление ключа должно сохранить этот статус. Удалить запись можно командой `hypr dev remove my-tile`.

Приватный `developer.pem` остаётся в Termux.

### Physical validation of SDK 0.1.1 and the isolation APK

The project owner reported 20/20 checks on a Pixel 9 running Android 17.

| Build | Result | Checks |
| --- | --- | --- |
| SDK 0.1.1, `ac9c395` | 8/8 | Update, local Preview, browser launch, HTML rendering, server shutdown, key generation, signing and verification |
| HyprDroid 10191, `84f7b5a` | 5/5 | In-place update, existing tiles, Clock format persistence, terminal and DroidFetch, Android HOME |
| Developer version code 14, `f85bf80` | 7/7 | Parallel installation, distinct icons, edition banner, independent workspaces and data, absence from HOME, settings persistence |

This validates the isolation APK. Local package installation and execution remain disabled.

### Pixel 9 Android 17 validation of Runtime hardening

The owner reported 17/18 checks for standard APK 10197 and Developer version 26. Standard HyprDroid passed 8/8, load checks 4/5, and Developer Edition 5/5. Four Clock Tiles ran simultaneously with responsive UI and persistent state. Package IDs, independent data and HOME behavior were verified. No bugs were observed. The ten-minute load test was not run. These results predate local package import.

The local-import update CI uses the previously signed Developer version 26 and ordinary build 10197 as upgrade fixtures.


### Opening a tile from an empty workspace

In the shell menu, **Оконный тайл** opens a neutral tile with the module picker in Clean and Classic. **Терминал** starts an empty tiling workspace with the command panel already selected. An existing layout is retained when you reopen it.

Before this fix, the window menu could create and persist a tiling session in Classic while the pager continued showing the shortcut canvas. The window picker is internal to HyprDroid and does not require the Android HOME role. Developer Edition remains a regular launcher-list app.

The signed update CI for this fix uses the previously released Developer version 54 as its upgrade baseline. The workspace UI test creates tiles by pressing the real menu and window controls, and checks restoration in a separate invocation after force-stopping the app.

On Android 15, the pre-fix build passed the fresh Clean case and timed out waiting for the neutral picker after switching to Classic. The fixed build passed both cases, module selection, closing and recreating an empty workspace, process-stop restoration, and a real terminal command. The signed update preserved the Developer installation from version 54 to version 64.

[Pre-fix reproduction](https://github.com/PseudoEF/HyprDroid/actions/runs/45404138647) · [Workspace regression phases](https://github.com/PseudoEF/HyprDroid/actions/runs/45408170434) · [Signed upgrade](https://github.com/PseudoEF/HyprDroid/actions/runs/45404374758)
