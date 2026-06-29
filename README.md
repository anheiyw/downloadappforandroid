# Media Downloader

An Android media downloader built with Kotlin and Jetpack Compose.

## Features

- Parse and download media from Bilibili, Instagram, and Twitter/X links.
- Download Bilibili DASH video and audio streams, then merge them into MP4.
- Save downloaded media to Android media storage.
- View downloaded media history in the app gallery.

## Tech Stack

- Kotlin
- Android Jetpack Compose
- OkHttp
- Coroutines
- MediaStore / MediaMuxer

## Build

Open the project in Android Studio, let Gradle sync, then run the app configuration on an Android device or emulator.

From a local terminal:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Notes

local.properties, build output, IDE metadata, and local cache directories are intentionally ignored by Git.
