package com.example.balebot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class BaleService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
    private var lastUpdateId = 0L
    private var isRunning = true
    private var flashState = false

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        scope.launch { pollUpdates() }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "balebot_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "BaleBot Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("BaleBot is active")
            .setContentText("Listening for commands...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private suspend fun pollUpdates() {
        while (isRunning) {
            try {
                val url = "${Config.BASE_URL}/getUpdates?offset=${lastUpdateId + 1}&timeout=30"
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@use
                    val json = JSONObject(body)
                    if (!json.getBoolean("ok")) return@use
                    val result = json.getJSONArray("result")
                    for (i in 0 until result.length()) {
                        val update = result.getJSONObject(i)
                        lastUpdateId = update.getLong("update_id")
                        handleUpdate(update)
                    }
                }
            } catch (e: Exception) {
                delay(3000)
            }
        }
    }

    private fun handleUpdate(update: JSONObject) {
        try {
            val message = update.optJSONObject("message") ?: return
            val chat = message.getJSONObject("chat")
            val chatId = chat.getLong("id").toString()
            if (chatId != Config.CHAT_ID) return
            val text = message.optString("text", "").trim()
            if (text.isEmpty()) return
            processCommand(text)
        } catch (_: Exception) {}
    }

    private fun processCommand(cmd: String) {
        val parts = cmd.split(" ")
        val command = parts[0].lowercase()
        when (command) {
            "/start" -> sendMessage("BaleBot is active! Type /help")
            "/help" -> sendMessage(HELP_TEXT)
            "/ring" -> ringPhone()
            "/vibrate" -> vibratePhone()
            "/silent" -> setSilent(true)
            "/maxvolume" -> setMaxVolume()
            "/mute" -> setSilent(true)
            "/battery" -> sendBattery()
            "/info" -> sendInfo()
            "/wifi" -> sendWifiInfo()
            "/location" -> sendLocation()
            "/flash" -> toggleFlash()
            "/lock" -> lockScreen()
            "/apps" -> listApps()
            "/find" -> findPhone()
            "/status" -> sendMessage("Service is running")
            "/stop" -> {
                sendMessage("Service stopped")
                isRunning = false
                stopSelf()
            }
            else -> sendMessage("Unknown command: $command")
        }
    }

    private fun ringPhone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val player = MediaPlayer.create(this, uri)
            player.isLooping = false
            player.start()
            sendMessage("Phone is ringing")
        } catch (e: Exception) {
            sendMessage("Error: ${e.message}")
        }
    }

    private fun vibratePhone() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(2000)
        }
        sendMessage("Vibrating")
    }

    private fun setSilent(silent: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = if (silent) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
        sendMessage(if (silent) "Silent mode on" else "Sound on")
    }

    private fun setMaxVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        sendMessage("Volume maxed")
    }

    private fun sendBattery() {
        val bm = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bm?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        sendMessage("Battery: $pct%")
    }

    private fun sendInfo() {
        val info = """
            Model: ${Build.MODEL}
            Brand: ${Build.BRAND}
            Android: ${Build.VERSION.RELEASE}
            SDK: ${Build.VERSION.SDK_INT}
            Time: ${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())}
        """.trimIndent()
        sendMessage(info)
    }

    private fun sendWifiInfo() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val connected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        sendMessage(if (connected) "WiFi connected" else "WiFi not connected")
    }

    private fun sendLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                sendMessage("Location:\nhttps://maps.google.com/?q=${loc.latitude},${loc.longitude}")
            } else {
                sendMessage("Location not found (enable GPS)")
            }
        } catch (e: SecurityException) {
            sendMessage("Location permission denied")
        }
    }

    private fun toggleFlash() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList[0]
            flashState = !flashState
            cm.setTorchMode(cameraId, flashState)
            sendMessage(if (flashState) "Flash ON" else "Flash OFF")
        } catch (e: Exception) {
            sendMessage("Error: ${e.message}")
        }
    }

    private fun lockScreen() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
                dpm.lockNow()
                sendMessage("Screen locked")
            } else {
                sendMessage("Admin access not granted")
            }
        } catch (e: Exception) {
            sendMessage("Error: ${e.message}")
        }
    }

    private fun listApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() }.distinct().take(30)
        sendMessage("Apps:\n" + apps.joinToString("\n- ", prefix = "- "))
    }

    private fun findPhone() {
        sendMessage("Find mode active (5 rings)")
        scope.launch {
            repeat(5) {
                ringPhone()
                delay(8000)
            }
        }
    }

    private fun sendMessage(text: String) {
        scope.launch {
            try {
                val url = "${Config.BASE_URL}/sendMessage"
                val body = FormBody.Builder()
                    .add("chat_id", Config.CHAT_ID)
                    .add("text", text)
                    .build()
                client.newCall(Request.Builder().url(url).post(body).build()).execute().close()
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val HELP_TEXT = """
            Available commands:
            /ring - Ring
            /vibrate - Vibrate
            /silent - Silent
            /battery - Battery
            /info - Device info
            /wifi - WiFi status
            /location - Location
            /lock - Lock screen
            /flash - Flashlight
            /find - Find phone
            /stop - Stop service
        """.trimIndent()
    }
}
