# Codex instructions — HyprDroid

Read `docs/PROJECT.md` and `docs/ROADMAP.md` before changing code.

- Android launcher, Kotlin + Jetpack Compose, no root/Shizuku/ADB required for end users.
- Never claim arbitrary third-party apps can be tiled without privileged Android window APIs.
- Keep standard Android HOME activity behavior; preserve a safe way to switch back to another launcher.
- Work in small verifiable increments; update ROADMAP and CHANGELOG after completed features.
- Do not add QUERY_ALL_PACKAGES or AccessibilityService without explicit justification and approval.
- Imported dotfiles are DATA only; never execute scripts or arbitrary code from them.
- Validate configs, provide preview and rollback, version config schemas.
- Run `gradle :app:assembleDebug` when possible. If environment lacks Android SDK or network, report unverified build honestly.
- Keep user-facing UI touch-friendly and responsive on phone displays.
- First task: verify the build and fix any dependency/API errors before adding features.
