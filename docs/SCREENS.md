# Iris X screens

A map of the screens and their code. Behaviour and reasoning are in [`design/UI_UX_DESIGN.md`](design/UI_UX_DESIGN.md).

Paths are under `feature/<module>/src/main/java/com/nanihadesuka/irisx/feature/<module>/`.

| Screen | File | `@Preview`s |
|---|---|---|
| Shell and bottom navigation | `home/MainShell.kt` | None |
| Home | `home/HomeScreen.kt` | Connected |
| Camera | `home/CameraScreen.kt` | None |
| Library | `home/LibraryScreen.kt` | None |
| Immersive view | `home/PhotoViewerScreen.kt`, `home/VideoUi.kt`, `home/SharedPhoto.kt` | None |
| Compression dialog and preview | `home/CompressUi.kt`, `home/CompressPreview.kt` | None |
| Pairing | `pairing/PairingScreen.kt` | Permissions, searching, in progress, failed |
| Settings | `settings/SettingsScreen.kt` | Settings |

Open a file in Android Studio's Split or Design view to see its previews. Most of the home module needs live data, so run the app for those screens.

## Home
```
 ┌───────────────────────────────┐
 │ (icon) Connected        ||  v │  camera card; a tap expands it
 │        Ready for new photos   │
 └───────────────────────────────┘
 ┌───────────────────────────────┐
 │                               │  latest photo; swipe for older ones
 │            photo              │  a tap opens immersive view
 │ DSCF0425.JPG                  │
 └───────────────────────────────┘
 [Home] [Camera] [Library] [Settings]
```
Expanded card: on card, on phone, location sent, Reconnect, Unpair.

## Camera
```
 On camera                     (refresh) (⋮)   ⋮ = Download all
 [All] [Photos] [Videos]
 [img][img][img][img]   whole photos; tick = on phone, MOV tag = video
 [img][img][img][img]   spinners on top for incoming photos
                 [Get 3 new]
```

## Library
```
 On phone                               (⋮)    ⋮ = Compress all
 [All] [Photos] [Videos]
 Today
 [img][img][▶ ][img]    long press: share, compress, delete
```

## Immersive view
```
 (photo or video on black, status bar hidden)

 (Preview. Downloading HD, 43%)  only while loading
 ━━━━━━━━━━──────────
 Save  Share  Photos  Compress  Details  Delete    one tap shows and hides
```
Swipe sideways to change photo, swipe down or back to close, pinch or double-tap to zoom in steps.

## Pairing
Stepper: permissions, searching, pairing, done. A single camera pairs automatically.

## Settings
Connection, automatic downloads, compression (method, values, estimated saving, two automatic switches), location frequency, unpair, battery settings, diagnostics log.
