package com.klip.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        fun btn(text: String, action: () -> Unit) {
            layout.addView(Button(this).apply {
                this.text = text
                setOnClickListener { action() }
            })
        }
        btn("1) Erişilebilirliği aç (F8 için)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val prefs = getSharedPreferences("klip", MODE_PRIVATE)
        val names = RecorderService.PRESETS.map { it.name }
        val qBtn = Button(this)
        fun setQ() { qBtn.text = "Kalite: " + names[prefs.getInt("q", 2)] + " (değiştir)" }
        qBtn.setOnClickListener {
            prefs.edit().putInt("q", (prefs.getInt("q", 2) + 1) % names.size).apply()
            setQ()
            Toast.makeText(this, "Kaydı durdurup yeniden başlatınca geçerli olur", Toast.LENGTH_SHORT).show()
        }
        setQ()
        layout.addView(qBtn)
        btn("2) Kaydı başlat") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Önce mikrofon iznini ver, sonra tekrar bas", Toast.LENGTH_LONG).show()
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            } else {
                val m = getSystemService(MediaProjectionManager::class.java)
                startActivityForResult(m.createScreenCaptureIntent(), 100)
            }
        }
        btn("Klip al (test)") { RecorderService.instance?.saveClip() }
        btn("Durdur") {
            startService(Intent(this, RecorderService::class.java).setAction("STOP"))
        }
        setContentView(layout)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, RecorderService::class.java)
                    .setAction("START")
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
            )
        }
    }
}
