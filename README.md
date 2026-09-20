# Natalia Camouflage Prototype v0.1

Prototype pertama Android untuk pipeline:

MediaProjection -> screen frames -> detector hook -> camouflage state -> overlay.

## Sudah dibuat
- MediaProjection screen capture.
- Foreground service.
- ImageReader menerima frame layar.
- Overlay controller sederhana.
- Demo CAMOUFLAGE ON/OFF dengan fade 150 ms.

## Belum dibuat
Detector Natalia/Camouflage sebenarnya belum dimasukkan. Tahap berikutnya adalah ROI crop, dataset NORMAL/CAMOUFLAGE, computer vision/classifier, lalu GPU compositor dan streaming encoder.

Prototype ini tidak membaca memory atau data internal game.
