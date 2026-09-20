# Natalia Camouflage v0.9

Prototype gabungan Screen Capture → frame processing → temporal detector → overlay.

**Catatan penting:** detector di `CamouflageDetector.kt` adalah heuristik placeholder, bukan model AI yang telah dilatih khusus untuk Natalia. Tujuannya menguji pipeline dan state machine. Untuk deteksi Natalia/Camouflage nyata, tahap berikutnya mengganti detector dengan model/fitur visual berdasarkan screenshot/video referensi.

Build GitHub Actions: JDK 17, Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21.
