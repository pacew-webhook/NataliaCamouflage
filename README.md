# Natalia Camouflage Prototype v0.1

Prototype Android untuk menguji pipeline:

MediaProjection -> screen frames -> detector hook -> camouflage state -> overlay effect.

## GitHub Actions

Project ini sudah disiapkan untuk build di GitHub Actions tanpa bergantung pada `./gradlew` di repository.

Workflow:

1. Checkout repository
2. Setup JDK 17
3. Setup Gradle 8.10.2
4. Jalankan `gradle assembleDebug`
5. Upload `app-debug.apk` sebagai artifact

Workflow berada di `.github/workflows/build.yml`.

## Yang sudah ada

- Android project Kotlin.
- MediaProjection permission flow.
- Foreground service untuk screen capture.
- ImageReader menerima frame layar.
- Overlay controller sederhana.
- Demo CAMOUFLAGE ON/OFF.
- State transition dengan fade 150 ms.

## Yang belum ada

Detector Natalia/Camouflage yang sebenarnya belum dimasukkan.

Tahap berikutnya:
1. Tambahkan ROI crop.
2. Kumpulkan screenshot/video berlabel NORMAL dan CAMOUFLAGE.
3. Uji computer vision sederhana.
4. Bila tidak cukup stabil, train classifier ringan dan convert ke TensorFlow Lite.
5. Ganti `setDemoCamouflage()` dengan output detector.
6. Setelah preview stabil, buat compositor GPU dan encoder/RTMP.

## Build lokal

Jika Gradle tersedia:

```bash
gradle assembleDebug
```

APK berada di:

```text
app/build/outputs/apk/debug/app-debug.apk
```
