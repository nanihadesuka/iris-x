# Iris X

Android companion for the Fujifilm X100VI: Bluetooth link, geotagging, photo and video transfer, gallery, compression, Google Photos backup.
Kotlin and Compose, modules `app`, `core:designsystem`, `domain`, `data`, `feature:home|pairing|settings`, `camera:api|kotlin` (the camera SDK).

## Read first

| Question | File |
| --- | --- |
| Features and roadmap | `MANIFEST.md` |
| Screens and behaviour | `docs/design/UI_UX_DESIGN.md` |

Also: `PLAN.md` (architecture and key decisions), `docs/SCREENS.md` (map of screens to code).

## Hard rules

- **Never delete anything from the camera.** It is read-only. Delete only touches phone copies. Camera delete needs the user's explicit go-ahead.
- **Do not guess camera commands.** An earlier experiment caused an SD card error on the camera. Use only the commands already in `:camera:kotlin`.
- **Separate guesses from facts.** A guess stays a guess until a log settles it.
- **Do not commit** unredacted logs or third-party code. Keep them out of this repo.
- **Commit or push only when asked.**
- **Do not tap or swipe on the user's phone without checking first.** They may be using it. Screenshots and logs are fine.
- **No emulator** unless asked. Mockups are Android Studio `@Preview`s.

## Working with the user

They prefer action over questions: build what they ask, and report what was and was not tested. They care about smooth animation, solid backgrounds and quick feedback. The app is dark only and uses in-app messages (`AppMessages`), never system toasts.

## Tooling on this machine (Windows, Git Bash)

- **Build:** `./gradlew :app:assembleDebug --console=plain`, and check the exit code before installing. Gradle has installed a stale APK after a failed run.
- **`java.net.BindException: Address already in use`:** run `adb kill-server`, wait a few seconds, and rerun.
- **adb paths:** set `MSYS_NO_PATHCONV=1` for adb only; it breaks Windows paths for `python`.
- **Large multi-line edits:** write a patch script to the scratchpad. Inline heredocs with quotes can fail.
- **App launch:** `adb shell am start --user 0 -n com.nanihadesuka.irisx/com.nanihadesuka.irisx.MainActivity`. `monkey` fails on this phone's second user.
- **Logs:** `adb logcat | grep IrisXDiag`.
