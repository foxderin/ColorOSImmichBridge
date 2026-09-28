# Immich SAF

Android **Storage Access Framework** provider for [Immich](https://immich.app).
Exposes your Immich server as a document source, so any app's file/photo picker
can browse and select assets straight from Immich — no photo picker, no
upload/download round trips.

Built for use together with [NoPhotoPickerAPI](https://github.com/foxderin/NoPhotoPickerAPI)
(which redirects forced Photo Picker requests to SAF), but it works with any
app or file manager that speaks SAF/DocumentsUI.

## What it does

- Registers as a `DocumentsProvider` (`com.foxderin.immichsaf.documents`)
- Roots: **全部照片** (all assets, newest first) + one folder per album
- MIME types: `image/*`, `video/*`
- Thumbnails streamed from Immich for picker grids
- Originals are fetched on demand (cached under `cacheDir`, LRU-pruned at 1 GiB)
- Read-only, API-key auth (`x-api-key`)

## Setup

1. In Immich web UI: **Settings → API Keys → New API Key** (read-only is enough).
2. Install the APK, open **Immich SAF**, enter your server URL
   (e.g. `http://192.168.1.10:2283`) and the API key, tap **测试连接**.
3. The root "Immich" now appears in DocumentsUI / any SAF picker.

## Verified against

- Immich API 3.2.0 OpenAPI spec (`/api/albums`, `/api/search/metadata`,
  `/api/assets/{id}/original`, `/api/assets/{id}/thumbnail`, `/api/server/about`)
- Android 16 (SDK 36); `minSdk 30`

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
