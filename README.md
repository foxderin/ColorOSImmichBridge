# ColorOS Immich Bridge

Android **Storage Access Framework** provider for [Immich](https://immich.app), built for ColorOS.
Exposes your Immich server as a document source, so any app's file/photo picker
can browse and select assets straight from Immich — no photo picker, no
upload/download round trips.

Use together with [NoPhotoPickerAPI](https://github.com/foxderin/NoPhotoPickerAPI)
(which redirects forced Photo Picker requests to SAF), but it works with any
app or file manager that speaks SAF/DocumentsUI.

## What it does

- Registers as a `DocumentsProvider` (`com.foxderin.colorosimmichbridge.documents`)
- Roots: **全部照片** (all assets, newest first) + one folder per album
- MIME types: `image/*`, `video/*`
- Thumbnails streamed from Immich for picker grids
- Originals are fetched on demand (cached under `cacheDir`, LRU-pruned at 1 GiB)
- Read-only; accepts a permanent API key (`x-api-key`) or a session JWT
  (`Authorization: Bearer`) — auto-detected

## Setup

1. In Immich web UI: **Settings → API Keys → New API Key** (read-only is enough).
2. Install the APK, open **Immich SAF**, enter your server URL
   (e.g. `http://192.168.1.10:2283`) and the API key, tap **测试连接**.
3. The root "Immich" now appears in DocumentsUI / any SAF picker.

## Verified

- Immich 3.2.2, real server: albums, `search/metadata` pagination, thumbnails,
  originals (byte-identical to `/api/assets/{id}/original`)
- End-to-end on Android 16 / ColorOS 16: NoPhotoPickerAPI rewrite →
  DocumentsUI → Immich root → album → pick → `content://` URI returned to the
  calling app; originals served byte-identical to the server (10679 = 10679)
- API shapes checked against the Immich 3.2.0 OpenAPI spec (`/api/albums`,
  `/api/search/metadata`, `/api/assets/{id}/original|thumbnail`,
  `/api/server/version`); `minSdk 30`

## ColorOS note

ColorOS blocks cold-starts of freshly sideloaded apps by other apps
(`OplusAppStartupManager: prevent start ... by contentprovider
com.android.documentsui`), so the root may not appear until the app has been
opened manually once. Fix: enable 自启动/允许后台运行 for Immich SAF, or (root)
add `<dynamic pkgName="com.foxderin.colorosimmichbridge" type="1" source="1" switch="1"/>`
to `/data/oplus/os/startup/startup_dynamic_list.xml` and reboot.

## Testing without a server

```bash
python3 tools/mock_immich.py 2283 mock-key   # spec-shaped mock on :2283
```

## Limitations

- `search/metadata` pagination is capped at 20 000 assets per folder to keep
  pickers responsive.
- Originals are downloaded before being handed out (random access / video
  seeking); first open of a large video takes as long as the download.
- Read-only: no upload, delete or rename through SAF.

## Build

```bash
./gradlew assembleDebug     # installable debug APK
./gradlew assembleRelease   # minified release APK (debug-signed without keystore env)
```

CI builds both variants on every push and uploads them as workflow artifacts.
