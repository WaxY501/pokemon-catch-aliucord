package dev.khopdi.pokemoncatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.aliucord.Logger
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.views.Button
import com.aliucord.views.TextInput
import com.aliucord.widgets.BottomSheet
import com.discord.models.message.Message
import com.discord.stores.StoreMessages
import com.discord.widgets.chat.input.ChatInputViewModel
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.exp

@AliucordPlugin
class AliucordCatchPlugin : Plugin() {
    private val log = Logger("PokemonCatch")
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<Long, Prediction>()
    private var classifier: OnnxClassifier? = null
    private var names: NameTables? = null

    data class Prediction(
        val messageId: Long,
        val source: String,
        val idx: Int,
        val conf: Float,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var used: Boolean = false
    )

    init {
        settingsTab = SettingsTab(PluginSettings::class.java, SettingsTab.Type.BOTTOM_SHEET)
    }

    override fun start(context: Context) {
        names = NameTables.load(context)
        executor.execute {
            try {
                classifier = OnnxClassifier(context)
                log.info("ONNX models loaded successfully")
            } catch (t: Throwable) {
                log.error("Failed to load ONNX models", t)
            }
        }

        patcher.patch(
            StoreMessages::class.java.getDeclaredMethod("handleMessageCreate", com.discord.api.message.Message::class.java),
            Hook { param ->
                if (!settings.getBool("enabled", true)) return@Hook
                val rawMsg = param.args[0] as? com.discord.api.message.Message ?: return@Hook
                val msg = Message(rawMsg)
                if (msg.author?.id != POKETWO_ID) return@Hook

                val embeds = msg.embeds ?: return@Hook
                if (embeds.isEmpty()) return@Hook
                val firstEmbed = embeds[0]
                val title = firstEmbed.title?.lowercase() ?: ""
                if (!title.contains("wild pokémon has appeared") && !title.contains("has appeared!")) {
                    return@Hook
                }

                val imageUrl = firstEmbed.image?.url ?: firstEmbed.thumbnail?.url ?: return@Hook
                val channelId = msg.channelId
                val messageId = msg.id

                executor.execute {
                    val clf = classifier ?: return@execute
                    val res = clf.classify(
                        imageUrl = imageUrl,
                        fallbackThreshold = settings.getInt("fallbackThreshold", 60) / 100f,
                        mainAccept = settings.getInt("mainAcceptThreshold", 70) / 100f,
                        kratosAccept = settings.getInt("kratosAcceptThreshold", 80) / 100f
                    )
                    if (res != null) {
                        cache[channelId] = Prediction(messageId, res.source, res.idx, res.conf)
                        log.info("Predicted idx=${res.idx} (${res.source}, ${(res.conf * 100).toInt()}%)")
                    }
                }
            }
        )

        patcher.patch(
            ChatInputViewModel::class.java.getDeclaredMethod(
                "sendMessage",
                Context::class.java,
                com.discord.utilities.textprocessing.MessageRenderContext::class.java,
                String::class.java,
                List::class.java,
                Boolean::class.javaPrimitiveType,
                kotlin.jvm.functions.Function1::class.java
            ),
            Hook { param ->
                if (!settings.getBool("enabled", true)) return@Hook
                val vm = param.thisObject as? ChatInputViewModel ?: return@Hook
                val content = param.args[2] as? String ?: return@Hook
                val channelId = getSelectedChannelId(vm) ?: return@Hook

                val pred = cache[channelId] ?: return@Hook
                if (pred.used || System.currentTimeMillis() - pred.createdAt > 600_000L) return@Hook

                val template = settings.getString("template", "<@716390085896962058> c {pokemon}")
                val triggerMode = settings.getString("triggerMode", "any")
                val trigger = settings.getString("trigger", "c")
                if (!matchesTrigger(content, template, triggerMode, trigger)) return@Hook

                val tbl = names ?: return@Hook
                val naming = settings.getString("naming", "english")
                val pokemon = tbl.resolve(pred.source, pred.idx, naming) ?: return@Hook

                param.args[2] = template.replace("{pokemon}", pokemon)
                pred.used = true
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        cache.clear()
        classifier?.close()
        classifier = null
    }

    private fun getSelectedChannelId(vm: ChatInputViewModel): Long? {
        return try {
            com.discord.stores.StoreStream.getChannelsSelected().id
        } catch (_: Throwable) {
            null
        }
    }

    private fun matchesTrigger(content: String, template: String, mode: String, trigger: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        val prefix = template.substringBefore("{pokemon}").trim()
        if (prefix.isNotEmpty() && trimmed.startsWith(prefix)) return false
        if (mode == "any") return true
        return trigger.isNotBlank() && trimmed.equals(trigger.trim(), ignoreCase = true)
    }

    companion object {
        const val POKETWO_ID = 716390085896962058L
    }
}

class PluginSettings : BottomSheet() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val settings = com.aliucord.PluginManager.plugins["AutoPokemonCatchClassifier"]?.settings ?: return

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }

        val title = TextView(ctx).apply {
            text = "Auto Pokémon Catch Classifier"
            textSize = 18f
        }
        layout.addView(title)

        val templateInput = TextInput(ctx, "Catch command template").apply {
            editText.setText(settings.getString("template", "<@716390085896962058> c {pokemon}"))
        }
        layout.addView(templateInput)

        val namingInput = TextInput(ctx, "Naming (english / alias / japanese)").apply {
            editText.setText(settings.getString("naming", "english"))
        }
        layout.addView(namingInput)

        val triggerModeInput = TextInput(ctx, "Trigger mode (any / specific)").apply {
            editText.setText(settings.getString("triggerMode", "any"))
        }
        layout.addView(triggerModeInput)

        val triggerInput = TextInput(ctx, "Specific trigger text").apply {
            editText.setText(settings.getString("trigger", "c"))
        }
        layout.addView(triggerInput)

        val saveBtn = Button(ctx).apply {
            text = "Save Settings"
            setOnClickListener {
                settings.setString("template", templateInput.editText.text.toString())
                settings.setString("naming", namingInput.editText.text.toString().trim().lowercase())
                settings.setString("triggerMode", triggerModeInput.editText.text.toString().trim().lowercase())
                settings.setString("trigger", triggerInput.editText.text.toString())
                dismiss()
            }
        }
        layout.addView(saveBtn)
        addView(layout)
    }
}

class NameTables(
    private val classes: List<String>,
    private val jpn: List<String>,
    private val kratos: List<String>,
    private val kratosJpn: List<String>,
    private val aliases: JSONObject,
    private val rawNames: Set<String>
) {
    fun resolve(source: String, idx: Int, naming: String): String? {
        val list = if (source == "main") classes else kratos
        val jpnList = if (source == "main") jpn else kratosJpn
        val raw = list.getOrNull(idx) ?: return null
        val eng = (if (raw.contains("Beta")) raw.split(Regex("\\s+")).last() else raw).lowercase().trim()
        return when (naming) {
            "japanese" -> jpnList.getOrNull(idx)?.takeIf { it.isNotBlank() && it != "???" } ?: eng
            "alias" -> {
                val arr = aliases.optJSONArray(eng)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val cand = arr.optString(i).lowercase().trim()
                        if (cand.isNotEmpty() && cand != eng && !rawNames.contains(cand)) return cand
                    }
                }
                eng
            }
            else -> eng
        }
    }

    companion object {
        fun load(context: Context): NameTables {
            fun readLines(asset: String): List<String> =
                context.assets.open("data/$asset").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }

            val classes = readLines("classes.txt")
            val jpn = readLines("jpn_classes.txt")
            val kratos = readLines("kratos_classes.txt")
            val kratosJpn = readLines("kratosjpn_classes.txt")
            val aliasesJson = context.assets.open("data/aliases.json").bufferedReader().readText()
            val rawSet = (classes + kratos).map { it.lowercase() }.toSet()
            return NameTables(classes, jpn, kratos, kratosJpn, JSONObject(aliasesJson), rawSet)
        }
    }
}

class OnnxClassifier(context: Context) {
    data class Result(val source: String, val idx: Int, val conf: Float)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val mainSession: OrtSession
    private val kratosSession: OrtSession?

    init {
        val modelDir = File(context.filesDir, "pokemoncatch_models").apply { mkdirs() }
        val files = listOf("model.onnx", "model.onnx.data", "kratos_model.onnx", "kratos_model.onnx.data")
        for (name in files) {
            val out = File(modelDir, name)
            if (!out.exists()) {
                context.assets.open("models/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        mainSession = env.createSession(File(modelDir, "model.onnx").absolutePath, OrtSession.SessionOptions())
        val kratosFile = File(modelDir, "kratos_model.onnx")
        kratosSession = if (kratosFile.exists()) env.createSession(kratosFile.absolutePath, OrtSession.SessionOptions()) else null
    }

    fun classify(imageUrl: String, fallbackThreshold: Float, mainAccept: Float, kratosAccept: Float): Result? {
        val bytes = download(imageUrl) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = Bitmap.createScaledBitmap(bmp, 224, 224, true)
        val tensor = bitmapToTensor(scaled)

        try {
            val inputName = mainSession.inputNames.first()
            val res = mainSession.run(mapOf(inputName to tensor))
            val mIdx: Int
            val mConf: Float
            try {
                val logits = (res[0].value as Array<FloatArray>)[0]
                val pair = softmaxTop1(logits)
                mIdx = pair.first
                mConf = pair.second
            } finally {
                res.close()
            }

            if (mConf >= fallbackThreshold) {
                return if (mConf >= mainAccept) Result("main", mIdx, mConf) else null
            }

            val kSess = kratosSession
            if (kSess != null) {
                val kName = kSess.inputNames.first()
                val kRes = kSess.run(mapOf(kName to tensor))
                try {
                    val kLogits = (kRes[0].value as Array<FloatArray>)[0]
                    val (kIdx, kConf) = softmaxTop1(kLogits)
                    if (kConf >= kratosAccept && kConf > mConf) {
                        return Result("kratos", kIdx, kConf)
                    }
                } finally {
                    kRes.close()
                }
            }
            return if (mConf >= mainAccept) Result("main", mIdx, mConf) else null
        } finally {
            tensor.close()
        }
    }

    private fun download(urlStr: String): ByteArray? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        return conn.inputStream.use { it.readBytes() }
    }

    private fun bitmapToTensor(bmp: Bitmap): OnnxTensor {
        val w = 224
        val h = 224
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = FloatBuffer.allocate(3 * w * h)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val plane = w * h
        for (i in 0 until plane) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            buf.put(i, (r - mean[0]) / std[0])
            buf.put(plane + i, (g - mean[1]) / std[1])
            buf.put(2 * plane + i, (b - mean[2]) / std[2])
        }
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    private fun softmaxTop1(logits: FloatArray): Pair<Int, Float> {
        var maxVal = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > maxVal) maxVal = v
        var sum = 0.0
        var bestIdx = 0
        var bestExp = 0.0
        for (i in logits.indices) {
            val e = exp((logits[i] - maxVal).toDouble())
            sum += e
            if (i == 0 || e > bestExp) {
                bestExp = e
                bestIdx = i
            }
        }
        return bestIdx to (bestExp / sum).toFloat()
    }

    fun close() {
        try { mainSession.close() } catch (_: Throwable) {}
        try { kratosSession?.close() } catch (_: Throwable) {}
        try { env.close() } catch (_: Throwable) {}
    }
}
