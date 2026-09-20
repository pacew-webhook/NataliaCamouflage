# Natalia Camouflage — Final 1.0

Android final-project foundation for realtime Natalia camouflage detection.

The capture, temporal state machine, overlay and TFLite dependency are included.
The bundled detector remains a fallback visual heuristic until a trained
Natalia-vs-Camouflage model is supplied. Replace the classifier section in
`CamouflageDetector.kt` with the trained TFLite model; do not treat the
fallback heuristic as proof of gameplay recognition.

Build: JDK 17, Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21.
