package com.jarvis.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f }
        val phone = EditText(this).apply {
            hint = "Número com DDD (ex: 11999998888)"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val msg = EditText(this).apply {
            hint = "Mensagem"
            setText("Oi vô, tudo bem?")
        }
        val accBtn = Button(this).apply {
            text = "1) Ligar acessibilidade"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val sendBtn = Button(this).apply {
            text = "2) Enviar no WhatsApp"
            setOnClickListener { send(phone.text.toString(), msg.text.toString()) }
        }
        listOf(status, phone, msg, accBtn, sendBtn).forEach { root.addView(it) }
        setContentView(root)
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

    private fun send(rawPhone: String, text: String) {
        var digits = rawPhone.filter { it.isDigit() }
        if (digits.length < 10) {
            Toast.makeText(this, "Digite o número com DDD", Toast.LENGTH_SHORT).show()
            return
        }
        if (digits.length <= 11) digits = "55$digits"
        if (!accessibilityOn()) {
            Toast.makeText(this, "Ligue a acessibilidade primeiro", Toast.LENGTH_LONG).show()
            return
        }
        SenderService.arm()
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(text)}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp.w4b"))
            } catch (e2: ActivityNotFoundException) {
                SenderService.pending = false
                Toast.makeText(this, "WhatsApp não encontrado", Toast.LENGTH_LONG).show()
            }
        }
    }
}
