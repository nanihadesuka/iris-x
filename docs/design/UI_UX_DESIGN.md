# Iris X UI/UX design

Iris X should stay out of the way: shoot, and the photos appear. The app is opened to look at photos, share them, or find out why something is waiting.

## 1. Principles

1. **Photos first.** Images take the space; status takes as little as it can.
2. **Automatic by default.** Connecting, geotagging and downloading need no taps.
3. **Never leave the user guessing.** Every wait names what is happening, and every failure says what to do next.
4. **Compact but easy to hit.** Dense grids and small type, with touch targets of at least 48 dp.
5. **Motion explains change.** Short fades and slides, and one pulse for "working". Nothing decorative loops while idle.
6. **The camera is read-only.** Iris X copies photos and never deletes from the camera. Deleting a phone copy always goes through the system confirmation.

## 2. Navigation

A bottom bar with four destinations, always opaque and always there outside immersive view. Pairing and immersive view are full-screen, without the bar.

| Tab | Purpose |
| --- | --- |
| **Home** | The camera card (status, what is left to download, link controls) above the latest photos |
| **Camera** | The card's catalog: thumbnails, on-phone badges, select and download |
| **Library** | Photos saved on the phone, by date: view, share, delete the phone copy |
| **Settings** | Connection, photos, location, reliability, camera, diagnostics |

Back from another tab returns to Home, and back from Home leaves the app. Each tab keeps its scroll position.

## 3. Status language

One mapping from connection and transfer state to words, colour and icon, used everywhere. Outside Home, a slim strip shows it above the content whenever something is in progress or wrong.

| State | Title | Detail | Colour |
| --- | --- | --- | --- |
| Searching | Looking for camera | Turn it on, it connects by itself. Last seen 12 min ago | Grey |
| Connecting | Connecting | Setting up the Bluetooth link | Blue, pulsing |
| Connected | Connected | Ready for new photos | Green |
| Asking for Wi-Fi | Waking camera Wi-Fi | Asking the camera to start its network | Blue, pulsing |
| Joining | Joining camera Wi-Fi | Your phone returns to its network afterwards | Blue, pulsing |
| Session | Opening session | Almost there | Blue, pulsing |
| Downloading | Downloading 3 of 12 | DSCF0042.JPG, with a progress bar | Blue |
| Paused | Sync paused | Resume to reconnect | Grey |
| Problem | Bluetooth is off, and so on | The fix, with a button | Amber |

## 4. Screens

**Home.** A card on top shows the connection state and a pause button. A tap expands it to card and phone counts, last location sent, Reconnect and Unpair (which goes to the confirmation in Settings). A button appears in the card when the link needs a fix. The newest photo takes the remaining space; swipe sideways for older ones, and a tap opens immersive view. Home itself shows no photo tools.

**Pairing** is one flow with a visible stepper: *Permissions, Camera, Link, Done*. It starts by itself on first run, pairs automatically when exactly one camera is found, and offers tips if nothing appears after 20 s. If it was opened only to fix permissions and a camera is already paired, it returns straight away instead of scanning.

**Unpairing** is a confirmation dialog stating what happens: the link stops, the phone forgets the camera, and photos stay on both devices. It also tells the user to remove the phone from the camera's own Bluetooth list.

**Camera tab.** Browsing starts on a tap, because it switches the phone onto the camera's Wi-Fi. The grid is full-length as soon as the card lists its photos, with placeholders to scroll through. Each photo's name and type are read once for the whole card, but thumbnails are read only for photos on screen that are not already on the phone. Scrolling onto unread photos after the idle close reconnects, while just opening the tab does not. A tap opens immersive view, including on a photo whose thumbnail is not read yet. Grid cells show an HD marker while their original downloads. Photos sit in date sections, by day unless Settings says weeks, months or years, using the capture time from the camera. A day over 48 photos is split into morning (before 12:00), midday (to 17:00), evening (to 21:00) and night, at most four sections. Each section header has a circle that selects its photos (its videos under the Videos filter). A batch skips what does not apply and says how many: Get skips what is on the phone, Compress skips what is compressed or not yet on the phone. Press and hold a photo to select it, then slide to extend across photos: the slide follows the first photo, selecting or unselecting, and scrolls at the edges. "Get N new" downloads only photos newer than the newest one on the phone. Downloading everything not on the phone is in the overflow menu. RAW files are shown but not selectable. The Wi-Fi session closes after 45 s idle. When the camera signals new photos, any browse ends, spinner placeholders appear at the top (one, then the exact count once the transfer session reports it), and afterwards any reduced copy is replaced by its original, if enabled. Wi-Fi only reconnects for that; with nothing to replace, the grid catches up at the next browse. Grids fill each square cell by default, with a Settings switch to show the whole photo instead, and the black belts the camera adds to thumbnails are trimmed on all four edges, including the phone's own thumbnails, because saved files embed a 160x120 thumbnail with belts (OBSERVED). Portrait thumbnails arrive sideways with no EXIF, so they are turned using the orientation the camera reports. Thumbnails are cached per camera and shown at once, even offline. A refresh reads only photos it has not seen, newest first, drops ones gone from the card, and discards the cache if the newest or oldest cached photo no longer matches (swapped or formatted card).

**Library.** The same date sections and section selection over a dense grid, using the shooting date. A long press selects for multi-share or delete. Removal is manual only. If `Pictures/Iris X` predates this install, Android hides the older photos, so Iris X asks once for read access and keeps an Allow banner if refused. Android has no single-folder media permission, so the grant covers all images, while Iris X lists only that folder.

**Immersive view.** The same screen from Home, Camera and Library: only the photo on black, with the status bar hidden. The navigation bar stays, so one edge swipe goes back. Swipe to other photos (the ones either side are loaded in advance), pinch or double-tap to zoom in steps of 2.5x up to 8x (Telephoto, originals in tiles; a double tap at the maximum returns to normal), swipe down to close (vertical only and only at normal zoom, so it never competes with swiping sideways; the back button also closes). One tap shows the tools and they stay while swiping; another tap hides them. Tools: Save, Share, Photos (moves the phone copy into `Pictures/Iris X/Iris X Backup`, a device folder the user enables once in Google Photos, which then uploads with no prompt; Iris X explains this on first use and shows "Queued" because it cannot see when an upload finishes), Details, Compress, and Delete for the phone copy only. Tools that need a phone copy stay dimmed until it exists.

From the Camera tab, stopping on a photo not yet on the phone shows the camera's preview, which is sharp at screen size and kept in a small disk cache, so reopening it needs no Wi-Fi. Leaving a photo or the viewer cancels its preview read and skips waiting ones, so the camera is free for the photo on screen. Downloads go first and viewing never cancels them. The original comes only on zoom or Save, with progress in the status capsule, and the preview stays until the file is fully saved, compressed if that is on, and drawn at full quality beneath it. A partial image is never shown. Each thing is said once: the capsule covers connecting, downloads and failures, and the line under the photo appears only while a preview is being read for over half a second, when a preview fails, or for videos and RAW. A ready preview shows no text. A sharper image fades in over the current one, never through black.

**Compression.** On-device, with Android's JPEG codec. Two switches, both on by default, compress with the chosen method automatically: when a photo is downloaded (after any upgrade to its original, replacing the phone copy without asking), and when one is sent to Google Photos (before it enters the backup folder). If the media location permission is refused they leave photos uncompressed rather than lose the location. Settings offers three methods: a target size per photo (default 1 MB; quality is tried first, resolution drops only when quality would fall below 70), a percentage reduction, or a manual longest side and quality, with an estimate of the space saved. Compress is a tool in immersive view, a selection action in Library, and "Compress all" in Library's overflow menu; each asks first, because the phone copy is rewritten in place and cannot be restored here. The camera's copy is untouched, EXIF including location is kept, and Iris X refuses to compress without the media location permission. A compressed photo is remembered so automatic originals do not replace it, and it is not compressed twice. The confirmation offers Preview, which saves nothing: a full-screen before and after with sizes, a divider to drag, two-finger zoom, and a Difference view that shows the change amplified 8x. Both sides are drawn at the same pixel size, so the comparison is fair. Adjust changes the method and values for that photo only, without touching Settings. In a bulk action, Previous and Next step through the selection.

**Video.** `.MOV` files download only on request (Save in immersive view, or selected on the Camera tab), never through "Get N new" or "Download all". The file is streamed to storage in chunks of up to about 12 MB, with real progress and no limit from app memory. Videos play in immersive view with Media3. Compress is always manual: choose a preset (1080p, 720p, 480p, H.265), and a new `_small.mp4` is written beside the untouched original. A full-size video cannot be sent to Google Photos: Photos queues the smaller copy, making it first if needed.

**Filters.** Camera and Library have All, Photos and Videos chips, and immersive view stays within the chosen filter. On the Camera tab, a photo not yet read appears only under All, because its type is not known.

**What each action leaves behind.** The Camera tab shows what is on the camera, and Library shows what is on the phone. No action on the phone changes the camera.

| Action | Where | Library | Camera tab | Viewer | Needs Wi-Fi |
| --- | --- | --- | --- | --- | --- |
| Open a photo not on the phone | Camera | No change | No change | Preview, with "Download for the full photo" | Only if no preview is kept |
| Zoom in, or Download | Viewer | Photo appears | Marked as on the phone | Fades to the original | Yes |
| Get selected, Get N new | Camera | Photos appear | Marked as on the phone | | Yes, unless all are on the phone |
| Delete | Library grid | Gone | Back to not downloaded, preview kept | | No |
| Delete | Viewer from Library | Gone | The same | Moves to the next photo, closes after the last | No |
| Delete | Viewer from Camera | Gone | The same | Stays on the photo, showing its preview | No |
| Delete phone copies | Camera selection | Gone | The same | | No |
| Compress | Any | Copy made smaller | Stays on the phone | Stays | No |
| Share | Any | No change | No change | No change | No, phone copies only |

Delete always goes through the system confirmation. A batch skips what does not apply and says so. Download with no camera connected says to connect it.

**Status in immersive view.** The status from the tabs appears over the photo as a small translucent capsule at the top, with the same words and the same Stop and Retry. Each new state opens to show its detail for 4 s, then settles to its title; a tap opens it again. Progress is a thin line along its bottom edge, and it moves down when the viewer's title is showing. It shows steps in progress and failures. For a camera photo not on the phone, it also says when the camera is not connected, with Connect. A photo already on the phone stays clean unless something is happening or wrong, and an idle browse shows nothing.

**Getting new photos.** Settings offers three modes. **On open** (default): a signal from the camera is only remembered, with no Wi-Fi. While Iris X is in front and the camera connected, it fetches after 3 s, so a glance starts nothing. Leaving before that cancels it; once started it finishes. If the camera still has its Wi-Fi up for the transfer, Iris X takes the reserved transfer, otherwise it browses and saves originals of what the listing shows is new. **Always**: fetches as the camera signals. **Ask**: only Refresh, Download or zoom. A card never browsed before is never downloaded by itself. HYPOTHESIS, to test: how long the camera keeps its Wi-Fi up when a signal is left unanswered, and whether it offers those photos again.

**Bluetooth and Wi-Fi off.** The status strip, the Home card and the capsule over a photo say so, with a Turn on button that opens the system's small prompt. Nothing opens by itself. Bluetooth off always shows; Wi-Fi off shows only when the camera is connected or something has failed, since otherwise it changes nothing. With Wi-Fi off a browse stops before waking the camera, and whatever failed is retried once the radio is back. Paused shows neither.

**Download progress.** The status strip, Home card and capsule count the photos in the title (2 of 5), and the bar and the MB figures show the current photo. The capsule keeps the MB figures when it settles. Text only fades when its words change. Numbers roll digit by digit, at most four times a second, and digits keep one width so nothing shifts. The figures move as bytes arrive.

**Saving downloads.** On the Camera tab, photos are received one at a time and saved to the phone beside the transfer, with at most two waiting. A received photo is still saved if the session ends. Videos stream straight to their file.

**Grid marks.** A cell's corner shows a cloud when the photo is in the Google Photos backup folder, and on the Camera tab a tick-down arrow when it is on the phone. Iris X cannot see whether Google Photos has finished uploading, so the cloud means queued in the folder.

**Multi-select.** Selecting opens a row of actions along the bottom, the same design as the viewer's tools, and each acts on all selected photos, skipping those it does not apply to. Camera tab: Download, Share, From phone (deletes phone copies only), Clear. Library: Share, Photos (Google Photos backup), Compress, Delete, Clear. An action with nothing to act on is dimmed.

**Home photo.** The large area shows what is newest: a photo arriving from the camera, then the camera's newest photo if the phone lacks it, then the phone's photos. Bulk downloads live on the Camera tab.

**Pause.** A button on the Home card, the same state as Stay connected in Settings. It ends any browse or transfer, closes Bluetooth and stops the background service, location and wake-ups. Resume, Connect or Retry undo it.

**Retry.** One action everywhere: the Camera tab, the status strip and the Home card all call `CameraBrowser.recover`, and show the same state. Browse and Refresh are never disabled. Retry turns sync back on if it was paused, gets the Bluetooth link back (up to 45 s, naming what it is doing), then browses. After two failures in a row with the link up, it restarts the link as well. A failure it cannot fix says what to do.

**Messages.** Short confirmations are a small dark pill from the app itself, above the navigation, fading after 2.5 s. System toasts are not used.

## 5. Visual system

Dark only, with no dynamic colour, so photos always sit on the same neutral background.

| Token | Value | Use |
| --- | --- | --- |
| Background | `#0B0B0D` | Near black, neutral to photos |
| Surface | `#17171B` / `#212126` | Cards and bars |
| Primary | `#F2B66D` | Warm film amber: actions, selection |
| Secondary | `#8FC7C1` | Muted teal: secondary accents |
| Connected / Busy / Away / Attention | `#5AD19A` / `#6FB1FF` / `#85858F` / `#FFB347` | Status only |

Motion: 150-300 ms. Tabs and full-screen flows change by fade. A photo entering immersive view grows from its exact spot on screen, from cropped to fitted, and closing returns it to the spot of whichever photo is showing; a photo with no spot on screen fades instead. Every screen has a solid background, so one shows through another only during a designed transition, or while a photo travels or is dragged away. List items fade in, loading thumbnails shimmer, and the only continuous animations run during real work.

## 6. Connection and power

**[DESIGN]** marks an Iris X decision.

- **Tiered scan [DESIGN].** The first 60 s after a disconnect, app open or Bluetooth turning on use `LOW_LATENCY`. After that Iris X drops to `LOW_POWER` in 5-minute windows, with the same filters. This saves battery when the camera is off.
- **Wake on power-on [DESIGN].** A Companion Device association lets the system watch for the camera and wake Iris X, offered once while connected and from Settings. Scanning stays as the fallback.
- **Instant wake [DESIGN].** Opening the app, tapping Retry, or Bluetooth state changes cut any wait short.
- **Self-restart [DESIGN].** An engine crash is logged and restarted with a delay. The service is sticky, and it starts on boot, on update, and from a 15-minute worker.
- **Safe foreground start [DESIGN].** If Android refuses the location service type from the background, the service runs as `connectedDevice` only instead of crashing.
- **One camera session at a time [DESIGN].** Auto transfer and browsing share a lock, because the camera takes one session.
- **Wi-Fi only on demand.** Wi-Fi is up only during a transfer or a browse session.
- **Selected download.** Ask for originals, read the photo's info, then read the photo.

## 7. Hobbyist backlog

Not built yet, roughly by value:

1. Fujifilm film simulation in the viewer and as a filter (needs MakerNote parsing).
2. Map of geotagged photos.
3. Shooting stats: most-used settings, photos per month.
4. Filters on the Camera tab, using the camera's own search modes.
5. RAW download.
6. Camera battery and card space on Home, if readable over BLE.
7. Remote shutter.
8. Home-screen widget with the latest photo.
