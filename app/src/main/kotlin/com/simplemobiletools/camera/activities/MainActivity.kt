package com.simplemobiletools.camera.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.view.*
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.transition.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.tabs.TabLayout
import com.simplemobiletools.camera.BuildConfig
import com.simplemobiletools.camera.R
import com.simplemobiletools.camera.databinding.ActivityMainBinding
import com.simplemobiletools.camera.extensions.config
import com.simplemobiletools.camera.extensions.fadeIn
import com.simplemobiletools.camera.extensions.fadeOut
import com.simplemobiletools.camera.extensions.setShadowIcon
import com.simplemobiletools.camera.extensions.toFlashModeId
import com.simplemobiletools.camera.helpers.*
import com.simplemobiletools.camera.implementations.CameraXInitializer
import com.simplemobiletools.camera.implementations.CameraXPreviewListener
import com.simplemobiletools.camera.interfaces.MyPreview
import com.simplemobiletools.camera.models.ResolutionOption
import com.simplemobiletools.camera.models.TimerMode
import com.simplemobiletools.camera.views.FocusCircleView
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.Release
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : SimpleActivity(), PhotoProcessor.MediaSavedListener, CameraXPreviewListener {
    private companion object {
        const val CAPTURE_ANIMATION_DURATION = 500L
        const val PHOTO_MODE_INDEX = 1
        const val VIDEO_MODE_INDEX = 0
        private const val MIN_SWIPE_DISTANCE_X = 100
        private const val TIMER_2_SECONDS = 2001
    }

    private lateinit var defaultScene: Scene
    private lateinit var flashModeScene: Scene
    private lateinit var timerScene: Scene
    private lateinit var mOrientationEventListener: OrientationEventListener
    private lateinit var mFocusCircleView: FocusCircleView
    private lateinit var mediaSoundHelper: MediaSoundHelper
    private lateinit var binding: ActivityMainBinding
    private var mPreview: MyPreview? = null
    private var mediaSizeToggleGroup: MaterialButtonToggleGroup? = null
    private var mPreviewUri: Uri? = null
    private var mIsHardwareShutterHandled = false
    private var mLastHandledOrientation = 0
    private var countDownTimer: CountDownTimer? = null

    private val tabSelectedListener = object : TabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            handlePermission(PERMISSION_RECORD_AUDIO) {
                if (it) {
                    when (tab.position) {
                        VIDEO_MODE_INDEX -> mPreview?.initVideoMode()
                        PHOTO_MODE_INDEX -> mPreview?.initPhotoMode()
                        else -> throw IllegalStateException("Unsupported tab position ${tab.position}")
                    }
                } else {
                    toast(R.string.no_audio_permissions)
                    selectPhotoTab()
                    if (isVideoCaptureIntent()) {
                        finish()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        useDynamicTheme = false
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        appLaunched(BuildConfig.APPLICATION_ID)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        initVariables()
        tryInitCamera()
        supportActionBar?.hide()
        checkWhatsNewDialog()
        setupOrientationEventListener()

        val windowInsetsController = ViewCompat.getWindowInsetsController(window.decorView)
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController?.hide(WindowInsetsCompat.Type.statusBars())

        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasStorageAndCameraPermissions()) {
            val isInPhotoMode = isInPhotoMode()
            setupPreviewImage(isInPhotoMode)
            mFocusCircleView.setStrokeColor(getProperPrimaryColor())
            toggleActionButtons(enabled = true)
            mOrientationEventListener.enable()
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ensureTransparentNavigationBar()

        if (ViewCompat.getWindowInsetsController(window.decorView) == null) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isAskingPermissions) {
            cancelTimer()
        }

        if (!hasStorageAndCameraPermissions() || isAskingPermissions) {
            return
        }

        mOrientationEventListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        mPreview = null
        mediaSoundHelper.release()
    }

    override fun onBackPressed() {
        if (!closeOptions()) {
            super.onBackPressed()
        }
    }

    private fun selectPhotoTab(triggerListener: Boolean = false) {
        if (!triggerListener) {
            removeTabListener()
        }

        binding.cameraModeTab.getTabAt(PHOTO_MODE_INDEX)?.select()
        setTabListener()
    }

    private fun selectVideoTab(triggerListener: Boolean = false) {
        if (!triggerListener) {
            removeTabListener()
        }
        binding.cameraModeTab.getTabAt(VIDEO_MODE_INDEX)?.select()
        setTabListener()
    }

    private fun setTabListener() {
        binding.cameraModeTab.addOnTabSelectedListener(tabSelectedListener)
    }

    private fun removeTabListener() {
        binding.cameraModeTab.removeOnTabSelectedListener(tabSelectedListener)
    }

    private fun ensureTransparentNavigationBar() {
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.transparent)
    }

    private fun initVariables() {
        mIsHardwareShutterHandled = false
        mediaSoundHelper = MediaSoundHelper(this)
        mediaSoundHelper.loadSounds()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_CAMERA && !mIsHardwareShutterHandled) {
            mIsHardwareShutterHandled = true
            shutterPressed()
            true
        } else if (!mIsHardwareShutterHandled && config.volumeButtonsAsShutter && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            mIsHardwareShutterHandled = true
            shutterPressed()
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            mIsHardwareShutterHandled = false
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun hideIntentButtons() {
        binding.cameraModeHolder.beGone()
        binding.layoutTop.settings.beGone()
        binding.lastPhotoVideoPreview.beInvisible()
    }

    private fun tryInitCamera() {
        handlePermission(PERMISSION_CAMERA) { grantedCameraPermission ->
            if (grantedCameraPermission) {
                handleStoragePermission { grantedStoragePermission ->
                    if (grantedStoragePermission) {
                        val isInPhotoMode = isInPhotoMode()
                        if (isInPhotoMode) {
                            initializeCamera(true)
                        } else {
                            handlePermission(PERMISSION_RECORD_AUDIO) { grantedRecordAudioPermission ->
                                if (grantedRecordAudioPermission) {
                                    initializeCamera(false)
                                } else {
                                    toast(R.string.no_audio_permissions)
                                    if (isThirdPartyIntent()) {
                                        finish()
                                    } else {
                                        // re-initialize in photo mode
                                        config.initPhotoMode = true
                                        tryInitCamera()
                                    }
                                }
                            }
                        }
                    } else {
                        toast(R.string.no_storage_permissions)
                        finish()
                    }
                }
            } else {
                toast(R.string.no_camera_permissions)
                finish()
            }
        }
    }

    private fun isInPhotoMode(): Boolean {
        return mPreview?.isInPhotoMode() ?: if (isVideoCaptureIntent()) {
            false
        } else if (isImageCaptureIntent()) {
            true
        } else {
            config.initPhotoMode
        }
    }

    private fun handleStoragePermission(callback: (granted: Boolean) -> Unit) {
        if (isTiramisuPlus()) {
            handlePermission(PERMISSION_READ_MEDIA_IMAGES) { grantedReadImages ->
                if (grantedReadImages) {
                    handlePermission(PERMISSION_READ_MEDIA_VIDEO, callback)
                } else {
                    callback.invoke(false)
                }
            }
        } else {
            handlePermission(PERMISSION_WRITE_STORAGE, callback)
        }
    }

    private fun isThirdPartyIntent() = isVideoCaptureIntent() || isImageCaptureIntent()

    private fun isImageCaptureIntent(): Boolean = intent?.action == MediaStore.ACTION_IMAGE_CAPTURE || intent?.action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE

    private fun isVideoCaptureIntent(): Boolean = intent?.action == MediaStore.ACTION_VIDEO_CAPTURE

    private fun createToggleGroup(): MaterialButtonToggleGroup {
        return MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun initializeCamera(isInPhotoMode: Boolean) {
        setContentView(binding.root)
        initButtons()
        initModeSwitcher()
        defaultScene = Scene(binding.topOptions, binding.layoutTop.defaultIcons)
        flashModeScene = Scene(binding.topOptions, binding.layoutFlash.flashToggleGroup)
        timerScene = Scene(binding.topOptions, binding.layoutTimer.timerToggleGroup)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.viewHolder) { _, windowInsets ->
            val safeInsetBottom = windowInsets.displayCutout?.safeInsetBottom ?: 0
            val safeInsetTop = windowInsets.displayCutout?.safeInsetTop ?: 0

            binding.topOptions.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = safeInsetTop
            }

            val marginBottom = safeInsetBottom + navigationBarHeight + resources.getDimensionPixelSize(R.dimen.bigger_margin)

            binding.shutter.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = marginBottom
            }

            WindowInsetsCompat.CONSUMED
        }

        if (isInPhotoMode) {
            selectPhotoTab()
        } else {
            selectVideoTab()
        }

        val outputUri = intent.extras?.get(MediaStore.EXTRA_OUTPUT) as? Uri
        val isThirdPartyIntent = isThirdPartyIntent()
        mPreview = CameraXInitializer(this).createCameraXPreview(
            binding.previewView,
            listener = this,
            mediaSoundHelper = mediaSoundHelper,
            outputUri = outputUri,
            isThirdPartyIntent = isThirdPartyIntent,
            initInPhotoMode = isInPhotoMode,
        )

        mFocusCircleView = FocusCircleView(this).apply {
            id = View.generateViewId()
        }
        binding.viewHolder.addView(mFocusCircleView)

        setupPreviewImage(true)
        initFlashModeTransitionNames()
        initTimerModeTransitionNames()

        if (isThirdPartyIntent) {
            hideIntentButtons()
        }
    }

    private fun initFlashModeTransitionNames() {
        val baseName = getString(R.string.toggle_flash)
        binding.layoutFlash.flashAuto.transitionName = "$baseName$FLASH_AUTO"
        binding.layoutFlash.flashOff.transitionName = "$baseName$FLASH_OFF"
        binding.layoutFlash.flashOn.transitionName = "$baseName$FLASH_ON"
        binding.layoutFlash.flashAlwaysOn.transitionName = "$baseName$FLASH_ALWAYS_ON"
    }

    private fun initTimerModeTransitionNames() {
        val baseName = getString(R.string.toggle_timer)
        binding.layoutTimer.timerOff.transitionName = "$baseName${TimerMode.OFF.name}"
        binding.layoutTimer.timer3s.transitionName = "$baseName${TimerMode.TIMER_3.name}"
        binding.layoutTimer.timer5s.transitionName = "$baseName${TimerMode.TIMER_5.name}"
        binding.layoutTimer.timer10S.transitionName = "$baseName${TimerMode.TIMER_10.name}"
    }

    private fun initButtons() {
        binding.timerText.setFactory { layoutInflater.inflate(R.layout.timer_text, null) }
        binding.toggleCamera.setOnClickListener { mPreview!!.toggleFrontBackCamera() }
        binding.lastPhotoVideoPreview.setOnClickListener { showLastMediaPreview() }
        binding.layoutTop.toggleFlash.setOnClickListener { mPreview!!.handleFlashlightClick() }
        binding.layoutTop.toggleTimer.setOnClickListener {
            val transitionSet = createTransition()
            TransitionManager.go(timerScene, transitionSet)
            binding.layoutTimer.timerToggleGroup.beVisible()
            binding.layoutTimer.timerToggleGroup.check(config.timerMode.getTimerModeResId())
            binding.layoutTimer.timerToggleGroup.children.forEach { setButtonColors(it as MaterialButton) }
        }
        binding.shutter.setOnClickListener { shutterPressed() }

        binding.layoutTop.settings.setShadowIcon(R.drawable.ic_settings_vector)
        binding.layoutTop.settings.setOnClickListener { launchSettings() }

        binding.layoutTop.changeResolution.setOnClickListener { mPreview?.showChangeResolution() }

        binding.layoutFlash.flashOn.setShadowIcon(R.drawable.ic_flash_on_vector)
        binding.layoutFlash.flashOn.setOnClickListener { selectFlashMode(FLASH_ON) }

        binding.layoutFlash.flashOff.setShadowIcon(R.drawable.ic_flash_off_vector)
        binding.layoutFlash.flashOff.setOnClickListener { selectFlashMode(FLASH_OFF) }

        binding.layoutFlash.flashAuto.setShadowIcon(R.drawable.ic_flash_auto_vector)
        binding.layoutFlash.flashAuto.setOnClickListener { selectFlashMode(FLASH_AUTO) }

        binding.layoutFlash.flashAlwaysOn.setShadowIcon(R.drawable.ic_flashlight_vector)
        binding.layoutFlash.flashAlwaysOn.setOnClickListener { selectFlashMode(FLASH_ALWAYS_ON) }

        binding.layoutTimer.timerOff.setShadowIcon(R.drawable.ic_timer_off_vector)
        binding.layoutTimer.timerOff.setOnClickListener { selectTimerMode(TimerMode.OFF) }

        binding.layoutTimer.timer3s.setShadowIcon(R.drawable.ic_timer_3_vector)
        binding.layoutTimer.timer3s.setOnClickListener { selectTimerMode(TimerMode.TIMER_3) }

        binding.layoutTimer.timer5s.setShadowIcon(R.drawable.ic_timer_5_vector)
        binding.layoutTimer.timer5s.setOnClickListener { selectTimerMode(TimerMode.TIMER_5) }

        binding.layoutTimer.timer10S.setShadowIcon(R.drawable.ic_timer_10_vector)
        binding.layoutTimer.timer10S.setOnClickListener { selectTimerMode(TimerMode.TIMER_10) }
        setTimerModeIcon(config.timerMode)
    }

    private fun selectTimerMode(timerMode: TimerMode) {
        config.timerMode = timerMode
        setTimerModeIcon(timerMode)
        closeOptions()
    }

    private fun setTimerModeIcon(timerMode: TimerMode) {
        binding.layoutTop.toggleTimer.setShadowIcon(timerMode.getTimerModeDrawableRes())
        binding.layoutTop.toggleTimer.transitionName = "${getString(R.string.toggle_timer)}${timerMode.name}"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initModeSwitcher() {
        val gestureDetector = GestureDetectorCompat(this, object : GestureDetectorListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // we have to return true here so ACTION_UP (and onFling) can be dispatched
                return true
            }

            override fun onFling(event1: MotionEvent?, event2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
                if (event1 == null || event2 == null) {
                    return true
                }

                val deltaX = event1.x - event2.x
                val deltaXAbs = abs(deltaX)

                if (deltaXAbs >= MIN_SWIPE_DISTANCE_X) {
                    if (deltaX > 0) {
                        onSwipeLeft()
                    } else {
                        onSwipeRight()
                    }
                }

                return true
            }
        })

        binding.cameraModeTab.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun onSwipeLeft() {
        if (!isThirdPartyIntent() && binding.cameraModeHolder.isVisible()) {
            selectPhotoTab(triggerListener = true)
        }
    }

    private fun onSwipeRight() {
        if (!isThirdPartyIntent() && binding.cameraModeHolder.isVisible()) {
            selectVideoTab(triggerListener = true)
        }
    }

    private fun selectFlashMode(flashMode: Int) {
        closeOptions()
        mPreview?.setFlashlightState(flashMode)
    }


    private fun showLastMediaPreview() {
        if (mPreviewUri != null) {
            val path = applicationContext.getRealPathFromURI(mPreviewUri!!) ?: mPreviewUri!!.toString()
            openPathIntent(path, false, BuildConfig.APPLICATION_ID)
        }
    }

    private fun shutterPressed() {
        if (countDownTimer != null) {
            cancelTimer()
        } else if (isInPhotoMode()) {
            val timerMode = config.timerMode
            if (timerMode == TimerMode.OFF) {
                mPreview?.tryTakePicture()
            } else {
                scheduleTimer(timerMode)
            }
        } else {
            mPreview?.toggleRecording()
        }
    }

    private fun cancelTimer() {
        mediaSoundHelper.stopTimerCountdown2SecondsSound()
        countDownTimer?.cancel()
        countDownTimer = null
        resetViewsOnTimerFinish()
    }

    private fun launchSettings() {
        val intent = Intent(applicationContext, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onInitPhotoMode() {
        binding.shutter.setImageResource(R.drawable.ic_shutter_animated)
        binding.layoutTop.toggleTimer.beVisible()
        binding.layoutTop.toggleTimer.fadeIn()
        setupPreviewImage(true)
        selectPhotoTab()
    }

    override fun onInitVideoMode() {
        binding.shutter.setImageResource(R.drawable.ic_video_rec_animated)
        binding.layoutTop.toggleTimer.fadeOut()
        binding.layoutTop.toggleTimer.beGone()
        setupPreviewImage(false)
        selectVideoTab()
    }

    private fun setupPreviewImage(isPhoto: Boolean) {
        val uri = if (isPhoto) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val lastMediaId = getLatestMediaId(uri)
        if (lastMediaId == 0L) {
            return
        }

        mPreviewUri = Uri.withAppendedPath(uri, lastMediaId.toString())

        loadLastTakenMedia(mPreviewUri)
    }

    private fun loadLastTakenMedia(uri: Uri?) {
        mPreviewUri = uri
        runOnUiThread {
            if (!isDestroyed) {
                val options = RequestOptions()
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.NONE)

                Glide.with(this)
                    .load(uri)
                    .apply(options)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(binding.lastPhotoVideoPreview)
            }
        }
    }

    private fun hasStorageAndCameraPermissions(): Boolean {
        return if (isInPhotoMode()) hasPhotoModePermissions() else hasVideoModePermissions()
    }

    private fun hasPhotoModePermissions(): Boolean {
        return if (isTiramisuPlus()) {
            hasPermission(PERMISSION_READ_MEDIA_IMAGES) && hasPermission(PERMISSION_READ_MEDIA_VIDEO) && hasPermission(PERMISSION_CAMERA)
        } else {
            hasPermission(PERMISSION_WRITE_STORAGE) && hasPermission(PERMISSION_CAMERA)
        }
    }

    private fun hasVideoModePermissions(): Boolean {
        return if (isTiramisuPlus()) {
            hasPermission(PERMISSION_READ_MEDIA_VIDEO) && hasPermission(PERMISSION_CAMERA) && hasPermission(PERMISSION_RECORD_AUDIO)
        } else {
            hasPermission(PERMISSION_WRITE_STORAGE) && hasPermission(PERMISSION_CAMERA) && hasPermission(PERMISSION_RECORD_AUDIO)
        }
    }

    private fun setupOrientationEventListener() {
        mOrientationEventListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                if (isDestroyed) {
                    mOrientationEventListener.disable()
                    return
                }

                val currOrient = when (orientation) {
                    in 75..134 -> ORIENT_LANDSCAPE_RIGHT
                    in 225..289 -> ORIENT_LANDSCAPE_LEFT
                    else -> ORIENT_PORTRAIT
                }

                if (currOrient != mLastHandledOrientation) {
                    val degrees = when (currOrient) {
                        ORIENT_LANDSCAPE_LEFT -> 90
                        ORIENT_LANDSCAPE_RIGHT -> -90
                        else -> 0
                    }

                    animateViews(degrees)
                    mLastHandledOrientation = currOrient
                }
            }
        }
    }

    private fun animateViews(degrees: Int) {
        val views = arrayOf<View>(
            binding.toggleCamera,
            binding.layoutTop.toggleFlash,
            binding.layoutTop.changeResolution,
            binding.shutter,
            binding.layoutTop.settings,
            binding.lastPhotoVideoPreview
        )
        for (view in views) {
            rotate(view, degrees)
        }
    }

    private fun rotate(view: View, degrees: Int) = view.animate().rotation(degrees.toFloat()).start()

    override fun setHasFrontAndBackCamera(hasFrontAndBack: Boolean) {
        binding.toggleCamera?.beVisibleIf(hasFrontAndBack)
    }

    override fun setFlashAvailable(available: Boolean) {
        if (available) {
            binding.layoutTop.toggleFlash.beVisible()
        } else {
            binding.layoutTop.toggleFlash.beGone()
            mPreview?.setFlashlightState(FLASH_OFF)
        }
    }

    override fun onChangeCamera(frontCamera: Boolean) {
        binding.toggleCamera.setImageResource(if (frontCamera) R.drawable.ic_camera_rear_vector else R.drawable.ic_camera_front_vector)
    }

    override fun onPhotoCaptureStart() {
        toggleActionButtons(enabled = false)
    }

    override fun onPhotoCaptureEnd() {
        toggleActionButtons(enabled = true)
    }

    private fun toggleActionButtons(enabled: Boolean) {
        runOnUiThread {
            binding.shutter.isClickable = enabled
            binding.previewView.isEnabled = enabled
            binding.layoutTop.changeResolution.isEnabled = enabled
            binding.toggleCamera.isClickable = enabled
            binding.layoutTop.toggleFlash.isClickable = enabled
        }
    }

    override fun shutterAnimation() {
        binding.shutterAnimation.alpha = 1.0f
        binding.shutterAnimation.animate().alpha(0f).setDuration(CAPTURE_ANIMATION_DURATION).start()
    }

    override fun onMediaSaved(uri: Uri) {
        binding.layoutTop.changeResolution.isEnabled = true
        loadLastTakenMedia(uri)
        if (isImageCaptureIntent()) {
            Intent().apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        } else if (isVideoCaptureIntent()) {
            Intent().apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        }
    }

    override fun onImageCaptured(bitmap: Bitmap) {
        if (isImageCaptureIntent()) {
            Intent().apply {
                putExtra("data", bitmap)
                setResult(Activity.RESULT_OK, this)
            }
            finish()
        }
    }

    override fun onChangeFlashMode(flashMode: Int) {
        val flashDrawable = when (flashMode) {
            FLASH_OFF -> R.drawable.ic_flash_off_vector
            FLASH_ON -> R.drawable.ic_flash_on_vector
            FLASH_AUTO -> R.drawable.ic_flash_auto_vector
            else -> R.drawable.ic_flashlight_vector
        }
        binding.layoutTop.toggleFlash.setShadowIcon(flashDrawable)
        binding.layoutTop.toggleFlash.transitionName = "${getString(R.string.toggle_flash)}$flashMode"
    }

    override fun onVideoRecordingStarted() {
        binding.cameraModeHolder.beInvisible()
        binding.videoRecCurrTimer.beVisible()

        binding.toggleCamera.fadeOut()
        binding.lastPhotoVideoPreview.fadeOut()

        binding.layoutTop.changeResolution.isEnabled = false
        binding.layoutTop.settings.isEnabled = false
        binding.shutter.post {
            if (!isDestroyed) {
                binding.shutter.isSelected = true
            }
        }
    }

    override fun onVideoRecordingStopped() {
        binding.cameraModeHolder.beVisible()

        binding.toggleCamera.fadeIn()
        binding.lastPhotoVideoPreview.fadeIn()

        binding.videoRecCurrTimer.text = 0.getFormattedDuration()
        binding.videoRecCurrTimer.beGone()

        binding.shutter.isSelected = false
        binding.layoutTop.changeResolution.isEnabled = true
        binding.layoutTop.settings.isEnabled = true
    }

    override fun onVideoDurationChanged(durationNanos: Long) {
        val seconds = TimeUnit.NANOSECONDS.toSeconds(durationNanos).toInt()
        binding.videoRecCurrTimer.text = seconds.getFormattedDuration()
    }

    override fun onFocusCamera(xPos: Float, yPos: Float) {
        mFocusCircleView.drawFocusCircle(xPos, yPos)
    }

    override fun onTouchPreview() {
        closeOptions()
    }

    private fun closeOptions(): Boolean {
        if (mediaSizeToggleGroup?.isVisible() == true ||
            binding.layoutFlash.flashToggleGroup.isVisible() || binding.layoutTimer.timerToggleGroup.isVisible()
        ) {
            val transitionSet = createTransition()
            TransitionManager.go(defaultScene, transitionSet)
            mediaSizeToggleGroup?.beGone()
            binding.layoutFlash.flashToggleGroup.beGone()
            binding.layoutTimer.timerToggleGroup.beGone()
            binding.layoutTop.defaultIcons.beVisible()
            return true
        }

        return false
    }

    override fun displaySelectedResolution(resolutionOption: ResolutionOption) {
        val imageRes = resolutionOption.imageDrawableResId
        binding.layoutTop.changeResolution.setShadowIcon(imageRes)
        binding.layoutTop.changeResolution.transitionName = "${resolutionOption.buttonViewId}"
    }

    override fun showImageSizes(
        selectedResolution: ResolutionOption,
        resolutions: List<ResolutionOption>,
        isPhotoCapture: Boolean,
        isFrontCamera: Boolean,
        onSelect: (index: Int, changed: Boolean) -> Unit
    ) {
        binding.topOptions.removeView(mediaSizeToggleGroup)
        val mediaSizeToggleGroup = createToggleGroup().apply {
            mediaSizeToggleGroup = this
        }

        binding.topOptions.addView(mediaSizeToggleGroup)

        val onItemClick = { clickedViewId: Int ->
            closeOptions()
            val index = resolutions.indexOfFirst { it.buttonViewId == clickedViewId }
            onSelect.invoke(index, selectedResolution.buttonViewId != clickedViewId)
        }

        resolutions.forEach {
            val button = createButton(it, onItemClick)
            mediaSizeToggleGroup.addView(button)
        }

        mediaSizeToggleGroup.check(selectedResolution.buttonViewId)

        val transitionSet = createTransition()
        val mediaSizeScene = Scene(binding.topOptions, mediaSizeToggleGroup)
        TransitionManager.go(mediaSizeScene, transitionSet)
        binding.layoutTop.defaultIcons.beGone()
        mediaSizeToggleGroup.beVisible()
        mediaSizeToggleGroup.children.map { it as MaterialButton }.forEach(::setButtonColors)
    }

    private fun createButton(resolutionOption: ResolutionOption, onClick: (clickedViewId: Int) -> Unit): MaterialButton {
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
        }

        return (layoutInflater.inflate(R.layout.layout_button, null) as MaterialButton).apply {
            layoutParams = params
            setShadowIcon(resolutionOption.imageDrawableResId)
            id = resolutionOption.buttonViewId
            transitionName = "${resolutionOption.buttonViewId}"
            setOnClickListener {
                onClick.invoke(id)
            }
        }
    }

    private fun createTransition(): Transition {
        val fadeTransition = Fade()
        return TransitionSet().apply {
            addTransition(fadeTransition)
            this.duration = resources.getInteger(R.integer.icon_anim_duration).toLong()
        }
    }

    override fun showFlashOptions(photoCapture: Boolean) {
        val transitionSet = createTransition()
        TransitionManager.go(flashModeScene, transitionSet)
        binding.layoutFlash.flashAuto.beVisibleIf(photoCapture)
        binding.layoutFlash.flashAlwaysOn.beVisibleIf(photoCapture)
        binding.layoutFlash.flashToggleGroup.check(config.flashlightState.toFlashModeId())

        binding.layoutFlash.flashToggleGroup.beVisible()
        binding.layoutFlash.flashToggleGroup.children.forEach { setButtonColors(it as MaterialButton) }
    }

    private fun setButtonColors(button: MaterialButton) {
        val primaryColor = getProperPrimaryColor()
        val states = arrayOf(intArrayOf(-android.R.attr.state_checked), intArrayOf(android.R.attr.state_checked))
        val iconColors = intArrayOf(ContextCompat.getColor(this, R.color.md_grey_white), primaryColor)
        button.iconTint = ColorStateList(states, iconColors)
    }

    override fun adjustPreviewView(requiresCentering: Boolean) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(binding.viewHolder)
        if (requiresCentering) {
            constraintSet.connect(binding.previewView.id, ConstraintSet.TOP, binding.topOptions.id, ConstraintSet.BOTTOM)
            constraintSet.connect(binding.previewView.id, ConstraintSet.BOTTOM, binding.cameraModeHolder.id, ConstraintSet.TOP)
        } else {
            constraintSet.connect(binding.previewView.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            constraintSet.connect(binding.previewView.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        }
        constraintSet.applyTo(binding.viewHolder)
    }

    override fun mediaSaved(path: String) {
        rescanPaths(arrayListOf(path)) {
            setupPreviewImage(true)
            Intent(BROADCAST_REFRESH_MEDIA).apply {
                putExtra(REFRESH_PATH, path)
                `package` = "com.simplemobiletools.gallery"
                sendBroadcast(this)
            }
        }

        if (isImageCaptureIntent()) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun scheduleTimer(timerMode: TimerMode) {
        hideViewsOnTimerStart()
        binding.shutter.setImageState(intArrayOf(R.attr.state_timer_cancel), true)
        binding.timerText.beVisible()
        var playSound = true
        countDownTimer = object : CountDownTimer(timerMode.millisInFuture, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) + 1).toString()
                binding.timerText.setText(seconds)
                if (playSound && config.isSoundEnabled) {
                    if (millisUntilFinished <= TIMER_2_SECONDS) {
                        mediaSoundHelper.playTimerCountdown2SecondsSound()
                        playSound = false
                    } else {
                        mediaSoundHelper.playTimerCountdownSound()
                    }
                }
            }

            override fun onFinish() {
                cancelTimer()
                mPreview!!.tryTakePicture()
            }
        }.start()
    }

    private fun hideViewsOnTimerStart() {
        arrayOf(binding.topOptions, binding.toggleCamera, binding.lastPhotoVideoPreview, binding.cameraModeHolder).forEach {
            it.fadeOut()
            it.beInvisible()
        }
    }

    private fun resetViewsOnTimerFinish() {
        arrayOf(binding.topOptions, binding.toggleCamera, binding.lastPhotoVideoPreview, binding.cameraModeHolder).forEach {
            it?.fadeIn()
            it?.beVisible()
        }

        binding.timerText.beGone()
        binding.shutter.setImageState(intArrayOf(-R.attr.state_timer_cancel), true)
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            add(Release(33, R.string.release_33))
            add(Release(35, R.string.release_35))
            add(Release(39, R.string.release_39))
            add(Release(44, R.string.release_44))
            add(Release(46, R.string.release_46))
            add(Release(52, R.string.release_52))
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
