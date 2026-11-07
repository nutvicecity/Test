# GT-R Speedometer (Android)

GPS speedometer for Android with a dashboard inspired by the Nissan GT-R R35 gauge cluster.

- Portrait layout; 0–300 km/h speedometer with carbon face, red illuminated ring, animated needle and digital readout
- G-force meter (accelerometer)
- Battery level shown as a "fuel" gauge
- Current / max speed, trip distance, trip time, altitude, coordinates, GPS signal
- "แผนที่" button opens the current position in your map app
- Long-press the stats panel to reset the trip

Pure Android framework (Kotlin, custom `Canvas` drawing), no third-party libraries. minSdk 24.

## Build

GitHub Actions builds the APK on every push (`.github/workflows/build.yml`); download it from the
run's **Artifacts**. Locally: `./gradlew assembleDebug`.

`preview/index.html` is a browser preview of the same drawing code with simulated driving.

Nissan and GT-R are trademarks of Nissan Motor Co., Ltd.; this is an unofficial personal project.

---

# YT MP3 (Android) — `ytmp3/`

Downloads the audio of a YouTube link and converts it to MP3 on the phone.

- Paste a link, or **Share** a video from the YouTube app to "YT MP3" (download starts automatically)
- Quality: 320 / 192 / 128 kbps; progress, cancel, open and share the result
- Saved to `Music/YTMP3`; title metadata embedded
- Uses [youtubedl-android](https://github.com/yausername/youtubedl-android) (yt-dlp + ffmpeg, GPL-3.0);
  yt-dlp updates itself once a day, or tap "อัปเดตตัวดาวน์โหลด" when downloads start failing
- minSdk 24, arm64-v8a / armeabi-v7a

**APK:** each push publishes `YT-MP3.apk` to the `ytmp3-latest` release (and as the `YT-MP3-apk` Actions artifact).
Locally: `./gradlew :ytmp3:assembleRelease`.

Only download content you own or have permission to download; doing so may be against YouTube's Terms of Service.
