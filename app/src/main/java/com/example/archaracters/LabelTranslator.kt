package com.example.archaracters

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions

/** يترجم أسماء الأشياء المكتشفة من الإنجليزية إلى العربية على الجهاز (ML Kit Translate). */
class LabelTranslator {
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.ARABIC)
            .build()
    )
    private val cache = HashMap<String, String>()
    private var ready = false

    init {
        // تنزيل نموذج الترجمة مرة واحدة (~30MB) ثم يعمل دون إنترنت
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { ready = true }
    }

    fun translateAll(labels: List<String>, done: (List<String>) -> Unit) {
        if (!ready || labels.isEmpty()) { done(labels); return }
        val result = arrayOfNulls<String>(labels.size)
        var pending = labels.size
        labels.forEachIndexed { i, label ->
            val cached = cache[label]
            if (cached != null) {
                result[i] = cached
                if (--pending == 0) done(result.mapIndexed { j, r -> r ?: labels[j] })
            } else {
                translator.translate(label)
                    .addOnSuccessListener { t -> cache[label] = t; result[i] = t }
                    .addOnCompleteListener {
                        if (--pending == 0) done(result.mapIndexed { j, r -> r ?: labels[j] })
                    }
            }
        }
    }

    fun close() = translator.close()
}
