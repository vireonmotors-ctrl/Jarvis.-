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
        fun arm() { armedAt = System.currentTimeMillis(); mode = 1 }
        fun armCall(video: Boolean) { armedAt = System.currentTimeMillis(); mode = if (video) 3 else 2 }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (mode == 0) return
        if (System.currentTimeMillis() - armedAt > 25_000) { mode = 0; return }
        val root = rootInActiveWindow ?: return
        if (mode == 1) handleSend(root) else handleCall(root)
    }

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
        // 1) se apareceu a confirmação "Ligar", toca nela e termina
        val confirm = findClickableByExactText(root, listOf("Ligar", "Chamar", "Call"))
        if (confirm != null && confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            mode = 0
            return
        }
        // 2) senão, toca no ícone de chamada no topo da conversa (no máximo a cada 2 s)
        if (System.currentTimeMillis() - lastClick < 2000) return
        val labels = if (mode == 3) listOf("chamada de vídeo", "chamada de video", "video call")
        else listOf("chamada de voz", "voice call")
        val btn = findByDescription(root, labels) ?: return
        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) lastClick = System.currentTimeMillis()
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

    private fun findClickableByExactText(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            root.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.isClickable && it.text?.toString()?.trim().equals(label, true)
            }?.let { return it }
        }
        return null
    }

    private fun findByDescription(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            for (n in root.findAccessibilityNodeInfosByText(label)) {
                var cur: AccessibilityNodeInfo? = n
                while (cur != null && !cur.isClickable) cur = cur.parent
                if (cur != null) return cur
            }
        }
        return null
    }

    override fun onInterrupt() {}
}
