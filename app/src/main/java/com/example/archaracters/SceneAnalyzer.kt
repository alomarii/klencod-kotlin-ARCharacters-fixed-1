package com.example.archaracters

import android.os.SystemClock
import com.google.ar.core.Frame
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/** يحلل صورة الكاميرا بـ ML Kit لمعرفة الأشياء الظاهرة (مرة كل ~1.2 ثانية). */
class SceneAnalyzer(private val onLabels: (List<String>) -> Unit) {
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build()
    )
    private var busy = false
    private var last = 0L

    fun analyze(frame: Frame, rotationDegrees: Int = 90) {
        val now = SystemClock.elapsedRealtime()
        if (busy || now - last < 1200) return
        val image = try {
            frame.acquireCameraImage()
        } catch (e: Exception) {
            return // الصورة غير جاهزة بعد
        }
        busy = true
        last = now
        val input = InputImage.fromMediaImage(image, rotationDegrees)
        labeler.process(input)
            .addOnSuccessListener { labels ->
                onLabels(labels.sortedByDescending { it.confidence }.take(5).map { it.text })
            }
            .addOnCompleteListener {
                image.close()
                busy = false
            }
    }

    fun close() = labeler.close()
}
