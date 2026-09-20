# Natalia Camouflage Final 1.0

Android prototype/final architecture for detecting a persistent visual transition on a Mobile Legends screen and triggering a CAMOUFLAGE overlay.

## Pipeline

MediaProjection → ImageReader → ROI feature extraction → temporal validation → camouflage state → overlay.

## Important accuracy note

This package does **not** contain a trained Natalia AI model. The included detector is a model-free visual transition detector with an explicit `FrameDetector` interface. It is useful for the complete capture/state pipeline, but it must not be represented as a guaranteed Natalia/Camouflage classifier.

To make recognition game-character-specific, add a trained TFLite/ONNX model implementing the same detector contract and train/validate it with real Natalia NORMAL and CAMOUFLAGE frames.

## Build

GitHub Actions uses JDK 17, Gradle 8.10.2, Android Gradle Plugin 8.7.3 and Kotlin 2.0.21.

## Runtime

1. Install APK.
2. Grant overlay permission.
3. Start Screen Capture.
4. Keep the game visible.
5. The detector learns a short NORMAL baseline and then validates persistent visual changes across multiple frames.

## Future model integration

Replace `CamouflageDetector` with a model-backed implementation of `FrameDetector`. `CaptureService` does not need to know whether the detector is heuristic, TFLite, or another inference engine.
