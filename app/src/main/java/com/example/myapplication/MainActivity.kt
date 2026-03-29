package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.databinding.ActivityMainBinding
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService

    private var currentQuality = Quality.FHD
    private var currentFps = 60
    private var bufferDurationSec = 6
    
    private var currentSegmentStartTime: Long = 0
    private var bufferStartTimeTotal: Long = 0
    private var isBufferingEnabled = false
    private var isSavingHighlightInProgress = false
    
    private val bufferSegments = LinkedList<File>()
    
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        setupOrientationListener()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }

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
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener.disable()
        if (isBufferingEnabled) stopBuffering()
    }

    private fun updateUIForRotation(rotationDegrees: Float) {
        val viewsToRotate = mutableListOf<View>(
            viewBinding.btnSaveHighlight,
            viewBinding.textBufferTime,
            viewBinding.btnToggleBuffer,
            viewBinding.btnSettings,
            viewBinding.zoom05,
            viewBinding.zoom1,
            viewBinding.zoom2,
            viewBinding.textCurrentZoom
        )
        
        viewsToRotate.forEach { it.animate().rotation(rotationDegrees).setDuration(300).start() }
        
        videoCapture?.targetRotation = getDisplayRotationFromUiRotation(rotationDegrees)
    }

    private fun getDisplayRotationFromUiRotation(uiRotation: Float): Int {
        return when (uiRotation) {
            0f -> Surface.ROTATION_0
            90f -> Surface.ROTATION_90
            180f -> Surface.ROTATION_180
            270f -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
    }

    private fun setupControls() {
        viewBinding.btnToggleBuffer.setOnClickListener { toggleBuffering() }
        viewBinding.btnSaveHighlight.setOnClickListener { saveHighlight() }
        viewBinding.btnSettings.setOnClickListener {
            viewBinding.settingsPanel.visibility = if (viewBinding.settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun setupSettingsPanel() {
        viewBinding.bufferSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = progress + 2
                bufferDurationSec = duration
                viewBinding.textBufferDuration.text = String.format(Locale.US, "%ds", duration)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (isBufferingEnabled) {
                    stopBuffering()
                }
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Buffering remains stopped as requested to avoid crashes and allow safe duration adjustment
            }
        })
        viewBinding.bufferSlider.progress = bufferDurationSec - 2
        viewBinding.textBufferDuration.text = String.format(Locale.US, "%ds", bufferDurationSec)
        
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
                if (isBufferingEnabled) stopBuffering()
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
                if (isBufferingEnabled) stopBuffering()
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
                val factory = viewBinding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                
                camera?.cameraControl?.startFocusAndMetering(action)
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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(currentQuality))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(this, cameraSelector, preview, videoCapture)
                
                camera?.cameraControl?.setZoomRatio(currentZoomRatio)
                
                videoCapture?.targetRotation = getDisplayRotationFromUiRotation(currentUiRotation)

            } catch(exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleBuffering() {
        if (isBufferingEnabled) {
            stopBuffering()
        } else {
            isBufferingEnabled = true
            bufferStartTimeTotal = System.currentTimeMillis()
            startBuffering()
        }
    }

    private fun startBuffering() {
        val videoCapture = this.videoCapture ?: return
        if (!isBufferingEnabled) return
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val segmentFile = File(cacheDir, "segment_${System.currentTimeMillis()}.mp4")
        val fileOutputOptions = FileOutputOptions.Builder(segmentFile)
            .setDurationLimitMillis(60 * 1000L) 
            .build()

        recording = videoCapture.output
            .prepareRecording(this, fileOutputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        viewBinding.btnToggleBuffer.setImageResource(R.drawable.ic_shutter_stop)
                        viewBinding.btnSaveHighlight.isEnabled = true
                        viewBinding.btnSaveHighlight.alpha = 1.0f
                        viewBinding.textBufferTime.visibility = View.VISIBLE
                        currentSegmentStartTime = System.currentTimeMillis()
                        handler.post(bufferTimerRunnable)
                    }
                    is VideoRecordEvent.Finalize -> {
                        bufferSegments.add(segmentFile)
                        if (bufferSegments.size > 3) {
                            val oldest = bufferSegments.removeFirst()
                            if (oldest.exists()) oldest.delete()
                        }

                        if (isSavingHighlightInProgress) {
                            processHighlight()
                        } else if (isBufferingEnabled) {
                            startBuffering()
                        } else {
                            cleanupBufferingUI()
                        }
                    }
                }
            }
    }

    private fun stopBuffering() {
        isBufferingEnabled = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        recording?.stop()
        recording = null
        clearBufferCache()
        cleanupBufferingUI()
    }

    private fun saveHighlight() {
        if (recording != null && !isSavingHighlightInProgress) {
            isSavingHighlightInProgress = true
            recording?.stop() 
            Toast.makeText(this, "Capturing highlight...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processHighlight() {
        val filesToMerge = bufferSegments.toList()
        VideoTrimmer.mergeAndTrim(this, filesToMerge, bufferDurationSec) { uri ->
            runOnUiThread {
                if (uri != null) {
                    Toast.makeText(this, "Highlight Saved!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show()
                }
                
                clearBufferCache()
                bufferStartTimeTotal = System.currentTimeMillis()

                isSavingHighlightInProgress = false
                if (isBufferingEnabled) startBuffering()
            }
        }
    }

    private fun clearBufferCache() {
        bufferSegments.forEach { if (it.exists()) it.delete() }
        bufferSegments.clear()
    }

    private fun cleanupBufferingUI() {
        viewBinding.btnToggleBuffer.setImageResource(R.drawable.ic_shutter_buffer)
        viewBinding.btnSaveHighlight.isEnabled = false
        viewBinding.btnSaveHighlight.alpha = 0.3f
        viewBinding.textBufferTime.visibility = View.GONE
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
