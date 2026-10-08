package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SenderService : AccessibilityService() {

    companion object {
        @Volatile var pending = false
        @Volatile var armedAt = 0L
        fun arm() { armedAt = System.currentTimeMillis(); pending = true }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!pending) return
        if (System.currentTimeMillis() - armedAt > 20_000) { pending = false; return }
        val root = rootInActiveWindow ?: return
        val btn = findSendButton(root) ?: return
        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            pending = false
            // volta para o app depois de enviar
            Handler(Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_BACK)
                performGlobalAction(GLOBAL_ACTION_BACK)
            }, 1500)
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
