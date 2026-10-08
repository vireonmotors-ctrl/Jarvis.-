package com.jarvis.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private data class Contact(val name: String, val ddd: String, val number: String)
    private enum class Step { NONE, NAME, DDD, NUMBER }

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    private var step = Step.NONE
    private var tmpName = ""
    private var tmpDdd = ""
    private val history = JSONArray()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakOn = true

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                val vs = ptVoices()
                val idx = prefs.getInt("voiceIdx", -1)
                val chosen = if (idx in vs.indices) vs[idx]
                else vs.firstOrNull {
                    val n = it.name.lowercase()
                    n.contains("ptd") || (n.contains("male") && !n.contains("female"))
                } ?: vs.firstOrNull()
                chosen?.let { tts?.voice = it }
                tts?.setPitch(0.85f)
                ttsReady = true
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(36), dp(12), dp(8))
        }
        status = TextView(this).apply { textSize = 12f }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val accBtn = Button(this).apply {
            text = "Acessibilidade"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val keyBtn = Button(this).apply {
            text = "Chave da IA"
            setOnClickListener { askKey() }
        }
        top.addView(accBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(keyBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(chat) }

        input = EditText(this).apply {
            hint = "Fale com o Jarvis"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        val sendBtn = Button(this).apply {
            text = "Enviar"
            setOnClickListener { submit() }
        }
        val micBtn = Button(this).apply {
            text = "🎤"
            setOnClickListener { listen() }
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(micBtn)
        bottom.addView(sendBtn)

        root.addView(status)
        root.addView(top)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom)
        setContentView(root)

        reply("Oi! Eu sou o Jarvis. Toque no 🎤 ou escreva: novo contato, mandar para NOME: texto, ligar para NOME, alarme para 7:30, que horas são.", false)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (accessibilityOn()) "Acessibilidade: LIGADA ✅" else "Acessibilidade: DESLIGADA ❌"
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains("$packageName/")
    }

    // ---------- contatos ----------
    private fun loadContacts(): MutableList<Contact> {
        val out = mutableListOf<Contact>()
        val arr = JSONArray(prefs.getString("contacts", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Contact(o.getString("n"), o.getString("d"), o.getString("t")))
        }
        return out
    }

    private fun saveContacts(list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("d", it.ddd).put("t", it.number)) }
        prefs.edit().putString("contacts", arr.toString()).apply()
    }

    private fun findContact(name: String): Contact? {
        val list = loadContacts()
        val n = name.trim()
        return list.firstOrNull { it.name.equals(n, true) }
            ?: list.firstOrNull { it.name.contains(n, true) }
    }

    // ---------- chat ----------
    private fun addMsg(text: String, me: Boolean) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFF111111.toInt())
            setBackgroundColor(if (me) 0xFFDCF8C6.toInt() else 0xFFE8EAF0.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (me) Gravity.END else Gravity.START
            setMargins(0, dp(6), 0, 0)
        }
        chat.addView(tv, lp)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun reply(text: String, voice: Boolean = true) {
        addMsg(text, false)
        if (voice) say(text)
    }

    private fun say(text: String) {
        if (!speakOn || !ttsReady) return
        val clean = text.replace(Regex("[^\\p{L}\\p{N}\\s.,!?:;()'\"-]"), " ")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun ptVoices() = (tts?.voices ?: emptySet())
        .filter { it.locale.language == "pt" && it.locale.country == "BR" }
        .sortedBy { it.name }

    private fun changeVoice() {
        val vs = ptVoices()
        if (vs.isEmpty()) { reply("Não encontrei vozes em português neste celular."); return }
        val next = (prefs.getInt("voiceIdx", -1) + 1) % vs.size
        prefs.edit().putInt("voiceIdx", next).apply()
        tts?.voice = vs[next]
        reply("Voz ${next + 1} de ${vs.size}. Se não for de homem, escreva: trocar voz")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        handle(t)
    }

    private val sendRegex = Regex(
        "^(?:enviar|envie|mandar|manda|mande|envia)\\s+(?:uma\\s+)?(?:mensagem\\s+)?(?:para|pra|pro)\\s+(?:o\\s+|a\\s+)?(.+?)(?:\\s*[:,]|\\s+dizendo|\\s+falando|\\s+que)\\s*(.+)$",
        RegexOption.IGNORE_CASE
    )

    private val callRegex = Regex(
        "^(?:ligar|liga|ligue|chamar|chame|chama|telefonar)\\s+(?:para|pra|pro)\\s+(?:o\\s+|a\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val videoRegex = Regex(
        "^(?:v[ií]deo\\s*chamada|chamada\\s+de\\s+v[ií]deo)\\s+(?:para|pra|pro)\\s+(?:o\\s+|a\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    private fun handle(text: String) {
        addMsg(text, true)
        when (step) {
            Step.NAME -> {
                tmpName = text.trim(); step = Step.DDD
                reply("Qual o DDD de $tmpName? (só os 2 números)"); return
            }
            Step.DDD -> {
                val d = text.filter { it.isDigit() }
                if (d.length != 2) { reply("O DDD tem 2 números. Qual é?"); return }
                tmpDdd = d; step = Step.NUMBER
                reply("Qual o número do WhatsApp, sem o DDD?"); return
            }
            Step.NUMBER -> {
                val n = text.filter { it.isDigit() }
                if (n.length !in 8..9) { reply("O número tem 8 ou 9 dígitos, sem o DDD. Qual é?"); return }
                val list = loadContacts()
                list.removeAll { it.name.equals(tmpName, true) }
                list.add(Contact(tmpName, tmpDdd, n))
                saveContacts(list)
                step = Step.NONE
                reply("Salvei $tmpName: ($tmpDdd) $n ✅"); return
            }
            Step.NONE -> {}
        }

        val low = text.lowercase().trim()
        val m = sendRegex.find(text.trim())
        val videoM = videoRegex.find(text.trim())
        val callM = callRegex.find(text.trim())
        when {
            low.contains("novo contato") || low.contains("salvar contato") ||
                low.contains("salvar número") || low.contains("salvar numero") ||
                low.contains("adicionar contato") -> {
                step = Step.NAME
                reply("Qual o nome do contato?")
            }
            low == "contatos" || low.contains("meus contatos") || low.contains("lista de contatos") -> {
                val l = loadContacts()
                reply(if (l.isEmpty()) "Você ainda não salvou nenhum contato. Escreva: novo contato"
                else l.joinToString("\n") { "• ${it.name}: (${it.ddd}) ${it.number}" })
            }
            low.startsWith("apagar ") || low.startsWith("excluir ") -> {
                val name = text.trim().substringAfter(" ").trim()
                val list = loadContacts()
                if (list.removeAll { it.name.equals(name, true) }) {
                    saveContacts(list); reply("Apaguei $name.")
                } else reply("Não achei $name nos contatos.")
            }
            low == "depurar" -> {
                reply(if (SenderService.lastDump.isBlank()) "Nada capturado ainda. Peça uma ligação primeiro."
                else "Vi isto na tela do WhatsApp:\n" + SenderService.lastDump, false)
            }
            videoM != null -> {
                val c = findContact(videoM.groupValues[1])
                if (c == null) reply("Não achei \"${videoM.groupValues[1]}\" nos contatos. Escreva: novo contato")
                else callWhatsApp(c, true)
            }
            callM != null -> {
                val c = findContact(callM.groupValues[1])
                if (c == null) reply("Não achei \"${callM.groupValues[1]}\" nos contatos. Escreva: novo contato")
                else callWhatsApp(c, false)
            }
            m != null -> {
                val c = findContact(m.groupValues[1])
                if (c == null) reply("Não achei \"${m.groupValues[1]}\" nos contatos. Escreva: novo contato")
                else sendWhatsApp(c, m.groupValues[2].trim())
            }
            low == "silêncio" || low == "silencio" || low == "calar" || low == "desligar voz" -> {
                speakOn = false
                tts?.stop()
                addMsg("Voz desligada. Escreva \"ligar voz\" para voltar.", false)
            }
            low == "ligar voz" -> { speakOn = true; reply("Voz ligada.") }
            low == "trocar voz" -> changeVoice()
            low.contains("que horas são") || low.contains("que horas sao") ||
                low.contains("hora certa") || low == "horas" || low.startsWith("que horas") ->
                reply("Agora são ${SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())}.")
            low.contains("que dia é hoje") || low.contains("que dia e hoje") || low.contains("data de hoje") ->
                reply("Hoje é ${SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", Locale("pt", "BR")).format(Date())}.")
            low.contains("timer") || low.contains("temporizador") || low.contains("daqui a") -> setTimer(low)
            low.contains("alarme") || low.contains("despertador") ||
                low.contains("me acorde") || low.contains("me acorda") -> setAlarm(low)
            else -> askAi(text)
        }
    }

    // ---------- WhatsApp ----------
    private fun sendWhatsApp(c: Contact, text: String) {
        if (!accessibilityOn()) {
            reply("Ligue a acessibilidade primeiro (botão no topo)."); return
        }
        SenderService.arm()
        val uri = Uri.parse("https://wa.me/55${c.ddd}${c.number}?text=${Uri.encode(text)}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp.w4b"))
            } catch (e2: ActivityNotFoundException) {
                SenderService.mode = 0
                reply("WhatsApp não encontrado."); return
            }
        }
        reply("Enviando para ${c.name}...")
    }

    private fun callWhatsApp(c: Contact, video: Boolean) {
        if (!accessibilityOn()) {
            reply("Ligue a acessibilidade primeiro (botão no topo)."); return
        }
        SenderService.armCall(video)
        val uri = Uri.parse("https://wa.me/55${c.ddd}${c.number}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp.w4b"))
            } catch (e2: ActivityNotFoundException) {
                SenderService.mode = 0
                reply("WhatsApp não encontrado."); return
            }
        }
        reply(if (video) "Chamando ${c.name} por vídeo..." else "Ligando para ${c.name}...")
    }

    // ---------- voz, alarme e timer ----------
    private fun listen() {
        tts?.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Pode falar")
        }
        try {
            startActivityForResult(i, 77)
        } catch (e: Exception) {
            reply("Este celular não tem o reconhecimento de voz do Google. Use o microfone do teclado.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!t.isNullOrBlank()) handle(t)
        }
    }

    private fun setAlarm(low: String) {
        val m = Regex("(\\d{1,2})(?:\\s*(?::|h|horas?\\s*e?|\\s+e)\\s*(\\d{1,2}))?").find(low)
        if (m == null) { reply("Diga a hora. Exemplo: alarme para 7:30"); return }
        var h = m.groupValues[1].toInt()
        var min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (low.contains("e meia")) min = 30
        if ((low.contains("da tarde") || low.contains("da noite")) && h < 12) h += 12
        if (h > 23 || min > 59) { reply("Essa hora não existe. Exemplo: alarme para 7:30"); return }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, min)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        try {
            startActivity(i)
            reply("Alarme marcado para %02d:%02d ⏰ (para apagar, use o app Relógio)".format(h, min))
        } catch (e: Exception) {
            reply("Não consegui criar o alarme: ${e.message}")
        }
    }

    private fun setTimer(low: String) {
        val m = Regex("(\\d+)\\s*(horas?|h|minutos?|min|segundos?|seg)").find(low)
        if (m == null) { reply("Diga o tempo. Exemplo: timer de 10 minutos"); return }
        val n = m.groupValues[1].toInt()
        val unit = m.groupValues[2]
        val secs = when {
            unit.startsWith("h") -> n * 3600
            unit.startsWith("m") -> n * 60
            else -> n
        }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        try {
            startActivity(i)
            reply("Timer de $n ${m.groupValues[2]} iniciado ⏱️")
        } catch (e: Exception) {
            reply("Não consegui criar o timer: ${e.message}")
        }
    }

    // ---------- IA ----------
    private fun askKey() {
        val et = EditText(this).apply {
            hint = "sk-ant-..."
            setText(prefs.getString("key", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Chave da API Anthropic")
            .setView(et)
            .setPositiveButton("Salvar") { _, _ ->
                prefs.edit().putString("key", et.text.toString().trim()).apply()
                reply("Chave salva ✅")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun askAi(text: String) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) {
            reply("Para eu conversar, toque em \"Chave da IA\" e cole sua chave da Anthropic.")
            return
        }
        history.put(JSONObject().put("role", "user").put("content", text))
        while (history.length() > 12) history.remove(0)
        while (history.length() > 0 && history.getJSONObject(0).getString("role") != "user") history.remove(0)

        val names = loadContacts().joinToString(", ") { it.name }
        val system = "Você é o Jarvis, um assistente pessoal em português do Brasil, simpático e direto. " +
            "Responda curto, para caber na tela do celular. " +
            "Contatos salvos do usuário: ${if (names.isBlank()) "nenhum" else names}. " +
            "Se ele quiser mandar WhatsApp, diga para escrever: mandar para NOME: texto. " +
            "Se quiser salvar um contato, diga para escrever: novo contato. Para ligar, diga para escrever: ligar para NOME. Para alarme, diga: alarme para 7:30. Para saber as horas, diga: que horas são."
        status.text = "Jarvis está pensando..."
        thread {
            try {
                val ans = callApi(key, system)
                history.put(JSONObject().put("role", "assistant").put("content", ans))
                runOnUiThread { onResume(); reply(ans) }
            } catch (e: Exception) {
                history.remove(history.length() - 1)
                runOnUiThread { onResume(); reply("Não consegui responder: ${e.message}") }
            }
        }
    }

    private fun callApi(key: String, system: String): String {
        val body = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 600)
            .put("system", system)
            .put("messages", history)
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val resp = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val msg = try { JSONObject(resp).getJSONObject("error").getString("message") }
            catch (e: Exception) { resp.take(150) }
            throw Exception("Erro $code: $msg")
        }
        val arr = JSONObject(resp).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "text") sb.append(o.getString("text"))
        }
        return sb.toString()
    }
}
        