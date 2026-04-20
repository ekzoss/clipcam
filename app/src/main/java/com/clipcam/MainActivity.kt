package com.clipcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.SensorManager
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.TypedValue
import android.view.*
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.clipcam.databinding.ActivityMainBinding
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService

    private var highlightRecorder: HighlightRecorder? = null

    private var currentQuality = Quality.FHD
    private var currentFps = 60
    private var bufferDurationSec = 6
    private var audioLagMs = 250L
    
    private var bufferStartTimeTotal: Long = 0
    private var isBufferingEnabled = false
    private var isSavingHighlightInProgress = false
    
    private val handler = Handler(Looper.getMainLooper())
    private val bufferTimerRunnable = object : Runnable {
        override fun run() {
            if (isBufferingEnabled) {
                val elapsedSinceBufferStart = (System.currentTimeMillis() - bufferStartTimeTotal) / 1000
                val displaySeconds = minOf(elapsedSinceBufferStart, bufferDurationSec.toLong())
                viewBinding.textBufferTime.text = String.format(Locale.US, "%ds", displaySeconds)
                handler.postDelayed(this, 500)
            }
        }
    }

    private lateinit var orientationEventListener: OrientationEventListener
    private var currentUiRotation = 0f

    private val hideRulerHandler = Handler(Looper.getMainLooper())
    private val hideRulerRunnable = Runnable {
        viewBinding.zoomRulerContainer.visibility = View.GONE
        viewBinding.zoomShortcuts.visibility = View.VISIBLE
    }

    private var currentZoomRatio = 1.0f
    private var isSyncingRuler = false

    private var isManualFocusActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        setupOrientationListener()
        setupControls()
        setupZoom()
        setupTapToFocus()
        setupSettingsPanel()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                
                val newRotation = when {
                    orientation >= 315 || orientation < 45 -> 0f
                    orientation in 45..134 -> 270f
                    orientation in 135..224 -> 180f
                    else -> 90f
                }

                if (newRotation != currentUiRotation) {
                    currentUiRotation = newRotation
                    updateUIForRotation(newRotation)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationEventListener.enable()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener.disable()
        highlightRecorder?.stop()
        highlightRecorder = null
        cameraProvider?.unbindAll()
        isBufferingEnabled = false
        cleanupBufferingUI()
    }

    private fun updateUIForRotation(rotationDegrees: Float) {
        val viewsToRotate = mutableListOf<View>(
            viewBinding.btnSaveHighlight,
            viewBinding.textBufferTime,
            viewBinding.textBufferInactive,
            viewBinding.btnToggleBuffer,
            viewBinding.btnSettings,
            viewBinding.zoom05,
            viewBinding.zoom1,
            viewBinding.zoom2,
            viewBinding.textCurrentZoom,
            viewBinding.focusRing
        )
        
        viewsToRotate.forEach { it.animate().rotation(rotationDegrees).setDuration(300).start() }
    }

    private fun setupControls() {
        viewBinding.btnToggleBuffer.setOnClickListener { toggleBuffering() }
        viewBinding.btnSaveHighlight.setOnClickListener { saveHighlight() }
        viewBinding.btnSettings.setOnClickListener {
            viewBinding.settingsPanel.visibility = if (viewBinding.settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        viewBinding.textBufferInactive.text = String.format(Locale.US, "%ds", bufferDurationSec)
    }

    private fun setupSettingsPanel() {
        viewBinding.bufferSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = progress + 2
                bufferDurationSec = duration
                highlightRecorder?.updateBufferDuration(duration)
                viewBinding.textBufferDuration.text = String.format(Locale.US, "%ds", duration)
                viewBinding.textBufferInactive.text = String.format(Locale.US, "%ds", duration)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        viewBinding.bufferSlider.progress = bufferDurationSec - 2
        viewBinding.textBufferDuration.text = String.format(Locale.US, "%ds", bufferDurationSec)
        viewBinding.textBufferInactive.text = String.format(Locale.US, "%ds", bufferDurationSec)
        
        viewBinding.editAudioLag.setText(audioLagMs.toString())
        viewBinding.editAudioLag.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toLongOrNull() ?: 0L
                audioLagMs = value
                highlightRecorder?.updateLagCompensation(value)
            }
        })
        
        updateSettingsPanelUI()
    }

    private fun updateSettingsPanelUI() {
        val resolutions = mapOf("UHD" to Quality.UHD, "FHD" to Quality.FHD, "HD" to Quality.HD, "SD" to Quality.SD, "AUTO" to Quality.HIGHEST)
        val fpsList = listOf(60, 30)

        viewBinding.resolutionOptions.removeAllViews()
        resolutions.forEach { (name, quality) ->
            val isSelected = currentQuality == quality
            val btn = createSettingsButton(name, isSelected)
            btn.setOnClickListener {
                currentQuality = quality
                updateSettingsPanelUI()
                startCamera()
            }
            viewBinding.resolutionOptions.addView(btn)
        }

        viewBinding.fpsOptions.removeAllViews()
        fpsList.forEach { fps ->
            val isSelected = currentFps == fps
            val btn = createSettingsButton(String.format(Locale.US, "%dFPS", fps), isSelected)
            btn.setOnClickListener {
                currentFps = fps
                updateSettingsPanelUI()
                startCamera()
            }
            viewBinding.fpsOptions.addView(btn)
        }
    }

    private fun createSettingsButton(text: String, isSelected: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
        tv.setBackgroundResource(if (isSelected) R.drawable.bg_setting_selected else 0)
        tv.gravity = Gravity.CENTER
        tv.setPadding(24, 12, 24, 12)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        params.setMargins(4, 0, 4, 0)
        tv.layoutParams = params
        return tv
    }

    @OptIn(ExperimentalCamera2Interop::class)
    @SuppressLint("ClickableViewAccessibility")
    private fun setupZoom() {
        val rulerContent = viewBinding.zoomRulerContent
        rulerContent.removeAllViews()
        for (i in 5..50) {
            val tickContainer = LinearLayout(this)
            tickContainer.orientation = LinearLayout.VERTICAL
            tickContainer.gravity = Gravity.CENTER_HORIZONTAL
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(15, 0, 15, 0)
            tickContainer.layoutParams = params
            
            val zoomVal = i / 10.0f
            val isLabeled = i == 5 || i == 10 || i == 20 || i == 50
            val isMajor = i % 5 == 0
            
            if (isLabeled) {
                val tv = TextView(this).apply {
                    text = if (i == 5) ".5x" else String.format(Locale.US, "%dx", zoomVal.toInt())
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                }
                tickContainer.addView(tv)
            } else {
                tickContainer.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 12) })
            }
            
            tickContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(3, if (isLabeled) 24 else if (isMajor) 16 else 10)
                setBackgroundColor(Color.WHITE)
            })
            rulerContent.addView(tickContainer)
        }

        viewBinding.zoomRulerScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (isSyncingRuler) return@setOnScrollChangeListener
            val totalScrollRange = rulerContent.width - viewBinding.zoomRulerScroll.width
            if (totalScrollRange > 0) {
                val progress = (scrollX.toFloat() / totalScrollRange).coerceIn(0f, 1f)
                val ratio = 0.5f + (progress * 4.5f)
                performZoom(ratio)
                resetHideRulerTimer()
            }
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val zoomButtons = listOf(viewBinding.zoom05, viewBinding.zoom1, viewBinding.zoom2)
        val zoomFactors = listOf(0.5f, 1.0f, 2.0f)
        
        for (i in zoomButtons.indices) {
            val btn = zoomButtons[i]
            val factor = zoomFactors[i]
            
            var startX = 0f
            var isDragging = false
            val longPressHandler = Handler(Looper.getMainLooper())
            val longPressRunnable = Runnable {
                if (viewBinding.zoomRulerContainer.visibility != View.VISIBLE) {
                    isDragging = true 
                    performZoom(factor)
                    syncRulerToRatio(factor)
                    showZoomRuler()
                }
            }

            btn.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        isDragging = false
                        longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startX
                        if (!isDragging && Math.abs(dx) > touchSlop) {
                            isDragging = true
                            longPressHandler.removeCallbacks(longPressRunnable)
                            if (viewBinding.zoomRulerContainer.visibility != View.VISIBLE) {
                                performZoom(factor)
                                syncRulerToRatio(factor)
                                showZoomRuler()
                            }
                        }
                        
                        if (isDragging || viewBinding.zoomRulerContainer.visibility == View.VISIBLE) {
                            val diffX = startX - event.rawX
                            viewBinding.zoomRulerScroll.scrollBy(diffX.toInt(), 0)
                            startX = event.rawX
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        if (!isDragging && viewBinding.zoomRulerContainer.visibility != View.VISIBLE) {
                            performZoom(factor)
                        }
                        v.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        viewBinding.viewFinder.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (isManualFocusActive) {
                    camera?.cameraControl?.cancelFocusAndMetering()
                    viewBinding.focusRing.visibility = View.GONE
                    isManualFocusActive = false
                } else {
                    val factory = viewBinding.viewFinder.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
                        .disableAutoCancel()
                        .build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                    viewBinding.focusRing.apply {
                        visibility = View.VISIBLE
                        x = event.x - (width / 2)
                        y = event.y - (height / 2)
                    }
                    isManualFocusActive = true
                }
                v.performClick()
                true
            } else {
                false
            }
        }
    }

    private fun showZoomRuler() {
        viewBinding.zoomShortcuts.visibility = View.GONE
        viewBinding.zoomRulerContainer.visibility = View.VISIBLE
        resetHideRulerTimer()
    }

    private fun resetHideRulerTimer() {
        hideRulerHandler.removeCallbacks(hideRulerRunnable)
        hideRulerHandler.postDelayed(hideRulerRunnable, 3000)
    }

    private fun performZoom(ratio: Float) {
        val minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 0.5f
        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10.0f
        val clampedRatio = ratio.coerceIn(minZoom, maxZoom)
        camera?.cameraControl?.setZoomRatio(clampedRatio)
        currentZoomRatio = clampedRatio
        viewBinding.textCurrentZoom.text = String.format(Locale.US, "%.1fx", clampedRatio)
    }

    private fun syncRulerToRatio(ratio: Float) {
        viewBinding.zoomRulerScroll.post {
            val totalScrollRange = viewBinding.zoomRulerContent.width - viewBinding.zoomRulerScroll.width
            if (totalScrollRange <= 0) return@post
            val progress = (ratio - 0.5f) / 4.5f
            val scrollX = (progress * totalScrollRange).toInt()
            isSyncingRuler = true
            viewBinding.zoomRulerScroll.scrollTo(scrollX, 0)
            isSyncingRuler = false
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val previewSize = getResolutionForQuality(currentQuality)
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(currentFps, currentFps)
            )
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }

            highlightRecorder?.stop()
            highlightRecorder = HighlightRecorder(
                previewSize.width,
                previewSize.height,
                getBitrateForQuality(currentQuality),
                currentFps,
                bufferDurationSec,
                audioLagMs
            )
            highlightRecorder?.start()

            val recorderPreview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(previewSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        ).build()
                )
                .build()
            
            recorderPreview.setSurfaceProvider(cameraExecutor) { request ->
                val surface = highlightRecorder?.inputSurface
                if (surface != null) {
                    request.provideSurface(surface, cameraExecutor) {}
                } else {
                    request.willNotProvideSurface()
                }
            }

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, recorderPreview)
                camera?.cameraControl?.setZoomRatio(currentZoomRatio)
                isManualFocusActive = false
                viewBinding.focusRing.visibility = View.GONE
            } catch(exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getResolutionForQuality(quality: Quality): Size {
        return when (quality) {
            Quality.UHD -> Size(3840, 2160)
            Quality.FHD -> Size(1920, 1080)
            Quality.HD -> Size(1280, 720)
            Quality.SD -> Size(640, 480)
            else -> Size(1920, 1080)
        }
    }

    private fun getBitrateForQuality(quality: Quality): Int {
        return when (quality) {
            Quality.UHD -> 40_000_000
            Quality.FHD -> 16_000_000
            Quality.HD -> 8_000_000
            Quality.SD -> 2_000_000
            else -> 10_000_000
        }
    }

    private fun toggleBuffering() {
        if (isBufferingEnabled) {
            isBufferingEnabled = false
            cleanupBufferingUI()
        } else {
            isBufferingEnabled = true
            bufferStartTimeTotal = System.currentTimeMillis()
            updateBufferingUI()
        }
    }

    private fun updateBufferingUI() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewBinding.btnToggleBuffer.setImageResource(R.drawable.ic_shutter_stop)
        viewBinding.btnSaveHighlight.isEnabled = true
        viewBinding.btnSaveHighlight.alpha = 1.0f
        viewBinding.textBufferTime.visibility = View.VISIBLE
        viewBinding.textBufferInactive.visibility = View.GONE
        handler.removeCallbacks(bufferTimerRunnable)
        handler.post(bufferTimerRunnable)
    }

    private fun saveHighlight() {
        if (highlightRecorder != null && !isSavingHighlightInProgress) {
            isSavingHighlightInProgress = true
            
            // Briefly change button color to blue for feedback
            viewBinding.btnSaveHighlight.setColorFilter(Color.parseColor("#2196F3"))
            handler.postDelayed({
                viewBinding.btnSaveHighlight.clearColorFilter()
            }, 300)

            val sensorOrientation = camera?.cameraInfo?.sensorRotationDegrees ?: 90
            val rotationHint = (sensorOrientation - currentUiRotation.toInt() + 360) % 360
            
            highlightRecorder?.saveHighlight(this, rotationHint) { _ ->
                runOnUiThread {
                    isSavingHighlightInProgress = false
                }
            }
        }
    }

    private fun cleanupBufferingUI() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewBinding.btnToggleBuffer.setImageResource(R.drawable.ic_shutter_buffer)
        viewBinding.btnSaveHighlight.isEnabled = false
        viewBinding.btnSaveHighlight.alpha = 0.3f
        viewBinding.textBufferTime.visibility = View.GONE
        viewBinding.textBufferInactive.visibility = View.VISIBLE
        handler.removeCallbacks(bufferTimerRunnable)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && allPermissionsGranted()) startCamera()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).let {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) it + Manifest.permission.WRITE_EXTERNAL_STORAGE else it
        }
    }
}
