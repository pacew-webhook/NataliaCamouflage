# Natalia Camouflage V2

Android Studio project for on-device Natalia camouflage detection.

## V2 changes
- **ROI-first detection:** the detector crops a configurable region of the MediaProjection frame before resizing it to the TFLite input.
- **Adjustable ROI:** edit X/Y/width/height from the app, or use the on-screen ROI editor while the detector is running.
- **Confidence threshold:** configurable 40–95%.
- **Temporal stabilizer:** configurable consecutive ON/OFF frames to reduce flicker.
- **Model-aware input:** reads the TFLite input tensor shape/type and supports the existing FLOAT32 model convention.
- **Probability handling:** accepts already-normalized probabilities and applies softmax to logits when needed.
- **Live front camera:** CameraX + ML Kit Selfie Segmentation. The segmented person is covered with a camouflage pattern only when the detector is ON.
- **ROI guide:** optional full-screen guide over the game.
- **Memory safety:** latest-frame processing and bounded ImageReader buffers.

## Recommended first run
1. Open the project in Android Studio.
2. Build/install the debug APK.
3. Grant **Display over other apps** and **Camera** permission.
4. Press **Start Detector** and approve screen capture.
5. Start with **Preset: Tengah / Natalia**.
6. If detection remains OFF, press **Edit ROI di layar game** and move/resize the cyan box so it covers Natalia.
7. Adjust **Confidence threshold** if necessary.

## Important model note
The included `natalia_camouflage.tflite` is the model supplied with the original project. V2 improves the image pipeline around that model; it does not retrain the model. If the model was trained on a different crop, normalization, or camera orientation, its accuracy may still require a better training dataset/model.

## Build
- Compile SDK 35
- Min SDK 26
- Target SDK 35
- AGP 8.7.3
- Kotlin 2.3.20
- Gradle 8.10.2 (recommended by the included CI workflow)
