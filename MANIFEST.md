# Iris X – Feature Manifest (user point of view)

**Iris X is the quiet assistant for your Fujifilm X100VI.** You shoot; your phone takes care of the rest.

## What it does for you

1. **Your photos are tagged with where you took them.**
   Your phone shares its location with the camera, so each photo gets GPS coordinates and the right time without you doing anything.
2. **Your phone stays linked to the camera.**
   Iris X reconnects over Bluetooth Low Energy by itself when the camera is on, searching quickly at first and then at low power. It only uses Wi-Fi and GPS when needed.
3. **New photos come to your phone by themselves.**
   When the camera signals new shots, Iris X joins its Wi-Fi, copies the JPEGs into `Pictures/Iris X`, then fetches the full original of any reduced copy (optional). Placeholders on the Camera tab show them arriving.
4. **You can browse the whole card from your phone.**
   See every photo on the card, scroll while thumbnails load, and download only the new ones, your selection, or everything. The gallery is cached, so it opens at once, even offline.
5. **Every photo opens full screen.**
   Home, Camera and Library share one immersive view. Camera photos open straight away on their preview, say what is loading, and fade into the original when it arrives.
6. **You choose what to back up.**
   One tap on **Photos** queues a photo in a folder Google Photos backs up by itself, with no account in Iris X and no prompt.
7. **You can make photos smaller on the phone.**
   Compress one photo, a selection or all of them, down to a target size (1 MB by default), by a percentage, or with your own resolution and quality. Location and shooting details are kept. Before saving, you can preview the result beside the original, with the sizes and an amplified view of what changed.

8. **Videos too, when you ask.**
   Download a video from the camera, play it in the app, and make a smaller copy. Only that smaller copy can go to Google Photos, and the original stays on your phone.

## How you use it

### First time (about 1 minute)
1. Open Iris X. It asks for the permissions it needs, with a one-line reason each.
2. On the camera: **Menu → Connection Setting → Bluetooth Settings → Pairing Register**. Iris X shows the same instruction and a stepper.
3. Iris X finds the camera and pairs. Confirm on the camera. Done.

### Every day
Nothing. Shoot, and the photos appear.

### Bottom navigation
- **Home:** a camera card with connection status, a pause button, and on tap the details, **Reconnect** and **Unpair**. The newest photo fills the rest, including one arriving or still only on the camera; swipe sideways through them.
- **Camera:** the card's photos and videos, marked when they are on your phone, with All / Photos / Videos filters. **Get N new** downloads what is newer than your newest photo; **Download all** is in the ⋮ menu. Long-press to select.
- **Library:** the photos and videos on your phone, by date, with the same filters. Long-press to share, compress or delete a selection; **Compress all** is in the ⋮ menu.
- **Settings:** connection, automatic downloads, compression with an estimate of the space saved, location frequency, battery settings, and a diagnostic log.

### Immersive view
- Tap a photo on any tab to open it. It grows from where it was, and returns there when you close it.
- Swipe sideways for other photos, within the chosen filter; neighbours load in advance.
- A camera photo shows its preview with a progress bar and percentage until the original arrives.
- Pinch or double-tap to zoom in steps of 2.5×, up to 8×.
- Swipe down, or use back, to close.
- Tap once to show the tools, again to hide them. They stay while you swipe: **Save**, **Share**, **Photos**, **Compress**, **Details**, **Delete** (phone copy only).

## Rules Iris X follows
- **The camera is read-only.** Iris X copies photos and never deletes anything from the camera.
- **Automatic means JPEG.** Videos download only when you ask. RAW files are listed on the Camera tab but never downloaded.
- **Never downloads twice.** A photo already on the phone is not saved again, unless the camera has a larger original.
- **Compressing by hand asks first.** Automatic compression, on by default when downloading and when sending to Google Photos, does not ask and can be turned off in Settings. Either way it replaces the phone copy and cannot be undone on the phone. The camera's copy is untouched, and a compressed photo is not replaced by its original automatically.
- **Light on battery.** Bluetooth stays low power, GPS is used sparingly, and Wi-Fi runs only during a transfer or a browse.
- **Your photos stay on your devices.** Iris X has no server and no account. Nothing leaves your phone except through Google Photos or an app you share with.

## Roadmap

### To confirm on the camera
These are built but not yet fully proven:
- A new shot landing on the phone with no interaction, including placeholders and the upgrade to the original.
- Wake on camera power-on, and unpairing.
- Portrait previews turning the right way.
- Google Photos picking up the `Iris X Backup` folder, and compression keeping the location.
- Downloading a video from the camera, and whether compressed videos keep their location and colours.

### Next
1. Video: presets you can customise, and a compression notification outside the app.
2. Film simulation shown in the photo details.
3. Map of geotagged photos.
4. Shooting stats: most-used settings, photos per month.
5. Filters on the Camera tab.
6. RAW download.
7. Camera battery and card space on Home, if readable over Bluetooth.
8. Remote shutter.
9. Home-screen widget with the latest photo.

## Not in scope
Deleting from the camera, photo editing, and other camera models (they may work, but only the X100VI is targeted).
