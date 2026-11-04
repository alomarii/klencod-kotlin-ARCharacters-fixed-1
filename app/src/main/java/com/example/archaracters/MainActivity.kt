package com.example.archaracters

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView
import kotlinx.coroutines.launch
import java.util.Locale

private const val CREDIT = "برمجة وتصميم: محمد العمري  730215825"

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val characters by lazy { Characters.load(this) }
    private val translator = LabelTranslator()
    private val analyzer = SceneAnalyzer { en -> translator.translateAll(en) { ar -> onLabels(ar) } }

    // حالة الواجهة
    private var labels by mutableStateOf<List<String>>(emptyList())
    private var reply by mutableStateOf("")
    private var thinking by mutableStateOf(false)
    private var activeName by mutableStateOf<String?>(null)
    private var showSettings by mutableStateOf(false)

    private val histories = mutableMapOf<String, MutableList<Pair<String, String>>>()
    private val nodeNames = HashMap<Node, String>()
    private val seen = mutableSetOf<String>()
    private var lastAuto = 0L

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showApp() else {
                Toast.makeText(this, "الكاميرا مطلوبة لتشغيل الواقع المعزز", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) sendUserMessage(text)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences("ar_prefs", MODE_PRIVATE)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ar"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Toast.makeText(this, "ثبّت بيانات الصوت العربي ثم أعد فتح التطبيق", Toast.LENGTH_LONG).show()
                    try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) {}
                } else {
                    tts?.setSpeechRate(0.95f)
                    ttsReady = true
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) showApp() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ───────────── الذكاء والكلام ─────────────

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say")
    }

    private fun systemPrompt(name: String): String {
        val persona = characters.firstOrNull { it.name == name }?.persona ?: ""
        val scene = if (labels.isEmpty()) "غير معروف" else labels.joinToString("، ")
        return """
            اسمك: $name.
            $persona
            أنت شخصية افتراضية تظهر في الواقع المعزز عبر كاميرا هاتف المستخدم وتعيش في مكانه الحقيقي.
            الأشياء التي تراها الكاميرا الآن (من نظام تعرف آلي وقد يخطئ): $scene.
            تحدث دائماً باللغة العربية فقط، حتى لو كتب المستخدم بلغة أخرى، وافهم اللهجات العربية وردّ بلهجة قريبة من لهجة المستخدم أو بالفصحى المبسطة. استخدم جملاً قصيرة جداً (جملة أو جملتان) وبأسلوب طبيعي يصلح للنطق الصوتي، بلا رموز تعبيرية ولا تنسيق.
            إذا وصلتك رسالة تبدأ بـ [نظام] فهي ملاحظة آلية وليست من المستخدم، فعلّق عليها بعفوية.
        """.trimIndent()
    }

    private fun ask(name: String, text: String, auto: Boolean) {
        val key = prefs.getString("key", "").orEmpty()
        if (key.isBlank()) {
            if (auto) {
                val l = labels.firstOrNull()
                if (l != null) { reply = "$name: أرى $l"; speak("أرى $l") }
            } else {
                reply = "أضف مفتاح Claude API من الإعدادات ⚙ لأتمكن من الرد"
            }
            return
        }
        val hist = histories.getOrPut(name) { mutableListOf() }
        hist.add("user" to text)
        while (hist.size > 20) hist.removeAt(0)
        thinking = true
        lifecycleScope.launch {
            try {
                val answer = ClaudeClient.chat(key, systemPrompt(name), hist.toList())
                hist.add("assistant" to answer)
                reply = "$name: $answer"
                speak(answer)
            } catch (e: Exception) {
                if (hist.isNotEmpty()) hist.removeAt(hist.lastIndex)
                reply = "تعذر الاتصال: ${e.message}"
            }
            thinking = false
        }
    }

    private fun sendUserMessage(text: String) {
        val name = activeName
        if (name == null) {
            reply = "ضع شخصية في المكان أو المس واحدة لتتحدث معها"
            return
        }
        if (thinking) return
        ask(name, text, auto = false)
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن")
        }
        try { speechLauncher.launch(intent) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "التعرف على الصوت غير متوفر، ثبّت تطبيق Google", Toast.LENGTH_LONG).show()
        }
    }

    // ───────────── تفاعل الشخصية مع الواقع (ML Kit) ─────────────

    private fun onLabels(new: List<String>) {
        labels = new
        val name = activeName ?: return
        if (!prefs.getBoolean("auto", true) || thinking) return
        val fresh = new.filter { it !in seen }
        seen.addAll(new)
        if (seen.size > 60) seen.clear()
        val now = SystemClock.elapsedRealtime()
        if (fresh.isNotEmpty() && now - lastAuto > 25_000) {
            lastAuto = now
            ask(name, "[نظام] ظهر أمامك الآن في الكاميرا: ${fresh.joinToString("، ")}. علّق بجملة قصيرة.", auto = true)
        }
    }

    private fun characterOf(node: Node): String? {
        var n: Node? = node
        while (n != null) {
            nodeNames[n]?.let { return it }
            n = n.parent
        }
        return null
    }

    // ───────────── الواجهة ─────────────

    private fun showApp() {
        setContent {
            MaterialTheme {
              CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) {
                    val engine = rememberEngine()
                    val modelLoader = rememberModelLoader(engine)
                    val view = rememberView(engine)
                    val collisionSystem = rememberCollisionSystem(view)
                    val cameraNode = rememberARCameraNode(engine)
                    val childNodes = rememberNodes()
                    var frame by remember { mutableStateOf<Frame?>(null) }
                    var trackingOk by remember { mutableStateOf(false) }
                    var count by remember { mutableIntStateOf(0) }
                    var selected by remember { mutableStateOf(characters.firstOrNull()) }
                    var input by remember { mutableStateOf("") }

                    ARScene(
                        modifier = Modifier.fillMaxSize(),
                        childNodes = childNodes,
                        engine = engine,
                        view = view,
                        modelLoader = modelLoader,
                        collisionSystem = collisionSystem,
                        cameraNode = cameraNode,
                        sessionConfiguration = { session, config ->
                            config.depthMode =
                                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                                    Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                        },
                        onSessionUpdated = { _, updated ->
                            frame = updated
                            trackingOk = updated.camera.trackingState == TrackingState.TRACKING
                            if (trackingOk) analyzer.analyze(updated, 90)
                        },
                        onGestureListener = rememberOnGestureListener(
                            onSingleTapConfirmed = { motionEvent, node ->
                                val tapped = node?.let { characterOf(it) }
                                if (tapped != null) {
                                    activeName = tapped
                                    reply = "أنت تتحدث الآن مع $tapped"
                                    return@rememberOnGestureListener
                                }
                                val ch = selected
                                if (ch == null) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "أضف ملفات GLB إلى assets/models",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@rememberOnGestureListener
                                }
                                val hit = frame?.hitTest(motionEvent.x, motionEvent.y)
                                    ?.firstOrNull { it.isValid(depthPoint = false, point = false) }
                                val anchor = hit?.createAnchorOrNull() ?: return@rememberOnGestureListener

                                val anchorNode = AnchorNode(engine = engine, anchor = anchor)
                                val modelNode = ModelNode(
                                    modelInstance = modelLoader.createModelInstance("models/${ch.file}"),
                                    scaleToUnits = 0.6f
                                ).apply { isEditable = true }
                                try { modelNode.playAnimation(0, loop = true) } catch (_: Exception) {}
                                anchorNode.addChildNode(modelNode)
                                nodeNames[anchorNode] = ch.name
                                nodeNames[modelNode] = ch.name
                                childNodes += anchorNode
                                count++
                                activeName = ch.name
                                ask(ch.name, "[نظام] ظهرتَ للتو أمام المستخدم في غرفته. رحّب به بجملة قصيرة.", auto = true)
                                if (prefs.getString("key", "").isNullOrBlank()) {
                                    reply = "${ch.name}: مرحباً بك!"
                                    speak("مرحباً بك!")
                                }
                            }
                        )
                    )

                    // الشريط العلوي: الحالة + الأشياء المكتشفة + الإعدادات
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(
                                    if (!trackingOk) "حرّك الهاتف ببطء لاكتشاف الأسطح..."
                                    else "المس سطحاً لوضع شخصية ($count)"
                                )
                                if (labels.isNotEmpty())
                                    Text("👁 ${labels.joinToString("، ")}", style = MaterialTheme.typography.bodySmall)
                                Text(CREDIT, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Button(onClick = { showSettings = true }) { Text("⚙") }
                    }

                    // الشريط السفلي: اختيار الشخصية + الرد + المحادثة
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (thinking || reply.isNotBlank()) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                            ) {
                                Text(
                                    if (thinking) "..." else reply,
                                    modifier = Modifier.padding(12.dp).fillMaxWidth()
                                )
                            }
                        }

                        if (characters.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(characters) { c ->
                                    if (c == selected) Button(onClick = { selected = c }) { Text(c.name) }
                                    else OutlinedButton(
                                        onClick = { selected = c },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                        )
                                    ) { Text(c.name) }
                                }
                            }
                        }

                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                        ) {
                            Row(
                                Modifier.padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = {
                                        Text(activeName?.let { "تحدث مع $it..." } ?: "ضع شخصية أولاً")
                                    },
                                    singleLine = true
                                )
                                TextButton(onClick = { startVoiceInput() }) { Text("🎤") }
                                Button(onClick = {
                                    val t = input.trim()
                                    if (t.isNotEmpty()) { input = ""; sendUserMessage(t) }
                                }) { Text("إرسال") }
                            }
                        }

                        if (count > 0) {
                            OutlinedButton(
                                onClick = {
                                    childNodes.clear(); nodeNames.clear()
                                    count = 0; activeName = null; reply = ""
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                )
                            ) { Text("مسح كل الشخصيات") }
                        }
                    }

                    if (showSettings) SettingsDialog()
                }
              }
            }
        }
    }

    @Composable
    private fun SettingsDialog() {
        var key by remember { mutableStateOf(prefs.getString("key", "").orEmpty()) }
        var auto by remember { mutableStateOf(prefs.getBoolean("auto", true)) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("الإعدادات") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("مفتاح Claude API") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("تعليق الشخصية تلقائياً على ما تراه الكاميرا", Modifier.weight(1f))
                        Switch(checked = auto, onCheckedChange = { auto = it })
                    }
                    Text(CREDIT, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("key", key.trim()).putBoolean("auto", auto).apply()
                    showSettings = false
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("إلغاء") } }
        )
    }

    override fun onDestroy() {
        tts?.shutdown()
        analyzer.close()
        translator.close()
        super.onDestroy()
    }
}