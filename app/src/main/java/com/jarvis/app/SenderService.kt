package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SenderService : AccessibilityService() {

    companion object {
        // 0 = nada, 1 = enviar mensagem, 2 = chamada de voz, 3 = chamada de vídeo
        @Volatile var mode = 0
        @Volatile var armedAt = 0L
        @Volatile var lastClick = 0L
        @Volatile var lastDump = ""
        fun arm() { armedAt = System.currentTimeMillis(); mode = 1 }
        fun armCall(video: Boolean) {
            armedAt = System.currentTimeMillis(); lastClick = 0L
            lastDump = ""; mode = if (video) 3 else 2
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (mode == 0) return
        if (System.currentTimeMillis() - armedAt > 25_000) { mode = 0; return }
        val root = rootInActiveWindow ?: return
        if (mode == 1) handleSend(root) else handleCall(root)
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), out)
    }

    private fun label(n: AccessibilityNodeInfo): String =
        ((n.contentDescription?.toString() ?: "") + " | " + (n.text?.toString() ?: "")).trim()

    private fun handleSend(root: AccessibilityNodeInfo) {
        val btn = findSendButton(root) ?: return
        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            mode = 0
            Handler(Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_BACK)
            }, 1500)
        }
    }

    private fun handleCall(root: AccessibilityNodeInfo) {
        val all = mutableListOf<AccessibilityNodeInfo>()
        walk(root, all)

        // guarda tudo que está na tela para o comando "depurar"
        lastDump = all.map { label(it) }.filter { it.length > 3 }.distinct().take(40).joinToString("\n")

        // 1) confirmação "Ligar" (botão com texto exato)
        val confirm = all.firstOrNull {
            it.isClickable && (it.text?.toString()?.trim() ?: "").lowercase() in listOf("ligar", "chamar", "call")
        }
        if (confirm != null && confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            mode = 0
            return
        }

        // 2) ícone de chamada na barra da conversa (no máximo a cada 1,5 s)
        if (System.currentTimeMillis() - lastClick < 1500) return
        val want = if (mode == 3) listOf("chamada de vídeo", "chamada de video", "videochamada", "video call", "vídeo", "video")
        else listOf("chamada de voz", "voice call", "ligar", "ligação", "ligacao")
        val bad = listOf("mensagem", "message", "gravar", "record", "áudio", "audio")

        for (n in all) {
            val d = (n.contentDescription?.toString() ?: "").lowercase()
            if (d.isEmpty() || bad.any { d.contains(it) }) continue
            if (want.none { d.contains(it) }) continue
            var cur: AccessibilityNodeInfo? = n
            while (cur != null && !cur.isClickable) cur = cur.parent
            if (cur != null && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                lastClick = System.currentTimeMillis()
                return
            }
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        }
        for (label in listOf("Enviar", "Send")) {
            root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.isClickable }?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {}
}
