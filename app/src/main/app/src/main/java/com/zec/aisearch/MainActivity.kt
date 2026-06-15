package com.zec.aisearch

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val REQ_CODE = 100
    private lateinit var projManager: MediaProjectionManager
    private var apiKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val keyInput = findViewById<EditText>(R.id.et_api_key)
        val saveBtn = findViewById<Button>(R.id.btn_save)
        val startBtn = findViewById<Button>(R.id.btn_start)
        val stopBtn = findViewById<Button>(R.id.btn_stop)

        val prefs = getSharedPreferences("ai_search", MODE_PRIVATE)
        keyInput.setText(prefs.getString("api_key", ""))

        saveBtn.setOnClickListener {
            apiKey = keyInput.text.toString().trim()
            prefs.edit().putString("api_key", apiKey).apply()
            Toast.makeText(this, "API Key 已保存", Toast.LENGTH_SHORT).show()
        }

        startBtn.setOnClickListener {
            apiKey = prefs.getString("api_key", "") ?: ""
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "请先输入并保存 API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = projManager.createScreenCaptureIntent()
            startActivityForResult(intent, REQ_CODE)
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, FloatService::class.java))
            Toast.makeText(this, "悬浮球已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val prefs = getSharedPreferences("ai_search", MODE_PRIVATE)
            val key = prefs.getString("api_key", "") ?: ""
            val intent = Intent(this, FloatService::class.java).apply {
                putExtra("result_code", resultCode)
                putExtra("data", data)
                putExtra("api_key", key)
            }
            startForegroundService(intent)
            Toast.makeText(this, "悬浮球已启动！", Toast.LENGTH_SHORT).show()
        }
    }
}
