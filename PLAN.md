# Iris X – Architecture and plan

Companion app for the **Fujifilm X100VI**. Features and roadmap are in `MANIFEST.md`, and screens in `docs/design/UI_UX_DESIGN.md`. This file covers how the app is built and why.

## 1. Requirements

| # | Requirement | Where it lives |
|---|-------------|----------------|
| R1 | Low-power Bluetooth link that reconnects by itself | `:data`, `CameraSyncService`, tiered scan, Companion Device wake-up |
| R2 | Send the phone's location to the camera | `:data` |
| R3 | New photos reach the phone automatically | `DownloadNewPhotosUseCase`, on the camera's reserved-transfer request |
| R4 | Browse the card; download photos and videos on request | `CameraBrowser`, `:camera:kotlin` |
| R5 | Save where other apps can see files | `MediaStorePhotoStore`, `Pictures/Iris X` |
| R6 | Pairing and unpairing | `:feature:pairing`, `ForgetCameraUseCase` |
| R7 | On-device compression and selective Google Photos backup | `PhotoCompressor`, `VideoCompressor`, `Pictures/Iris X/Iris X Backup` |
| R8 | **Never delete from the camera** | No camera-side delete path exists |

## 2. Key decisions

- **Iris X's own Kotlin SDK talks to the camera.** It contains no vendor code and has not been tried on a real camera.
- **`targetSdk = 36` with `ACCESS_LOCAL_NETWORK`.** Android 17 otherwise silently blocks traffic to the camera.
- **Automatic means JPEG.** Videos download and compress only on request. RAW is never downloaded.
- **Compression on the device,** with Android's JPEG codec and Media3 Transformer, not FFmpeg.
- **Backup through a Google Photos device folder,** not an API, so no Google Cloud project or sign-in is needed.
- **Dark only, with no dynamic colour,** so photos always sit on the same background.

## 3. Architecture

Clean architecture, unidirectional data flow, single activity with Compose and Navigation 3.

```
:app                 DI wiring (Koin), MainActivity, Navigation 3 host, manifest
:core:designsystem   Theme and shared composables
:domain              Models, repository interfaces, use cases. No Android dependencies
:data                Bluetooth, location, Wi-Fi, storage, DataStore, background work
:feature:pairing     Pairing flow
:feature:home        Main shell and tabs: Home, Camera, Library, immersive viewer
:feature:settings    Settings
```

Rules: `feature → domain, core`; `data → domain`; `app → everything`; `domain` depends on nothing but Kotlin. Long-running work lives in singletons outside any screen (`CameraBrowser`, `PhotoCompressor`, `VideoCompressor`), so switching tabs does not interrupt it.

Main libraries: Compose BOM, Material 3, Navigation 3, DataStore, WorkManager, Play Services Location, Koin, Telephoto (viewer gestures), Media3 (playback and video compression).

## 4. Runtime

```
Companion Device association: the system wakes the app when it sees the camera
        │
CameraSyncService (foreground)
  ├─ Bluetooth link   tiered scan, reconnect, geotag requests
  ├─ Location         every 15, 5 or 1 min
  └─ Reserved transfer, on AP state 0x8003
        DownloadNewPhotosUseCase → camera session → MediaStore

CameraBrowser (user browsing)
  BLE function launch → camera AP → Wi-Fi → camera session in catalog mode
  handles, then thumbnails, cached per camera; closes after 45 s idle

Only one session at a time: the camera takes one session.
```


## 5. Bluetooth reference


- Pairing service `123d8f06-62a1-4935-9322-833c531ee225`.
- Geotag service `3b46ec2b-48ba-41fd-b1b8-ed860b60d22b`, characteristic `0f36ec14-29e5-411a-a1b6-64ee8383f090`. 20-byte little-endian packet: `int32 lat*1e7, int32 lon*1e7, int32 alt, 4 pad, u16 year, u8 month, day, hour, min, sec`.
- The camera asks for a fix by notifying `ad06c7b7-f41a-46f4-a29a-712055319122` with `01 00`.
- Camera AP gateway `192.168.0.1`, PTP/IP port `55740`.

## 6. History

1. First pass: multi-module skeleton, Kotlin PTP/IP client, single status-orb Home screen.
2. Camera refused the connection: traced to the Android 17 permission. Later replaced by Iris X's own SDK.
3. Catalog and originals working, then the bottom-navigation redesign.
4. Camera-card Home, shared immersive viewer, compression, backup folder, video.
