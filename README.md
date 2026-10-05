# Live TV Premium

Standalone Android TV / TV Box IPTV application.

## Features

- Premium animated TV UI
- Category-based Home
- D-pad / OK / Back navigation
- Growing focus animation
- GitHub Raw M3U auto-load
- Duplicate channel filtering
- Local playlist cache
- Media3 HLS playback
- Retry-safe playlist loading
- Exit confirmation from Home
- No website/server dependency

## Playlist

Configured in:

`app/src/main/java/com/livetv/premium/BrandConfig.kt`

Default:

https://raw.githubusercontent.com/f6388600-ai/iptv-auto-playlist/refs/heads/main/playlist.m3u

## Change branding later

Edit `BrandConfig.kt` in GitHub:

- `APP_NAME`
- `REMOTE_LOGO_URL`

For the actual Android launcher icon, replace:

`app/src/main/res/drawable/app_logo.xml`

and rebuild the APK.

## Build

Use Android Studio or GitHub Actions.

GitHub Actions workflow:

`.github/workflows/build-apk.yml`

The workflow provisions Gradle 8.13 and JDK 17, then builds:

`app/build/outputs/apk/debug/app-debug.apk`

This project intentionally does not depend on a checked-in Gradle wrapper JAR; the GitHub workflow installs the pinned Gradle version directly.

## Notes

A live stream cannot be guaranteed to be buffer-free because playback depends on the source server and network. The app caches playlist metadata, prepares the player immediately, and keeps failures from crashing the app.
