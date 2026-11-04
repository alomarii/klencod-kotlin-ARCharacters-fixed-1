package com.example.archaracters

import android.content.Context

data class CharacterInfo(val file: String, val name: String, val persona: String)

object Characters {
    /** يقرأ كل ملفات .glb من assets/models. ملف نصي بنفس الاسم (.txt) يحدد شخصية الكائن. */
    fun load(ctx: Context): List<CharacterInfo> {
        val files = ctx.assets.list("models")?.filter { it.endsWith(".glb", true) }?.sorted()
            ?: emptyList()
        return files.map { f ->
            val base = f.substringBeforeLast('.')
            val persona = try {
                ctx.assets.open("models/$base.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "شخصية ودودة ومرحة وفضولية"
            }
            CharacterInfo(f, base.replace('_', ' ').replace('-', ' '), persona.trim())
        }
    }
}
