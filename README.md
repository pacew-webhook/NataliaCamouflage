# Natalia Camouflage v1.4.1 – Live Camera Camouflage

Based on v1.0 STARTUP-STABILITY-FIX.

## New behavior
- The app continues detecting Natalia camouflage from the game screen with `natalia_camouflage.tflite`.
- When AI state is `CAMOUFLAGE`, a front-camera overlay activates a person-segmentation effect.
- The camera background remains visible while the detected person area is covered with a camouflage pattern.
- When AI returns to `NORMAL`, the camera returns to the normal preview.
- CameraX + ML Kit Selfie Segmentation are used for the live camera effect.

## Important
The camera overlay is an Android screen overlay. Whether a particular live/streaming app includes Android overlays in its broadcast depends on how that app captures the screen/camera. The game detector itself remains independent of the camera segmenter.


### Build compatibility fix

The project uses Kotlin 2.3.20 because LiteRT 2.2.0 is compiled with Kotlin metadata 2.3.0. The build uses the Kotlin 2.3.x compiler family required by the LiteRT 2.2.0 API metadata. Kotlin compilation is forced in-process in CI to avoid Kotlin daemon startup/GC failures.
