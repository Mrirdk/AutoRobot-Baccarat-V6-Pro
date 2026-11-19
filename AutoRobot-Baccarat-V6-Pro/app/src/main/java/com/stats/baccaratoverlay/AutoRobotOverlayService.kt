package com.stats.baccaratoverlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class AutoRobotOverlayService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_V6_OVERLAY"
        const val ACTION_STOP = "ACTION_STOP_V6_OVERLAY"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        private const val CHANNEL_ID = "autorobot_v6_hybrid_channel"
        private const val NOTIF_ID = 6001

        // Standard Baccarat Big Road / Bead Plate grid dimensions inside ROI Target Box
        private const val GRID_ROWS = 6
        private const val GRID_COLS = 12
        private const val SCAN_INTERVAL_MS = 380L
        private const val STABILITY_REQUIRED = 3
    }

    private lateinit var windowManager: WindowManager
    private lateinit var coinManager: AccountCoinManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var toneGenerator: ToneGenerator? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 320

    // Overlay Views
    private var robotView: View? = null
    private var targetView: View? = null
    private lateinit var robotParams: WindowManager.LayoutParams
    private lateinit var targetParams: WindowManager.LayoutParams

    // UI References inside robotView
    private lateinit var ivOverlayRobot: ImageView
    private lateinit var viewOverlayRobotAura: View
    private lateinit var layoutRobotBubble: LinearLayout
    private lateinit var tvOverlayCoinBadge: TextView
    private lateinit var tvTimingBadge: TextView
    private lateinit var tvResultAnnouncement: TextView
    private lateinit var tvRobotStatus: TextView
    private lateinit var tvMainRoadStatus: TextView
    private lateinit var tvMarkovProb: TextView
    private lateinit var tvRecentBeads: TextView
    private lateinit var btnToggleScan: Button
    private lateinit var btnToggleTarget: Button
    private lateinit var btnResetRoad: Button

    // Scanning & Pattern State
    private var isAutoScanning = false
    private var isTargetLocked = false
    private var isOcrBusy = false

    private var stableGridSignature = ""
    private var stableGridHits = 0

    private val capturedHistory = mutableListOf<Char>()
    private var lastGridBeadCount = 0
    private var lastAlertedHandIndex = -1
    private var isBettingWindowOpen = true
    private var lastBannerResultToken = ""

    private var overlayRobotAnim: AnimatorSet? = null

    private val scanLoopRunnable = object : Runnable {
        override fun run() {
            if (isAutoScanning) {
                performLockedRoiAndTimingScan()
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        coinManager = AccountCoinManager(this)
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: Exception) {
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        startAndroid14ProjectionForeground()
        setupOverlayWindows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode == Activity.RESULT_OK && data != null) {
                    initMediaProjection(resultCode, data)
                }
            }
        }
        return START_STICKY
    }

    private fun startAndroid14ProjectionForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoRobot Baccarat V6 Pro Scanner",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoRobot Baccarat V6 Pro")
            .setContentText("Đang chạy chế độ soi cầu khóa khung ROI...")
            .setSmallIcon(R.drawable.ic_robot_cutout)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        releaseProjection()
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                isAutoScanning = false
                mainHandler.removeCallbacks(scanLoopRunnable)
            }
        }, mainHandler)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AutoRobotV6ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    private fun setupOverlayWindows() {
        val inflater = LayoutInflater.from(this)

        // 1. Scan Target ROI Box (Must be added first so Robot HUD stays above it)
        targetView = inflater.inflate(R.layout.scan_target_layout, null)
        targetParams = WindowManager.LayoutParams(
            dpToPx(250),
            dpToPx(125),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - dpToPx(250)) / 2
            y = (screenHeight * 0.62f).toInt()
        }
        windowManager.addView(targetView, targetParams)
        setupTargetDragAndResize()

        // 2. Floating Robot HUD Panel
        robotView = inflater.inflate(R.layout.robot_overlay_layout, null)
        robotParams = WindowManager.LayoutParams(
            dpToPx(290),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(14)
            y = dpToPx(64)
        }
        windowManager.addView(robotView, robotParams)

        bindOverlayViews()
        startOverlayRobotAnimations()
    }

    private fun bindOverlayViews() {
        val root = robotView ?: return
        ivOverlayRobot = root.findViewById(R.id.ivOverlayRobot)
        viewOverlayRobotAura = root.findViewById(R.id.viewOverlayRobotAura)
        layoutRobotBubble = root.findViewById(R.id.layoutRobotBubble)
        tvOverlayCoinBadge = root.findViewById(R.id.tvOverlayCoinBadge)
        tvTimingBadge = root.findViewById(R.id.tvTimingBadge)
        tvResultAnnouncement = root.findViewById(R.id.tvResultAnnouncement)
        tvRobotStatus = root.findViewById(R.id.tvRobotStatus)
        tvMainRoadStatus = root.findViewById(R.id.tvMainRoadStatus)
        tvMarkovProb = root.findViewById(R.id.tvMarkovProb)
        tvRecentBeads = root.findViewById(R.id.tvRecentBeads)
        btnToggleScan = root.findViewById(R.id.btnToggleScan)
        btnToggleTarget = root.findViewById(R.id.btnToggleTarget)
        btnResetRoad = root.findViewById(R.id.btnResetRoad)

        val user = coinManager.getCurrentUser()
        val coins = coinManager.getCoins(user)
        tvOverlayCoinBadge.text = if (coinManager.isAdmin(user)) "ADMIN ∞" else "$coins Xu"

        // Drag Robot Panel via Top Header
        val header = root.findViewById<View>(R.id.robotHeader)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = robotParams.x
                    initialY = robotParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    robotParams.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(0, screenWidth - dpToPx(120))
                    robotParams.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, screenHeight - dpToPx(120))
                    windowManager.updateViewLayout(robotView, robotParams)
                    true
                }
                else -> false
            }
        }

        root.findViewById<Button>(R.id.btnCloseOverlay).setOnClickListener {
            stopSelf()
        }

        btnToggleScan.setOnClickListener {
            isAutoScanning = !isAutoScanning
            if (isAutoScanning) {
                btnToggleScan.text = "TẠM DỪNG"
                btnToggleScan.setBackgroundResource(R.drawable.bg_btn_gold)
                btnToggleScan.setTextColor(Color.parseColor("#090D16"))
                tvTimingBadge.text = "🟢 ĐANG QUÉT KHUNG CẦU..."
                viewOverlayRobotAura.setBackgroundResource(R.drawable.bg_robot_aura_gold)
                mainHandler.removeCallbacks(scanLoopRunnable)
                mainHandler.post(scanLoopRunnable)
            } else {
                btnToggleScan.text = "BẮT ĐẦU"
                btnToggleScan.setBackgroundResource(R.drawable.bg_btn_emerald)
                btnToggleScan.setTextColor(Color.WHITE)
                tvTimingBadge.text = "⏸ ĐÃ TẠM DỪNG QUÉT"
                viewOverlayRobotAura.setBackgroundResource(R.drawable.bg_robot_aura)
                mainHandler.removeCallbacks(scanLoopRunnable)
            }
        }

        btnToggleTarget.setOnClickListener {
            isTargetLocked = !isTargetLocked
            val tView = targetView ?: return@setOnClickListener
            val label = tView.findViewById<TextView>(R.id.tvTargetLabel)
            val resizeBtn = tView.findViewById<View>(R.id.btnResizeHandle)

            if (isTargetLocked) {
                btnToggleTarget.text = "MỞ KHUNG"
                tView.setBackgroundResource(R.drawable.bg_scan_target_locked)
                label.text = "🔒 ĐÃ KHÓA KHUNG CẦU"
                label.setTextColor(Color.parseColor("#FFD166"))
                resizeBtn.visibility = View.GONE
                targetParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                btnToggleTarget.text = "KHÓA KHUNG"
                tView.setBackgroundResource(R.drawable.bg_scan_target)
                label.text = "KHUNG BẮT CẦU (KÉO & CHỈNH GÓC)"
                label.setTextColor(Color.parseColor("#00FF88"))
                resizeBtn.visibility = View.VISIBLE
                targetParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            windowManager.updateViewLayout(tView, targetParams)
        }

        btnResetRoad.setOnClickListener {
            capturedHistory.clear()
            lastGridBeadCount = 0
            lastAlertedHandIndex = -1
            stableGridSignature = ""
            stableGridHits = 0
            lastBannerResultToken = ""
            tvResultAnnouncement.text = "ĐÃ LÀM MỚI DỮ LIỆU BÀN MỚI"
            tvRobotStatus.text = "Sẵn sàng bắt cầu bàn mới!"
            tvMainRoadStatus.text = "Thế cầu: Đang chờ dữ liệu"
            tvMarkovProb.text = "Tỷ lệ: BANKER 50% | PLAYER 50%"
            tvRecentBeads.text = "Cầu gần nhất: --"
            playChimeAndVibrate(isAlert = false)
        }
    }

    private fun setupTargetDragAndResize() {
        val tView = targetView ?: return
        val resizeHandle = tView.findViewById<View>(R.id.btnResizeHandle)

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        tView.setOnTouchListener { _, event ->
            if (isTargetLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = targetParams.x
                    startY = targetParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    targetParams.x = (startX + (event.rawX - touchX).toInt())
                        .coerceIn(0, (screenWidth - targetParams.width).coerceAtLeast(0))
                    targetParams.y = (startY + (event.rawY - touchY).toInt())
                        .coerceIn(0, (screenHeight - targetParams.height).coerceAtLeast(0))
                    windowManager.updateViewLayout(tView, targetParams)
                    true
                }
                else -> false
            }
        }

        var startW = 0
        var startH = 0
        resizeHandle.setOnTouchListener { _, event ->
            if (isTargetLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = targetParams.width
                    startH = targetParams.height
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    targetParams.width = (startW + (event.rawX - touchX).toInt())
                        .coerceIn(dpToPx(130), screenWidth - targetParams.x)
                    targetParams.height = (startH + (event.rawY - touchY).toInt())
                        .coerceIn(dpToPx(70), screenHeight - targetParams.y)
                    windowManager.updateViewLayout(tView, targetParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun startOverlayRobotAnimations() {
        val floatY = ObjectAnimator.ofFloat(ivOverlayRobot, View.TRANSLATION_Y, -4f, 4f).apply {
            duration = 1400L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val auraScaleX = ObjectAnimator.ofFloat(viewOverlayRobotAura, View.SCALE_X, 0.88f, 1.16f).apply {
            duration = 1100L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        val auraScaleY = ObjectAnimator.ofFloat(viewOverlayRobotAura, View.SCALE_Y, 0.88f, 1.16f).apply {
            duration = 1100L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        overlayRobotAnim = AnimatorSet().apply {
            playTogether(floatY, auraScaleX, auraScaleY)
            start()
        }
    }

    private fun triggerRobotAlertBounce() {
        ivOverlayRobot.animate()
            .scaleX(1.28f)
            .scaleY(1.28f)
            .rotationBy(18f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                ivOverlayRobot.animate().scaleX(1.0f).scaleY(1.0f).rotation(0f).setDuration(200L).start()
            }
            .start()

        layoutRobotBubble.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pop_in))
        tvResultAnnouncement.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pop_in))
    }

    /**
     * Core V6 Fix:
     * 1. Crops strictly inside the user's ROI target box (no random full-screen noise).
     * 2. Quantizes 6x12 grid cells by central RGB color dominance (Red=B, Blue=P, Green=T).
     * 3. Requires 3 consecutive identical frames (3-frame debounce) before updating road.
     * 4. Runs bilingual OCR on center table banner to detect exact betting timing & round results.
     */
    private fun performLockedRoiAndTimingScan() {
        val reader = imageReader ?: return
        val tView = targetView ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val fullBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            // 1. Strict ROI Crop from Target Box Screen Location
            val loc = IntArray(2)
            tView.getLocationOnScreen(loc)
            val roiLeft = loc[0].coerceIn(0, fullBitmap.width - 10)
            val roiTop = loc[1].coerceIn(0, fullBitmap.height - 10)
            val roiWidth = tView.width.coerceIn(10, fullBitmap.width - roiLeft)
            val roiHeight = tView.height.coerceIn(10, fullBitmap.height - roiTop)

            val roiBitmap = Bitmap.createBitmap(fullBitmap, roiLeft, roiTop, roiWidth, roiHeight)
            val gridBeads = extractOrderedBeadsFromGrid(roiBitmap)
            roiBitmap.recycle()

            // 3-Frame Stability Consensus Lock
            val signature = gridBeads.joinToString("")
            if (signature == stableGridSignature) {
                stableGridHits++
            } else {
                stableGridSignature = signature
                stableGridHits = 1
            }

            if (stableGridHits == STABILITY_REQUIRED && gridBeads.size >= 2) {
                processStableRoadmapSnapshot(gridBeads)
            }

            // 2. Center Banner Bilingual OCR for Exact Result & Betting Window Timing
            if (!isOcrBusy) {
                isOcrBusy = true
                val bannerTop = (screenHeight * 0.24f).toInt().coerceIn(0, fullBitmap.height - 20)
                val bannerHeight = (screenHeight * 0.34f).toInt().coerceIn(20, fullBitmap.height - bannerTop)
                val bannerLeft = (screenWidth * 0.12f).toInt().coerceIn(0, fullBitmap.width - 20)
                val bannerWidth = (screenWidth * 0.76f).toInt().coerceIn(20, fullBitmap.width - bannerLeft)

                val bannerBitmap = Bitmap.createBitmap(fullBitmap, bannerLeft, bannerTop, bannerWidth, bannerHeight)
                runCenterBannerOcr(bannerBitmap)
            }

            fullBitmap.recycle()
        } catch (_: Exception) {
            isOcrBusy = false
        } finally {
            image.close()
        }
    }

    /**
     * Samples the central 40% area of each cell in a 6-row x 12-col grid,
     * reading top-to-bottom per column (standard Baccarat Big Road / Bead Plate order).
     */
    private fun extractOrderedBeadsFromGrid(roi: Bitmap): List<Char> {
        val cellW = roi.width.toFloat() / GRID_COLS
        val cellH = roi.height.toFloat() / GRID_ROWS
        val orderedBeads = mutableListOf<Char>()

        for (col in 0 until GRID_COLS) {
            for (row in 0 until GRID_ROWS) {
                val startX = (col * cellW + cellW * 0.30f).toInt().coerceIn(0, roi.width - 1)
                val endX = (col * cellW + cellW * 0.70f).toInt().coerceIn(startX + 1, roi.width)
                val startY = (row * cellH + cellH * 0.30f).toInt().coerceIn(0, roi.height - 1)
                val endY = (row * cellH + cellH * 0.70f).toInt().coerceIn(startY + 1, roi.height)

                var redHits = 0
                var blueHits = 0
                var greenHits = 0
                var totalSamples = 0

                var y = startY
                while (y < endY) {
                    var x = startX
                    while (x < endX) {
                        val px = roi.getPixel(x, y)
                        val r = Color.red(px)
                        val g = Color.green(px)
                        val b = Color.blue(px)
                        totalSamples++

                        // Classify vibrant Banker Red, Player Blue, Tie Green
                        if (r > 145 && r - g > 55 && r - b > 55) {
                            redHits++
                        } else if (b > 145 && b - r > 55 && b - g > 30) {
                            blueHits++
                        } else if (g > 135 && g - r > 45 && g - b > 45) {
                            greenHits++
                        }
                        x += 2
                    }
                    y += 2
                }

                if (totalSamples > 0) {
                    val threshold = (totalSamples * 0.22f).toInt().coerceAtLeast(1)
                    val bead = when {
                        redHits >= threshold && redHits > blueHits && redHits > greenHits -> 'B'
                        blueHits >= threshold && blueHits > redHits && blueHits > greenHits -> 'P'
                        greenHits >= threshold && greenHits > redHits && greenHits > blueHits -> 'T'
                        else -> null
                    }
                    if (bead != null) {
                        orderedBeads.add(bead)
                    }
                }
            }
        }
        return orderedBeads
    }

    private fun processStableRoadmapSnapshot(gridBeads: List<Char>) {
        val isNewHandAdded = gridBeads.size > lastGridBeadCount && lastGridBeadCount > 0
        val isInitialLoad = lastGridBeadCount == 0 && gridBeads.isNotEmpty()

        // Sync capturedHistory cleanly with the locked ROI grid
        capturedHistory.clear()
        capturedHistory.addAll(gridBeads)
        lastGridBeadCount = gridBeads.size

        val report = BaccaratV6HybridEngine.evaluate(capturedHistory)
        updateHudWithReport(report)

        if (isNewHandAdded) {
            val newestBead = capturedHistory.last()
            announceRoundResult(newestBead, report)
        } else if (isInitialLoad && report.recommendedSide != "WAIT") {
            tvResultAnnouncement.text = "ĐÃ BẮT ${capturedHistory.size} TAY CẦU TRÊN BẢNG"
            if (isBettingWindowOpen && lastAlertedHandIndex != capturedHistory.size) {
                lastAlertedHandIndex = capturedHistory.size
                playChimeAndVibrate(isAlert = true)
                triggerRobotAlertBounce()
            }
        }
    }

    private fun runCenterBannerOcr(bannerBitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bannerBitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text.uppercase()

                // 1. Detect Betting Phase Timing ("Báo đúng thời điểm vào lệnh")
                val bettingOpenKeywords = listOf(
                    "PLACE YOUR BET", "ĐẶT CƯỢC", "DAT CUOC",
                    "BẮT ĐẦU CƯỢC", "BAT DAU CUOC", "NEW GAME", "VÁN MỚI", "VAN MOI"
                )
                val bettingClosedKeywords = listOf(
                    "NO MORE BET", "NGỪNG CƯỢC", "NGUNG CUOC",
                    "CHIA BÀI", "CHIA BAI", "DEALING"
                )

                if (bettingOpenKeywords.any { rawText.contains(it) }) {
                    if (!isBettingWindowOpen) {
                        isBettingWindowOpen = true
                        tvTimingBadge.text = "🟢 MỞ CƯỢC — THỜI ĐIỂM VÀO LỆNH!"
                        tvTimingBadge.setTextColor(Color.parseColor("#10B981"))

                        val report = BaccaratV6HybridEngine.evaluate(capturedHistory)
                        if (report.recommendedSide != "WAIT" && lastAlertedHandIndex != capturedHistory.size) {
                            lastAlertedHandIndex = capturedHistory.size
                            playChimeAndVibrate(isAlert = true)
                            triggerRobotAlertBounce()
                        }
                    }
                } else if (bettingClosedKeywords.any { rawText.contains(it) }) {
                    isBettingWindowOpen = false
                    tvTimingBadge.text = "🔴 ĐÓNG CƯỢC — CHỜ KẾT QUẢ..."
                    tvTimingBadge.setTextColor(Color.parseColor("#EF4444"))
                }

                // 2. Bilingual Result Banner Detection ("Hiện kết quả là báo ngay")
                val detectedOutcome: Char? = when {
                    rawText.contains("BANKER WIN") || rawText.contains("NHÀ CÁI THẮNG") ||
                            rawText.contains("NHA CAI THANG") || rawText.contains("CÁI THẮNG") -> 'B'
                    rawText.contains("PLAYER WIN") || rawText.contains("NHÀ CON THẮNG") ||
                            rawText.contains("NHA CON THANG") || rawText.contains("CON THẮNG") -> 'P'
                    rawText.contains("TIE WIN") || rawText.contains("HÒA") || rawText.contains("HOA") -> 'T'
                    else -> null
                }

                if (detectedOutcome != null) {
                    val token = "${capturedHistory.size}_$detectedOutcome"
                    if (token != lastBannerResultToken) {
                        lastBannerResultToken = token
                        val report = BaccaratV6HybridEngine.evaluate(capturedHistory)
                        announceRoundResult(detectedOutcome, report)
                    }
                }
            }
            .addOnCompleteListener {
                bannerBitmap.recycle()
                isOcrBusy = false
            }
    }

    private fun announceRoundResult(outcome: Char, report: V6HybridReport) {
        val label = when (outcome) {
            'B' -> "🔴 KẾT QUẢ: BANKER (NHÀ CÁI) THẮNG!"
            'P' -> "🔵 KẾT QUẢ: PLAYER (NHÀ CON) THẮNG!"
            'T' -> "🟢 KẾT QUẢ: TIE (HÒA)!"
            else -> "KẾT QUẢ MỚI!"
        }
        tvResultAnnouncement.text = label
        tvTimingBadge.text = "🟢 VỪA RA KẾT QUẢ → VÀO LỆNH TIẾP!"
        tvTimingBadge.setTextColor(Color.parseColor("#FFD166"))

        updateHudWithReport(report)
        playChimeAndVibrate(isAlert = true)
        triggerRobotAlertBounce()
    }

    private fun updateHudWithReport(report: V6HybridReport) {
        tvRobotStatus.text = report.robotCallout
        tvMainRoadStatus.text = "${report.patternName} | ${report.derivedRoadStatusText}"
        tvMarkovProb.text = "Tỷ lệ: BANKER ${report.probB}% | PLAYER ${report.probP}%"

        val recentStr = if (capturedHistory.isEmpty()) {
            "--"
        } else {
            capturedHistory.takeLast(10).joinToString(" → ")
        }
        tvRecentBeads.text = "10 tay gần nhất (${capturedHistory.size}): $recentStr"
    }

    private fun playChimeAndVibrate(isAlert: Boolean) {
        try {
            if (isAlert) {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 260)
            } else {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
            }
        } catch (_: Exception) {
        }

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(
                VibrationEffect.createOneShot(if (isAlert) 240L else 90L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (_: Exception) {
        }
    }

    private fun releaseProjection() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        isAutoScanning = false
        mainHandler.removeCallbacks(scanLoopRunnable)
        overlayRobotAnim?.cancel()
        toneGenerator?.release()
        releaseProjection()
        robotView?.let { windowManager.removeView(it) }
        targetView?.let { windowManager.removeView(it) }
        super.onDestroy()
    }
}
