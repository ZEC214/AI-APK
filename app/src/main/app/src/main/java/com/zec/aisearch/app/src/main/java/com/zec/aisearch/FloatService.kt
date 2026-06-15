package com.zec.aisearch

import android.app.*
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class FloatService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var floatView: View
    private var mediaProjection: MediaProjection? = null
    private var apiKey = ""
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        apiKey = intent?.getStringExtra("api_key") ?: ""
        val resultCode = intent?.getIntExtra("result_code", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != -1 && data != null) {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, data)
        }

        setupFloatBall()
        return START_NOT_STICKY
    }

    private fun setupFloatBall() {
        floatView = LayoutInflater.from(this).inflate(R.layout.float_ball, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100; y = 300
        }

        var lastX = 0f; var lastY = 0f
        var moved = false

        floatView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX; lastY = event.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX; val dy = event.rawY - lastY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        params.x += dx.toInt(); params.y += dy.toInt()
                        wm.updateViewLayout(floatView, params)
                        lastX = event.rawX; lastY = event.rawY; moved = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) doScreenshot()
                    true
                }
                else -> false
            }
        }

        wm.addView(floatView, params)
    }

    private fun doScreenshot() {
        showToast("截图中...")
        val dm = resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)
        mediaProjection?.createVirtualDisplay(
            "capture", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        handler.postDelayed({
            val image = reader.acquireLatestImage()
            if (image == null) { showToast("截图失败，请重试"); return@postDelayed }
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * w
            val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            image.close()
            reader.close()

            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            sendToAI(b64)
        }, 500)
    }

    private fun sendToAI(b64: String) {
        showToast("AI 解题中...")
        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 1024)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", b64)
                        })
                    })
                    .put(JSONObject().apply {
                        put("type", "text")
                        put("text", "这是一道题目截图，请给出答案和简要解析。格式：【答案】xxx\n【解析】xxx")
                    })
                )
            }))
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                handler.post { showToast("网络错误: ${e.message}") }
            }
            override fun onResponse(call: Call, response: Response) {
                val text = try {
                    val json = JSONObject(response.body?.string() ?: "")
                    json.getJSONArray("content").getJSONObject(0).getString("text")
                } catch (e: Exception) { "解析失败" }
                handler.post { showResult(text) }
            }
        })
    }

    private fun showResult(text: String) {
        val resultView = LayoutInflater.from(this).inflate(R.layout.result_window, null)
        val tv = resultView.findViewById<TextView>(R.id.tv_result)
        val closeBtn = resultView.findViewById<Button>(R.id.btn_close)
        tv.text = text

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        wm.addView(resultView, params)
        closeBtn.setOnClickListener { wm.removeView(resultView) }
    }

    private fun showToast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun buildNotification(): Notification {
        val channelId = "ai_search"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "AI搜题", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AI搜题运行中")
            .setContentText("点击悬浮球截图搜题")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatView.isInitialized) wm.removeView(floatView)
        mediaProjection?.stop()
    }
}
