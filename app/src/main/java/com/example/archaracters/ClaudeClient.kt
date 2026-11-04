package com.example.archaracters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ClaudeClient {
    // يمكنك تغيير الموديل من هنا
    const val MODEL = "claude-sonnet-5-5"

    /** history: قائمة (role, text) حيث role = "user" أو "assistant" */
    suspend fun chat(
        apiKey: String,
        system: String,
        history: List<Pair<String, String>>
    ): String = withContext(Dispatchers.IO) {
        // يجب أن تبدأ المحادثة برسالة user
        val msgs = history.dropWhile { it.first != "user" }
        val arr = JSONArray()
        msgs.forEach { (role, text) ->
            arr.put(JSONObject().put("role", role).put("content", text))
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 300)
            .put("system", system)
            .put("messages", arr)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (e: Exception) { text }
                throw RuntimeException("[$code] $msg")
            }
            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.getString("text"))
            }
            sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }
}
