package com.example.photomapviewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.InputType
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

class MainActivity : AppCompatActivity(), SensorEventListener {

    data class PhotoItem(
        val uri: Uri,
        val name: String,
        val lastModified: Long,
        var lat: Double? = null,
        var lon: Double? = null,
        var direction: Double? = null,
        var isLoaded: Boolean = false,
        var debugStatus: String = ""
    )

    companion object {
        private const val TILE_MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000L
    }

    private val photoList = mutableListOf<PhotoItem>()
    private var currentIndex = 0
    private var isMapReady = false
    private var backgroundLoadJob: Job? = null
    private var fullScreenDecodeJob: Job? = null
    private var cameraInfoResetJob: Job? = null
    private var currentFolderUri: Uri? = null

    // 設定値
    private var isSortDesc = true
    private var pinLimitCount = 50
    private var cacheLimitMb = 100
    private var startupMode = 0
    private var isMapOnTop = false

    // モード管理
    private var isCameraMode = false
    
    // ★ 分離された2つの状態
    private var isGpsPowerOn = false    // GPS自体の電源 (トップボタン)
    private var isMapAutoPan = false    // 地図の自動追従 (地図上の◎ボタン)

    // センサー・位置情報
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private val lastAccelerometer = FloatArray(3)
    private val lastMagnetometer = FloatArray(3)
    private var lastAccelerometerSet = false
    private var lastMagnetometerSet = false
    private var currentAzimuth = 0.0
    private var currentDeclination = 0.0f
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var locationManager: LocationManager? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var gnssSatelliteCount = 0
    private var gpsAccuracy = -1.0f

    // CameraX
    private var imageCapture: ImageCapture? = null
    private lateinit var viewFinder: PreviewView

    // UI部品
    private lateinit var mainLayout: LinearLayout
    private lateinit var topSection: LinearLayout
    private lateinit var thumbContainer: FrameLayout
    private lateinit var ivThumbnail: ImageView
    private lateinit var tvImageInfo: TextView
    private lateinit var tvBgLoadStatus: TextView
    private lateinit var mapContainer: FrameLayout
    private lateinit var mapWebView: WebView
    private lateinit var tvGnssHud: TextView
    
    private lateinit var btnGpsToggle: Button
    
    private lateinit var btnNewer: Button
    private lateinit var btnOlder: Button
    private lateinit var btnGsi: Button
    private lateinit var btnGoogleMap: Button
    private lateinit var btnSwap: Button
    
    private lateinit var btmRowNormal: LinearLayout
    private lateinit var btmRowCamera: LinearLayout
    private lateinit var btnCancelCamera: Button
    private lateinit var btnShutter: Button

    private lateinit var fullContainer: FrameLayout
    private lateinit var fullImageView: ImageView
    private lateinit var gestureDetectorFull: GestureDetector
    private lateinit var gestureDetectorThumb: GestureDetector

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private val memoryCache: LruCache<String, Bitmap> by lazy {
        val maxMem = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        val cacheSize = maxOf(1024, maxMem / 8)
        object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions -> 
        val allGranted = permissions.entries.all { it.value == true }
        if (allGranted) {
            checkAutoResume()
            if (getSharedPreferences("app_settings", Context.MODE_PRIVATE).getBoolean("gps_enabled", false)) {
                toggleGpsPower(true)
            }
        } else {
            Toast.makeText(this, "権限が不足しています", Toast.LENGTH_LONG).show()
        }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().putString("last_folder_uri", uri.toString()).apply()
            loadPhotosUltraFast(uri, null)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadSettings()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val rootContainer = FrameLayout(this).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp()) }

        val btnSettings = Button(this).apply { text = "≡"; textSize = 18f; setBackgroundColor(Color.parseColor("#383838")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(48.dp(), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 4.dp() }; setOnClickListener { showMainMenu() } }
        topRow.addView(btnSettings)

        val btnTopPhoto = Button(this).apply { text = "◀"; textSize = 12f; setBackgroundColor(Color.parseColor("#383838")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(40.dp(), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 4.dp() }; setOnClickListener { if (photoList.isNotEmpty() && !isCameraMode) navigateTo(0) } }
        topRow.addView(btnTopPhoto)

        val btnCamera = Button(this).apply { text = "📷"; textSize = 14f; setBackgroundColor(Color.parseColor("#0066FF")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(48.dp(), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 4.dp() }; setOnClickListener { toggleCameraMode() } }
        topRow.addView(btnCamera)

        val btnSelectFolder = Button(this).apply { text = "📁 選択"; textSize = 11f; setBackgroundColor(Color.parseColor("#2D2D2D")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = 4.dp() }; setOnClickListener { if (!isCameraMode) folderPicker.launch(null) } }
        topRow.addView(btnSelectFolder)

        btnGpsToggle = Button(this).apply {
            text = "📡 OFF"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#383838"))
            setTextColor(Color.parseColor("#BBBBBB"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f)
            setOnClickListener { toggleGpsPower(!isGpsPowerOn) }
        }
        topRow.addView(btnGpsToggle)
        mainLayout.addView(topRow)

        topSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 210.dp()).apply { topMargin = 6.dp() } }
        setupThumbGestureDetector()
        thumbContainer = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); setBackgroundColor(Color.BLACK); isClickable = true; isFocusable = true; setOnTouchListener { _, event -> gestureDetectorThumb.onTouchEvent(event); true } }
        viewFinder = PreviewView(this).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); visibility = View.GONE }
        thumbContainer.addView(viewFinder)
        ivThumbnail = ImageView(this).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); scaleType = ImageView.ScaleType.FIT_CENTER }
        thumbContainer.addView(ivThumbnail)
        tvImageInfo = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f; setBackgroundColor(Color.parseColor("#99000000")); setPadding(6.dp(), 4.dp(), 6.dp(), 4.dp()); layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(6.dp(), 6.dp(), 6.dp(), 6.dp()) } }
        thumbContainer.addView(tvImageInfo)
        tvBgLoadStatus = TextView(this).apply { setTextColor(Color.parseColor("#BBBBBB")); textSize = 9f; setBackgroundColor(Color.parseColor("#66000000")); setPadding(4.dp(), 2.dp(), 4.dp(), 2.dp()); layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(6.dp(), 6.dp(), 6.dp(), 6.dp()) }; visibility = View.GONE }
        thumbContainer.addView(tvBgLoadStatus)
        topSection.addView(thumbContainer)
        mainLayout.addView(topSection)

        val midRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dp()).apply { topMargin = 6.dp() } }
        btnSwap = Button(this).apply { text = "⇅"; textSize = 16f; setBackgroundColor(Color.parseColor("#383838")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(44.dp(), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 4.dp() }; setOnClickListener { toggleViewOrder() } }
        midRow.addView(btnSwap)
        btnGsi = Button(this).apply { text = "国土地理院"; textSize = 11f; setBackgroundColor(Color.parseColor("#333333")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = 4.dp() }; setOnClickListener { openInGsiMap() } }
        btnGoogleMap = Button(this).apply { text = "Googleマップ"; textSize = 11f; setBackgroundColor(Color.parseColor("#333333")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f); setOnClickListener { openInGoogleMap() } }
        midRow.addView(btnGsi); midRow.addView(btnGoogleMap); mainLayout.addView(midRow)

        mapContainer = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 6.dp(); bottomMargin = 6.dp() } }
        mapWebView = WebView(this).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        mapContainer.addView(mapWebView)
        tvGnssHud = TextView(this).apply { text = "GNSS: 待機中"; setTextColor(Color.parseColor("#DDFFFFFF")); textSize = 10f; setBackgroundColor(Color.parseColor("#88000000")); setPadding(6.dp(), 2.dp(), 6.dp(), 2.dp()); layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(6.dp(), 6.dp(), 6.dp(), 6.dp()) } }
        mapContainer.addView(tvGnssHud); mainLayout.addView(mapContainer)

        btmRowNormal = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp()) }
        btnNewer = Button(this).apply { text = "◀ 新しい写真"; textSize = 12f; setBackgroundColor(Color.parseColor("#2D2D2D")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = 4.dp() }; setOnClickListener { val t = if (isSortDesc) currentIndex - 1 else currentIndex + 1; navigateTo(t) } }
        btnOlder = Button(this).apply { text = "古い写真 ▶"; textSize = 12f; setBackgroundColor(Color.parseColor("#2D2D2D")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = 4.dp() }; setOnClickListener { val t = if (isSortDesc) currentIndex + 1 else currentIndex - 1; navigateTo(t) } }
        btmRowNormal.addView(btnNewer); btmRowNormal.addView(btnOlder); mainLayout.addView(btmRowNormal)

        btmRowCamera = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64.dp()); visibility = View.GONE }
        btnCancelCamera = Button(this).apply { text = "✖ キャンセル"; textSize = 12f; setBackgroundColor(Color.parseColor("#444444")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(120.dp(), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 6.dp() }; setOnClickListener { toggleCameraMode() } }
        btnShutter = Button(this).apply { text = "📸 座標を直書きして撮影"; textSize = 14f; setBackgroundColor(Color.parseColor("#CC0000")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f); setOnClickListener { takePhotoWithExif() } }
        btmRowCamera.addView(btnCancelCamera); btmRowCamera.addView(btnShutter); mainLayout.addView(btmRowCamera)

        setupFullGestureDetector()
        fullContainer = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); setBackgroundColor(Color.BLACK); visibility = View.GONE; setOnTouchListener { _, event -> gestureDetectorFull.onTouchEvent(event); true } }
        fullImageView = ImageView(this).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); scaleType = ImageView.ScaleType.FIT_CENTER }
        fullContainer.addView(fullImageView)
        rootContainer.addView(mainLayout); rootContainer.addView(fullContainer)

        setContentView(rootContainer)
        setupWebView()

        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        else permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            checkAutoResume()
            if (getSharedPreferences("app_settings", Context.MODE_PRIVATE).getBoolean("gps_enabled", false)) {
                toggleGpsPower(true)
            }
        }
    }

    // --- 戻るボタンの安全化 ---
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::fullContainer.isInitialized && fullContainer.visibility == View.VISIBLE) {
            closeFullScreen()
            return
        }
        if (isGpsPowerOn) {
            moveTaskToBack(true)
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        
        // スワイプキル等で裏でGPSが終了していた場合のUI復旧
        val prefEnabled = getSharedPreferences("app_settings", Context.MODE_PRIVATE).getBoolean("gps_enabled", false)
        if (isGpsPowerOn != prefEnabled) {
            toggleGpsPower(prefEnabled)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isGpsPowerOn) {
            toggleGpsPower(false)
        }
    }

    // ★ 1. GPSメイン電源の管理 (完全分離)
    @SuppressLint("MissingPermission")
    private fun toggleGpsPower(enable: Boolean) {
        if (enable && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        
        isGpsPowerOn = enable
        getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().putBoolean("gps_enabled", enable).apply()

        if (enable) {
            btnGpsToggle.text = "📡 ON"
            btnGpsToggle.setBackgroundColor(Color.parseColor("#00CC66"))
            btnGpsToggle.setTextColor(Color.WHITE)
            
            ContextCompat.startForegroundService(this, Intent(this, GpsForegroundService::class.java))
            
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()
            locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) } // 重複登録防止
            
            val callback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { loc ->
                        gpsAccuracy = loc.accuracy
                        val geoField = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), 0f, System.currentTimeMillis())
                        currentDeclination = geoField.declination
                        updateGnssHud()
                        
                        // 自動追従がONの時のみ地図を動かす (分離ロジック)
                        if (isMapAutoPan && isMapReady) {
                            mapWebView.evaluateJavascript("moveToGpsLocation(${loc.latitude}, ${loc.longitude});", null)
                        }
                    }
                }
            }
            locationCallback = callback
            fusedLocationClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
            
            gnssStatusCallback?.let { locationManager?.unregisterGnssStatusCallback(it) }
            gnssStatusCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var usedCount = 0
                    for (i in 0 until status.satelliteCount) {
                        if (status.usedInFix(i)) usedCount++
                    }
                    gnssSatelliteCount = usedCount
                    updateGnssHud()
                }
            }
            gnssStatusCallback?.let { locationManager?.registerGnssStatusCallback(it, null) }
            
            // 電源を入れた時は自動で追従モードもONにする
            setMapAutoPan(true)
        } else {
            btnGpsToggle.text = "📡 OFF"
            btnGpsToggle.setBackgroundColor(Color.parseColor("#383838"))
            btnGpsToggle.setTextColor(Color.parseColor("#BBBBBB"))
            
            stopService(Intent(this, GpsForegroundService::class.java))
            locationCallback?.let {
                fusedLocationClient.removeLocationUpdates(it)
                locationCallback = null
            }
            gnssStatusCallback?.let {
                locationManager?.unregisterGnssStatusCallback(it)
                gnssStatusCallback = null
            }
            
            gnssSatelliteCount = 0
            gpsAccuracy = -1.0f
            updateGnssHud()
            
            // 電源を切った時は追従もOFFにする
            setMapAutoPan(false)
        }
    }

    // ★ 2. 地図の自動追従 (パン) 管理
    private fun setMapAutoPan(enable: Boolean) {
        isMapAutoPan = enable
        if (isMapReady) {
            mapWebView.evaluateJavascript("setGpsButtonActive($enable);", null)
        }
        // GPSがOFFなのに◎が押された場合は、GPSも連動してONにする
        if (enable && !isGpsPowerOn) {
            toggleGpsPower(true)
        }
    }

    private fun forceExitApp() {
        toggleGpsPower(false)
        finishAffinity()
        kotlin.system.exitProcess(0)
    }

    private fun updateGnssHud() {
        runOnUiThread {
            if (!isGpsPowerOn) {
                tvGnssHud.text = "GNSS: 停止中"
            } else {
                val accStr = if (gpsAccuracy > 0) "±%.1fm".format(gpsAccuracy) else "--"
                tvGnssHud.text = "GNSS: ${gnssSatelliteCount}基 / 誤差: $accStr"
            }
        }
    }

    // --- センサー処理 (真北偏角補正) ---
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, lastAccelerometer, 0, event.values.size)
            lastAccelerometerSet = true
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, lastMagnetometer, 0, event.values.size)
            lastMagnetometerSet = true
        }

        if (lastAccelerometerSet && lastMagnetometerSet) {
            val r = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, null, lastAccelerometer, lastMagnetometer)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                val azimuthInRadians = orientation[0]
                var azimuthInDegrees = Math.toDegrees(azimuthInRadians.toDouble()).toFloat()
                if (azimuthInDegrees < 0) azimuthInDegrees += 360f
                
                currentAzimuth = azimuthInDegrees.toDouble()
                var trueAzimuth = (currentAzimuth + currentDeclination) % 360.0
                if (trueAzimuth < 0) trueAzimuth += 360.0
                
                if (isCameraMode && isMapReady) {
                    mapWebView.evaluateJavascript("updateCameraCrosshairDir($trueAzimuth);", null)
                }
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- カメラ (CameraX) & シャッター機能 ---
    private fun toggleCameraMode() {
        isCameraMode = !isCameraMode
        cameraInfoResetJob?.cancel()
        
        if (isCameraMode) {
            ivThumbnail.visibility = View.GONE
            viewFinder.visibility = View.VISIBLE
            btmRowNormal.visibility = View.GONE
            btmRowCamera.visibility = View.VISIBLE
            
            val destText = if (currentFolderUri != null) "【選択中フォルダへ保存】" else "【標準カメラフォルダへ保存】"
            tvImageInfo.text = "$destText\n十字線を対象に合わせて撮影"
            tvImageInfo.setBackgroundColor(Color.parseColor("#CC0000"))
            
            startCamera()
            if (isMapReady) mapWebView.evaluateJavascript("toggleCameraMode(true);", null)
        } else {
            ivThumbnail.visibility = View.VISIBLE
            viewFinder.visibility = View.GONE
            btmRowNormal.visibility = View.VISIBLE
            btmRowCamera.visibility = View.GONE
            tvImageInfo.setBackgroundColor(Color.parseColor("#99000000"))
            
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try { cameraProviderFuture.get().unbindAll() } catch(e: Exception) {}
            }, ContextCompat.getMainExecutor(this))
            
            if (isMapReady) mapWebView.evaluateJavascript("toggleCameraMode(false);", null)
            if (photoList.isNotEmpty()) displayPhoto(currentIndex)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
                val capture = ImageCapture.Builder().build()
                imageCapture = capture
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this@MainActivity, cameraSelector, preview, capture)
            } catch(exc: Exception) {
                Toast.makeText(this@MainActivity, "カメラの起動に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoWithExif() {
        val capture = imageCapture ?: return
        btnShutter.isEnabled = false
        btnShutter.text = "保存中..."

        mapWebView.evaluateJavascript("getCrosshairLocation();") { result ->
            var targetLat = 0.0
            var targetLon = 0.0
            try {
                val json = JSONObject(result.removeSurrounding("\"").replace("\\", ""))
                targetLat = json.getDouble("lat")
                targetLon = json.getDouble("lng")
            } catch (e: Exception) {
                runOnUiThread { 
                    Toast.makeText(this, "座標の取得に失敗しました", Toast.LENGTH_SHORT).show()
                    btnShutter.isEnabled = true
                    btnShutter.text = "📸 座標を直書きして撮影"
                }
                return@evaluateJavascript
            }

            val tempFile = File(cacheDir, "temp_capture.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this@MainActivity),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val exif = ExifInterface(tempFile.absolutePath)
                                val latStr = convertToDegreeMinuteSeconds(targetLat)
                                val lonStr = convertToDegreeMinuteSeconds(targetLon)
                                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, latStr)
                                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (targetLat >= 0) "N" else "S")
                                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, lonStr)
                                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (targetLon >= 0) "E" else "W")

                                val geoField = GeomagneticField(targetLat.toFloat(), targetLon.toFloat(), 0f, System.currentTimeMillis())
                                val declination = geoField.declination.toDouble()
                                var trueAzimuth = (currentAzimuth + declination) % 360.0
                                if (trueAzimuth < 0) trueAzimuth += 360.0

                                exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "%.2f/1".format(Locale.US, trueAzimuth))
                                exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "T")
                                exif.saveAttributes()

                                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())
                                val newFileName = "IMG_${timestamp}.jpg"
                                
                                if (currentFolderUri != null) {
                                    val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, currentFolderUri!!)
                                    val newFileDoc = rootDoc?.createFile("image/jpeg", newFileName)
                                    if (newFileDoc != null) {
                                        contentResolver.openOutputStream(newFileDoc.uri)?.use { outStream ->
                                            FileInputStream(tempFile).use { inStream -> inStream.copyTo(outStream) }
                                        }
                                        withContext(Dispatchers.Main) {
                                            loadPhotosUltraFast(currentFolderUri!!, newFileName)
                                            showTemporaryCameraMessage("保存完了: $newFileName")
                                        }
                                    }
                                } else {
                                    val contentValues = ContentValues().apply {
                                        put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName)
                                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                                        }
                                    }
                                    val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                                    if (imageUri != null) {
                                        contentResolver.openOutputStream(imageUri)?.use { outStream ->
                                            FileInputStream(tempFile).use { inStream -> inStream.copyTo(outStream) }
                                        }
                                        withContext(Dispatchers.Main) {
                                            showTemporaryCameraMessage("標準フォルダに保存完了\n$newFileName")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "保存エラー", Toast.LENGTH_SHORT).show() }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    btnShutter.isEnabled = true
                                    btnShutter.text = "📸 座標を直書きして撮影"
                                    if (tempFile.exists()) tempFile.delete()
                                }
                            }
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Toast.makeText(this@MainActivity, "撮影失敗", Toast.LENGTH_SHORT).show()
                        btnShutter.isEnabled = true
                        btnShutter.text = "📸 座標を直書きして撮影"
                    }
                }
            )
        }
    }

    private fun showTemporaryCameraMessage(msg: String) {
        cameraInfoResetJob?.cancel()
        val destText = if (currentFolderUri != null) "【選択中フォルダへ保存】" else "【標準カメラフォルダへ保存】"
        tvImageInfo.text = "$destText\n$msg"
        cameraInfoResetJob = lifecycleScope.launch {
            delay(3000)
            if (isCameraMode) tvImageInfo.text = "$destText\n十字線を対象に合わせて撮影"
        }
    }

    private fun convertToDegreeMinuteSeconds(coordinate: Double): String {
        val absolute = abs(coordinate)
        val degrees = absolute.toInt()
        val minutes = ((absolute - degrees) * 60).toInt()
        val seconds = ((absolute - degrees - minutes / 60.0) * 3600 * 1000).toInt()
        return "$degrees/1,$minutes/1,$seconds/1000"
    }

    // --- UI入れ替え ---
    private fun toggleViewOrder() {
        isMapOnTop = !isMapOnTop
        (thumbContainer.parent as? ViewGroup)?.removeView(thumbContainer)
        (mapContainer.parent as? ViewGroup)?.removeView(mapContainer)

        if (isMapOnTop) {
            mapContainer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            topSection.addView(mapContainer)
            thumbContainer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 6.dp(); bottomMargin = 6.dp() }
            val midRowIdx = mainLayout.indexOfChild(btnSwap.parent as View)
            mainLayout.addView(thumbContainer, midRowIdx + 1)
        } else {
            thumbContainer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            topSection.addView(thumbContainer)
            mapContainer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = 6.dp(); bottomMargin = 6.dp() }
            val midRowIdx = mainLayout.indexOfChild(btnSwap.parent as View)
            mainLayout.addView(mapContainer, midRowIdx + 1)
        }
    }

    // --- スワイプ検知 ---
    private fun setupThumbGestureDetector() {
        gestureDetectorThumb = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 50
            private val SWIPE_VELOCITY_THRESHOLD = 50
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                if (isCameraMode || photoList.isEmpty()) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY) && abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    val t = if (diffX > 0) { if (isSortDesc) currentIndex - 1 else currentIndex + 1 } else { if (isSortDesc) currentIndex + 1 else currentIndex - 1 }
                    if (t in 0 until photoList.size) navigateTo(t)
                    return true
                } else if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    val t = if (diffY > 0) { if (isSortDesc) currentIndex - 1 else currentIndex + 1 } else { if (isSortDesc) currentIndex + 1 else currentIndex - 1 }
                    if (t in 0 until photoList.size) navigateTo(t)
                    return true
                }
                return false
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isCameraMode) openFullScreen()
                return true
            }
        })
    }

    private fun setupFullGestureDetector() {
        gestureDetectorFull = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY) && abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    val t = if (diffX > 0) { if (isSortDesc) currentIndex - 1 else currentIndex + 1 } else { if (isSortDesc) currentIndex + 1 else currentIndex - 1 }
                    if (t in 0 until photoList.size) navigateToAndRefreshFull(t)
                    return true
                } else if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    val t = if (diffY > 0) { if (isSortDesc) currentIndex - 1 else currentIndex + 1 } else { if (isSortDesc) currentIndex + 1 else currentIndex - 1 }
                    if (t in 0 until photoList.size) navigateToAndRefreshFull(t)
                    return true
                }
                return false
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                closeFullScreen()
                return true
            }
        })
    }

    private fun navigateToAndRefreshFull(index: Int) {
        navigateTo(index)
        updateFullScreenImage()
    }

    // --- 設定・メニュー ---
    private fun loadSettings() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        isSortDesc = prefs.getBoolean("sort_desc", true)
        pinLimitCount = prefs.getInt("pin_limit_count", 50)
        cacheLimitMb = prefs.getInt("cache_limit_mb", 100)
        startupMode = prefs.getInt("startup_mode", 0)
    }

    private fun saveSettings() {
        getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("sort_desc", isSortDesc)
            .putInt("pin_limit_count", pinLimitCount)
            .putInt("cache_limit_mb", cacheLimitMb)
            .putInt("startup_mode", startupMode).apply()
    }

    private fun checkAutoResume() {
        if (startupMode == 2) return
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val lastFolder = prefs.getString("last_folder_uri", null) ?: return
        val lastPhoto = prefs.getString("last_photo_name", null)

        val uri = Uri.parse(lastFolder)
        val hasPerm = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (hasPerm) {
            val targetName = if (startupMode == 0) lastPhoto else null
            loadPhotosUltraFast(uri, targetName)
        }
    }

    private fun showMainMenu() {
        val items = arrayOf(
            "📁 ファイル操作 ▶", 
            "📍 地図ピン表示 ▶", 
            "💾 キャッシュ管理 ▶", 
            "⚙️ 起動時の設定 ▶",
            "🚪 アプリを完全に終了"
        )
        AlertDialog.Builder(this)
            .setTitle("メニュー")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showFileMenu()
                    1 -> showPinMenu()
                    2 -> showCacheMenu()
                    3 -> showStartupMenu()
                    4 -> forceExitApp()
                }
            }.setNegativeButton("閉じる", null).show()
    }

    private fun showFileMenu() {
        val sortLabel = if (isSortDesc) "新しい順 (降順)" else "古い順 (昇順)"
        val items = arrayOf("🖼️ サムネイル一覧", "📑 ファイル名一覧", "並び順: $sortLabel")
        AlertDialog.Builder(this).setTitle("ファイル操作").setItems(items) { _, which ->
            when (which) {
                0 -> showThumbnailGridPicker(); 1 -> showFileNamePicker(); 2 -> showSortDialog()
            }
        }.setNegativeButton("戻る") { _, _ -> showMainMenu() }.show()
    }

    private fun showThumbnailGridPicker() {
        if (photoList.isEmpty()) return
        val dialogView = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#1E1E1E")) }
        val gridView = GridView(this).apply {
            numColumns = 3; stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = 4.dp(); horizontalSpacing = 4.dp(); setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
        }
        dialogView.addView(gridView)

        var dialogRef: AlertDialog? = null
        val cellSize = (resources.displayMetrics.widthPixels - 36.dp()) / 3

        gridView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = photoList.size
            override fun getItem(position: Int): Any = photoList[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val frame: FrameLayout
                val img: ImageView
                val badge: TextView

                if (convertView == null) {
                    frame = FrameLayout(this@MainActivity).apply { layoutParams = AbsListView.LayoutParams(cellSize, cellSize); setBackgroundColor(Color.BLACK) }
                    img = ImageView(this@MainActivity).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); scaleType = ImageView.ScaleType.CENTER_CROP }
                    badge = TextView(this@MainActivity).apply { textSize = 10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#99000000")); setPadding(4.dp(), 2.dp(), 4.dp(), 2.dp()); layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START) }
                    frame.addView(img); frame.addView(badge)
                } else {
                    frame = convertView as FrameLayout; img = frame.getChildAt(0) as ImageView; badge = frame.getChildAt(1) as TextView
                }

                val item = photoList[position]
                badge.text = "${position + 1}${if (item.lat != null) " 📍" else ""}"

                if (position == currentIndex) { frame.setPadding(3.dp(), 3.dp(), 3.dp(), 3.dp()); frame.setBackgroundColor(Color.parseColor("#0066FF")) }
                else { frame.setPadding(0, 0, 0, 0); frame.setBackgroundColor(Color.BLACK) }

                val cacheKey = "${item.uri}_grid"
                val cachedBmp = memoryCache.get(cacheKey)
                if (cachedBmp != null) img.setImageBitmap(cachedBmp)
                else {
                    img.setImageDrawable(null); img.tag = item.uri
                    lifecycleScope.launch(Dispatchers.IO) {
                        val bmp = decodeSampledBitmap(item.uri, cellSize, cellSize)
                        if (bmp != null) { memoryCache.put(cacheKey, bmp); withContext(Dispatchers.Main) { if (img.tag == item.uri) img.setImageBitmap(bmp) } }
                    }
                }
                frame.setOnClickListener { dialogRef?.dismiss(); navigateTo(position) }
                return frame
            }
        }

        val builtDialog = AlertDialog.Builder(this).setTitle("写真を選択 (全${photoList.size}枚)").setView(dialogView).setNegativeButton("キャンセル", null).create()
        dialogRef = builtDialog
        builtDialog.show()
    }

    private fun showFileNamePicker() {
        if (photoList.isEmpty()) return
        val names = photoList.mapIndexed { idx, p ->
            val prefix = if (idx == currentIndex) "▶ " else "   "
            "$prefix[${idx + 1}] ${p.name}${if (p.lat != null) " [GPS]" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("ファイル名一覧").setItems(names) { _, which -> navigateTo(which) }.setNegativeButton("戻る") { _, _ -> showFileMenu() }.show()
    }

    private fun showSortDialog() {
        val options = arrayOf("新しい順 (降順)", "古い順 (昇順)")
        val currentSelection = if (isSortDesc) 0 else 1
        AlertDialog.Builder(this).setTitle("並び順").setSingleChoiceItems(options, currentSelection) { dialog, which ->
            isSortDesc = (which == 0); saveSettings(); applySort(); dialog.dismiss()
        }.show()
    }

    private fun showPinMenu() {
        val options = arrayOf("50枚", "100枚", "全て表示", "手動設定 (${if (pinLimitCount > 0 && pinLimitCount != 50 && pinLimitCount != 100) "${pinLimitCount}枚" else "指定"})")
        val currentIdx = when (pinLimitCount) { 50 -> 0; 100 -> 1; -1 -> 2; else -> 3 }
        AlertDialog.Builder(this).setTitle("地図ピン表示件数").setSingleChoiceItems(options, currentIdx) { dialog, which ->
            when (which) {
                0 -> { pinLimitCount = 50; finishPinChange(dialog) }; 1 -> { pinLimitCount = 100; finishPinChange(dialog) }; 2 -> { pinLimitCount = -1; finishPinChange(dialog) }; 3 -> { dialog.dismiss(); showCustomPinDialog() }
            }
        }.setNegativeButton("戻る") { _, _ -> showMainMenu() }.show()
    }

    private fun finishPinChange(dialog: DialogInterface) { saveSettings(); renderCurrentPhotoOnMap(); dialog.dismiss() }

    private fun showCustomPinDialog() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "表示枚数 (例: 200)"; if (pinLimitCount > 0) setText(pinLimitCount.toString()) }
        AlertDialog.Builder(this).setTitle("手動設定").setView(input).setPositiveButton("決定") { _, _ ->
            val count = input.text.toString().toIntOrNull()
            if (count != null && count > 0) { pinLimitCount = count; saveSettings(); renderCurrentPhotoOnMap() }
            else Toast.makeText(this, "正しい数値を入力してください", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("キャンセル") { _, _ -> showPinMenu() }.show()
    }

    // --- 爆速・安全なキャッシュ管理 ---
    private fun getTileCacheDir(): File {
        val dirs = ContextCompat.getExternalCacheDirs(this)
        val storageIndex = getSharedPreferences("app_settings", Context.MODE_PRIVATE).getInt("storage_index", 0)
        val baseDir = if (storageIndex < dirs.size && dirs[storageIndex] != null) dirs[storageIndex] else dirs[0]
        val tileDir = File(baseDir, "tiles")
        if (!tileDir.exists()) tileDir.mkdirs()
        return tileDir
    }

    private fun getFolderSize(file: File): Long {
        var size = 0L
        if (file.isDirectory) {
            file.listFiles()?.forEach { size += getFolderSize(it) }
        } else {
            size = file.length()
        }
        return size
    }

    private fun showCacheMenu() {
        lifecycleScope.launch(Dispatchers.IO) {
            val tileDir = getTileCacheDir()
            val currentSizeMb = getFolderSize(tileDir) / (1024.0 * 1024.0)
            val limitStr = if (cacheLimitMb > 0) "${cacheLimitMb} MB" else "無制限"
            val storageIndex = getSharedPreferences("app_settings", Context.MODE_PRIVATE).getInt("storage_index", 0)
            val storageName = if (storageIndex == 0) "内部ストレージ" else "SDカード"
            
            withContext(Dispatchers.Main) {
                val items = arrayOf(
                    "🗂️ 保存先: $storageName (変更)",
                    "容量: %.1f MB / $limitStr".format(currentSizeMb),
                    "📥 周辺3kmの地図を保存",
                    "🗑️ 地図タイルキャッシュ消去",
                    "🗑️ Exifメタデータ消去"
                )
                AlertDialog.Builder(this@MainActivity).setTitle("キャッシュ管理").setItems(items) { _, which ->
                    when (which) {
                        0 -> showStorageSelectionDialog()
                        1 -> showCacheLimitDialog()
                        2 -> triggerPrecacheAroundCenter()
                        3 -> clearTileCache()
                        4 -> clearMetadataCache()
                    }
                }.setNegativeButton("戻る") { _, _ -> showMainMenu() }.show()
            }
        }
    }

    private fun showStorageSelectionDialog() {
        val dirs = ContextCompat.getExternalCacheDirs(this)
        val options = mutableListOf("内部ストレージ (高速推奨)")
        if (dirs.size > 1 && dirs[1] != null) options.add("SDカード領域")
        
        val currentIndex = getSharedPreferences("app_settings", Context.MODE_PRIVATE).getInt("storage_index", 0)
        val validIndex = if (currentIndex < options.size) currentIndex else 0

        AlertDialog.Builder(this)
            .setTitle("地図キャッシュの保存先")
            .setSingleChoiceItems(options.toTypedArray(), validIndex) { dialog, which ->
                getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().putInt("storage_index", which).apply()
                Toast.makeText(this, "保存先を変更しました", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }.setNegativeButton("戻る") { _, _ -> showCacheMenu() }.show()
    }

    private fun triggerPrecacheAroundCenter() {
        mapWebView.evaluateJavascript("getMapCenterAndZoom();") { result ->
            try {
                val json = JSONObject(result.removeSurrounding("\"").replace("\\", ""))
                startPrecacheTiles(json.getDouble("lat"), json.getDouble("lng"), json.getInt("zoom"))
            } catch (e: Exception) {
                Toast.makeText(this, "中心座標の取得に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPrecacheTiles(lat: Double, lng: Double, zoom: Int) {
        val dLat = 3.0 / 111.32
        val dLng = 3.0 / (111.32 * cos(Math.toRadians(lat)))
        val minLat = lat - dLat; val maxLat = lat + dLat
        val minLng = lng - dLng; val maxLng = lng + dLng

        val n = 1 shl zoom
        fun lng2TileX(l: Double): Int = floor((l + 180.0) / 360.0 * n).toInt()
        fun lat2TileY(l: Double): Int {
            val rad = Math.toRadians(l)
            return floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * n).toInt()
        }

        val minX = lng2TileX(minLng); val maxX = lng2TileX(maxLng)
        val minY = lat2TileY(maxLat); val maxY = lat2TileY(minLat)

        val tileUrls = mutableListOf<String>()
        for (x in minOf(minX, maxX)..maxOf(minX, maxX)) {
            for (y in minOf(minY, maxY)..maxOf(minY, maxY)) {
                tileUrls.add("https://cyberjapandata.gsi.go.jp/xyz/std/$zoom/$x/$y.png")
            }
        }

        val total = tileUrls.size
        if (total > 300) {
            Toast.makeText(this, "範囲が広すぎるため縮尺を拡大(ズームイン)してください", Toast.LENGTH_LONG).show()
            return
        }

        val progressLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 16.dp(), 20.dp(), 16.dp())
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = total; progress = 0 }
        val progressText = TextView(this).apply { text = "準備中... 0 / $total 枚"; setTextColor(Color.WHITE); textSize = 12f; setPadding(0, 8.dp(), 0, 0) }
        progressLayout.addView(progressBar); progressLayout.addView(progressText)

        val dialog = AlertDialog.Builder(this).setTitle("3km圏内保存 (Zoom: $zoom)").setView(progressLayout).setCancelable(false).setNegativeButton("中止", null).show()
        var isCancelled = false
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { isCancelled = true; dialog.dismiss() }

        lifecycleScope.launch(Dispatchers.IO) {
            var processedCount = 0
            val tileDir = getTileCacheDir()
            val now = System.currentTimeMillis()

            for (urlStr in tileUrls) {
                if (isCancelled) break
                val cacheFile = File(tileDir, md5(urlStr) + ".png")
                val isFresh = cacheFile.exists() && cacheFile.length() > 0L && (now - cacheFile.lastModified() < TILE_MAX_AGE_MS)
                
                if (!isFresh) {
                    try {
                        val conn = URL(urlStr).openConnection() as HttpURLConnection
                        conn.connectTimeout = 3000; conn.readTimeout = 3000; conn.connect()
                        if (conn.responseCode == 200) {
                            FileOutputStream(cacheFile).use { it.write(conn.inputStream.readBytes()) }
                        }
                    } catch (e: Exception) {}
                    delay(30)
                }
                processedCount++
                withContext(Dispatchers.Main) {
                    progressBar.progress = processedCount
                    progressText.text = "処理中: $processedCount / $total 枚"
                }
            }
            withContext(Dispatchers.Main) {
                if (dialog.isShowing) dialog.dismiss()
                trimTileCacheIfNeeded()
                Toast.makeText(this@MainActivity, "周辺3kmタイルを更新しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCacheLimitDialog() {
        val options = arrayOf("50 MB", "100 MB", "250 MB", "500 MB", "無制限")
        val values = listOf(50, 100, 250, 500, -1)
        val currentIdx = values.indexOf(cacheLimitMb).let { if (it >= 0) it else 1 }
        AlertDialog.Builder(this).setTitle("地図キャッシュ上限").setSingleChoiceItems(options, currentIdx) { dialog, which ->
            cacheLimitMb = values[which]; saveSettings(); lifecycleScope.launch(Dispatchers.IO) { trimTileCacheIfNeeded() }; dialog.dismiss()
        }.setNegativeButton("戻る") { _, _ -> showCacheMenu() }.show()
    }

    private fun clearTileCache() { lifecycleScope.launch(Dispatchers.IO) { val tileDir = getTileCacheDir(); if (tileDir.exists()) tileDir.deleteRecursively(); withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "消去しました", Toast.LENGTH_SHORT).show() } } }
    private fun clearMetadataCache() { getSharedPreferences("exif_metadata_cache", Context.MODE_PRIVATE).edit().clear().apply(); Toast.makeText(this, "Exifキャッシュ消去完了", Toast.LENGTH_SHORT).show() }
    private fun showStartupMenu() {
        val options = arrayOf("前回最後に見ていた写真", "フォルダの先頭ファイル", "手動でフォルダ選択")
        AlertDialog.Builder(this).setTitle("起動時の動作").setSingleChoiceItems(options, startupMode) { dialog, which -> startupMode = which; saveSettings(); dialog.dismiss() }.setNegativeButton("戻る") { _, _ -> showMainMenu() }.show()
    }

    private fun openFullScreen() {
        if (photoList.isEmpty()) return
        fullContainer.visibility = View.VISIBLE
        updateFullScreenImage()
    }
    private fun updateFullScreenImage() {
        if (photoList.isEmpty()) return
        val item = photoList.getOrNull(currentIndex) ?: return
        ivThumbnail.drawable?.let { fullImageView.setImageDrawable(it) }
        fullScreenDecodeJob?.cancel()
        fullScreenDecodeJob = lifecycleScope.launch(Dispatchers.IO) {
            val bmp = decodeSampledBitmap(item.uri, 2560, 2560)
            withContext(Dispatchers.Main) { if (fullContainer.visibility == View.VISIBLE && bmp != null) fullImageView.setImageBitmap(bmp) }
        }
    }
    private fun closeFullScreen() { fullContainer.visibility = View.GONE; fullScreenDecodeJob?.cancel() }

    private fun decodeSampledBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                val rawWidth = options.outWidth; val rawHeight = options.outHeight; var inSample = 1
                if (rawWidth > reqWidth || rawHeight > reqHeight) {
                    val halfWidth = rawWidth / 2; val halfHeight = rawHeight / 2
                    while ((halfWidth / inSample) >= reqWidth && (halfHeight / inSample) >= reqHeight) { inSample *= 2 }
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = inSample }
                contentResolver.openInputStream(uri)?.use { s2 -> BitmapFactory.decodeStream(s2, null, decodeOpts) }
            }
        } catch (e: Exception) { null }
    }

    private fun trimTileCacheIfNeeded() {
        if (cacheLimitMb <= 0) return
        val tileDir = getTileCacheDir(); if (!tileDir.exists()) return
        val files = tileDir.listFiles() ?: return
        var total = files.sumOf { it.length() }; val maxBytes = cacheLimitMb * 1024L * 1024L
        if (total > maxBytes) {
            val sorted = files.sortedBy { it.lastModified() }; val target = (maxBytes * 0.8).toLong()
            for (f in sorted) { val len = f.length(); if (f.delete()) { total -= len; if (total <= target) break } }
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // --- 爆速リストアップ処理 ---
    private suspend fun fetchFileListQuery(folderUri: Uri): List<PhotoItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<PhotoItem>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, DocumentsContract.getTreeDocumentId(folderUri))
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeIdx) ?: ""
                    if (mime.startsWith("image/jpeg") || mime.startsWith("image/jpg")) {
                        val docId = cursor.getString(idIdx); val name = cursor.getString(nameIdx) ?: "Unknown"; val lastMod = cursor.getLong(modIdx)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                        list.add(PhotoItem(uri, name, lastMod))
                    }
                }
            }
        } catch (e: Exception) { }
        list
    }

    private fun loadPhotosUltraFast(folderUri: Uri, restorePhotoName: String?) {
        backgroundLoadJob?.cancel()
        currentFolderUri = folderUri

        lifecycleScope.launch {
            if (!isCameraMode) { tvImageInfo.text = "データベース検索中..."; ivThumbnail.setImageDrawable(null) }
            photoList.clear()

            val rawList = fetchFileListQuery(folderUri)
            if (rawList.isEmpty()) {
                if (!isCameraMode) tvImageInfo.text = "写真なし"
                Toast.makeText(this@MainActivity, "JPEGが見つかりません", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            photoList.addAll(rawList); applySortListOnly()
            val initialLoadCount = if (pinLimitCount > 0) minOf(pinLimitCount, photoList.size) else minOf(50, photoList.size)
            withContext(Dispatchers.IO) { parseExifBlock(0, initialLoadCount) }

            var targetIdx = 0
            if (restorePhotoName != null) {
                val found = photoList.indexOfFirst { it.name == restorePhotoName }
                if (found != -1) targetIdx = found
            }
            if (!photoList[targetIdx].isLoaded) { withContext(Dispatchers.IO) { parseExifBlock(targetIdx, targetIdx + 1) } }
            
            currentIndex = targetIdx
            displayPhoto(currentIndex)
            if (initialLoadCount < photoList.size) startBackgroundParsing(initialLoadCount)
        }
    }

    private fun applySortListOnly() {
        if (isSortDesc) photoList.sortByDescending { it.lastModified } else photoList.sortBy { it.lastModified }
    }

    private fun applySort() {
        if (photoList.isEmpty()) return
        val currentItem = photoList.getOrNull(currentIndex)
        applySortListOnly()
        if (currentItem != null) {
            val newIndex = photoList.indexOfFirst { it.uri == currentItem.uri }
            currentIndex = if (newIndex != -1) newIndex else 0
        }
        displayPhoto(currentIndex)
    }

    private fun parseExifBlock(startIndex: Int, endIndex: Int) {
        val prefs = getSharedPreferences("exif_metadata_cache", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (i in startIndex until minOf(endIndex, photoList.size)) {
            val item = photoList[i]; if (item.isLoaded) continue
            val cacheKey = "${item.name}_${item.lastModified}"; val cachedData = prefs.getString(cacheKey, null)
            if (cachedData != null) {
                if (cachedData != "NO_GPS") {
                    val parts = cachedData.split(",")
                    if (parts.size >= 2) {
                        item.lat = parts[0].toDoubleOrNull(); item.lon = parts[1].toDoubleOrNull()
                        if (parts.size >= 3) item.direction = parts[2].toDoubleOrNull()
                        item.debugStatus = "OK"
                    }
                } else item.debugStatus = "GPSタグなし"
            } else {
                try {
                    contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor); val latLong = FloatArray(2)
                        if (exif.getLatLong(latLong)) {
                            item.lat = latLong[0].toDouble(); item.lon = latLong[1].toDouble()
                            val direction = exif.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, -1.0)
                            if (direction >= 0) item.direction = direction
                            item.debugStatus = "OK"
                            editor.putString(cacheKey, "${item.lat},${item.lon},${item.direction ?: ""}")
                        } else {
                            item.debugStatus = "GPSタグなし"; editor.putString(cacheKey, "NO_GPS")
                        }
                    }
                } catch (e: Exception) { item.debugStatus = "例外" }
            }
            item.isLoaded = true
        }
        editor.apply()
    }

    private fun startBackgroundParsing(startIndex: Int) {
        tvBgLoadStatus.visibility = View.VISIBLE
        backgroundLoadJob = lifecycleScope.launch(Dispatchers.IO) {
            val total = photoList.size; val batchSize = 10
            for (i in startIndex until total step batchSize) {
                parseExifBlock(i, i + batchSize); val currentParsed = minOf(i + batchSize, total)
                withContext(Dispatchers.Main) {
                    tvBgLoadStatus.text = "裏で解析中: $currentParsed / $total"
                    if (pinLimitCount == -1 || currentParsed <= pinLimitCount) renderCurrentPhotoOnMap()
                }
                delay(20)
            }
            withContext(Dispatchers.Main) { tvBgLoadStatus.visibility = View.GONE }
        }
    }

    private fun navigateTo(newIndex: Int) {
        if (photoList.isEmpty()) return
        if (newIndex in photoList.indices) {
            currentIndex = newIndex
            if (!photoList[currentIndex].isLoaded) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { parseExifBlock(currentIndex, currentIndex + 1) }
                    displayPhoto(currentIndex)
                }
            } else displayPhoto(currentIndex)
        }
    }

    private fun displayPhoto(index: Int) {
        val item = photoList[index]
        getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().putString("last_photo_name", item.name).apply()

        val cacheKey = "${item.uri}_main"; val cachedBmp = memoryCache.get(cacheKey)
        if (cachedBmp != null) ivThumbnail.setImageBitmap(cachedBmp)
        else {
            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = decodeSampledBitmap(item.uri, 800, 800)
                if (bmp != null) { memoryCache.put(cacheKey, bmp); withContext(Dispatchers.Main) { if (currentIndex == index) ivThumbnail.setImageBitmap(bmp) } }
            }
        }

        val gpsText = if (item.lat != null && item.lon != null) {
            var t = "GPS: ${"%.6f".format(item.lat)}, ${"%.6f".format(item.lon)}"
            if (item.direction != null) t += " (方位: ${item.direction}°)"
            t
        } else "GPS: なし (${item.debugStatus})"

        if (isCameraMode) {
            val destText = if (currentFolderUri != null) "【選択中フォルダへ保存】" else "【標準カメラフォルダへ保存】"
            tvImageInfo.text = "$destText\n十字線を対象に合わせて撮影"
            tvImageInfo.setBackgroundColor(Color.parseColor("#CC0000"))
        } else {
            tvImageInfo.text = "[${index + 1}/${photoList.size}] ${item.name}\n$gpsText"
            tvImageInfo.setBackgroundColor(Color.parseColor("#99000000"))
        }

        btnNewer.isEnabled = (if (isSortDesc) index > 0 else index < photoList.size - 1)
        btnOlder.isEnabled = (if (isSortDesc) index < photoList.size - 1 else index > 0)

        val hasGps = (item.lat != null && item.lon != null)
        btnGsi.isEnabled = hasGps; btnGoogleMap.isEnabled = hasGps
        renderCurrentPhotoOnMap()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        mapWebView.settings.javaScriptEnabled = true
        mapWebView.settings.domStorageEnabled = true
        mapWebView.addJavascriptInterface(WebAppInterface(), "Android")

        mapWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                isMapReady = true
                renderCurrentPhotoOnMap()
                if (isMapAutoPan) mapWebView.evaluateJavascript("setGpsButtonActive(true);", null)
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                if (url.contains("cyberjapandata.gsi.go.jp")) {
                    try {
                        val tileDir = getTileCacheDir()
                        val ext = if (url.endsWith(".png")) ".png" else ".jpg"
                        val mime = if (url.endsWith(".png")) "image/png" else "image/jpeg"
                        val cacheFile = File(tileDir, md5(url) + ext)
                        val now = System.currentTimeMillis()

                        val isFresh = cacheFile.exists() && cacheFile.length() > 0L && (now - cacheFile.lastModified() < TILE_MAX_AGE_MS)
                        if (isFresh) {
                            return WebResourceResponse(mime, "UTF-8", FileInputStream(cacheFile))
                        }

                        try {
                            val conn = URL(url).openConnection() as HttpURLConnection
                            conn.connectTimeout = 4000; conn.readTimeout = 4000; conn.connect()
                            if (conn.responseCode == 200) {
                                val bytes = conn.inputStream.readBytes(); FileOutputStream(cacheFile).use { it.write(bytes) }
                                lifecycleScope.launch(Dispatchers.IO) { trimTileCacheIfNeeded() }
                                return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(bytes))
                            }
                        } catch (e: Exception) {
                            if (cacheFile.exists() && cacheFile.length() > 0L) {
                                return WebResourceResponse(mime, "UTF-8", FileInputStream(cacheFile))
                            }
                        }
                    } catch (e: Exception) { }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        // assets から HTML を読み込み、地理院ドメインのコンテキストで展開
        val htmlContent = assets.open("map.html").bufferedReader().use { it.readText() }
        mapWebView.loadDataWithBaseURL("https://maps.gsi.go.jp", htmlContent, "text/html", "UTF-8", null)
    }

    private fun renderCurrentPhotoOnMap() {
        if (!isMapReady || photoList.isEmpty() || isCameraMode) return

        val current = photoList[currentIndex]
        val jsonArray = JSONArray()
        val loadedPhotos = photoList.filter { it.isLoaded }
        val targets = if (pinLimitCount > 0) loadedPhotos.take(pinLimitCount) else loadedPhotos

        targets.forEach { p ->
            if (p.lat != null && p.lon != null) {
                val originalIndex = photoList.indexOf(p)
                val obj = JSONObject().apply {
                    put("index", originalIndex); put("lat", p.lat); put("lon", p.lon)
                    if (p.direction != null) put("dir", p.direction)
                    put("hasGps", true); put("isCurrent", originalIndex == currentIndex)
                }
                jsonArray.put(obj)
            }
        }
        val hasCoords = current.lat != null && current.lon != null
        mapWebView.evaluateJavascript("updatePoints(${current.lat ?: 0.0}, ${current.lon ?: 0.0}, $hasCoords, '${jsonArray}', $currentIndex);", null)
    }

    private fun openInGsiMap() {
        val item = photoList.getOrNull(currentIndex) ?: return
        val lat = item.lat ?: return; val lon = item.lon ?: return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.gsi.go.jp/#18/${lat}/${lon}/")))
    }
    private fun openInGoogleMap() {
        val item = photoList.getOrNull(currentIndex) ?: return
        val lat = item.lat ?: return; val lon = item.lon ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(撮影地点)")).apply { setPackage("com.google.android.apps.maps") }) } 
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lon"))) }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onMarkerClicked(index: Int) { runOnUiThread { if (index >= 0 && !isCameraMode) navigateTo(index) } }
        
        // ★ 重要：親クラス(MainActivity)のメソッドを明示指定して無限再帰クラッシュを防止
        @JavascriptInterface
        fun setMapAutoPan(isTracking: Boolean) {
            runOnUiThread { this@MainActivity.setMapAutoPan(isTracking) }
        }

        // ★ ドラッグされた時に呼ばれる（GPS電源は切らずに追従のみOFF）
        @JavascriptInterface
        fun disableAutoPan() {
            runOnUiThread { isMapAutoPan = false }
        }

        @JavascriptInterface
        fun jumpToSelectedPhoto() {
            runOnUiThread {
                if (photoList.isNotEmpty()) {
                    val current = photoList[currentIndex]
                    if (current.lat != null && current.lon != null) {
                        mapWebView.evaluateJavascript("map.panTo([${current.lat}, ${current.lon}], { animate: true, duration: 0.4 });", null)
                    } else {
                        Toast.makeText(this@MainActivity, "この写真にはGPS情報がありません", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
