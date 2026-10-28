# GT-R Speedometer (Android)

GPS speedometer for Android with a dashboard inspired by the Nissan GT-R R35 gauge cluster.

- 0–300 km/h speedometer with carbon face, red illuminated ring, animated needle and digital readout
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
