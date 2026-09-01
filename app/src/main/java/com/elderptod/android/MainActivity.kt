package com.elderptod.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RTCStats
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

private const val PREFS = "elderptod"
private const val KEY_DEVICE_TOKEN = "device_token"
private const val KEY_BASE_URL = "base_url"
private const val KEY_FORCE_MEDIA_SPEAKER = "force_media_speaker"
private const val KEY_FONT_SIZE_MODE = "font_size_mode"
private const val DEFAULT_BASE_URL = "https://elderweb.classtutorbot.com"
private const val PAIRING_SUCCESS_AUTO_START_DELAY_MS = 2_000L
private const val CALL_RESULT_AUTO_HOME_DELAY_MS = 6_000L
private const val LOG_TAG = "ElderPTOD"
class MainActivity : ComponentActivity(), SignalingListener, WebRtcEvents {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val backendClient by lazy { BackendClient(httpClient, mainHandler) }
    private val audioController by lazy { CallAudioController(this) }
    private val signalingClient by lazy { SignalingClient(httpClient, mainHandler, this) }
    private val reminderTts by lazy { ReminderTtsManager(this, mainHandler) }
    private val webrtc by lazy { WebRtcCallManager(this, mainHandler, this) }
    private val httpClient = OkHttpClient.Builder()
        .dns(ElderDns)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (activeCall?.status == "ringing") {
                    acceptCall()
                } else {
                    startOnline()
                }
            } else {
                showReadyToStart("請允許麥克風，家人接通後才聽得到你。")
            }
        }
    private val qrScanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents
            if (contents.isNullOrBlank()) {
                showBodyStatus("未掃描 QR code")
            } else {
                applyPairingQr(contents)
            }
        }

    private lateinit var root: LinearLayout
    private lateinit var ui: ElderUi
    private lateinit var topBar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var brandText: TextView
    private lateinit var topStatus: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var contentScroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var homeActions: LinearLayout
    private lateinit var fontSizeRow: LinearLayout
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var tertiaryButton: Button
    private lateinit var dangerButton: Button
    private lateinit var speakerRow: LinearLayout
    private lateinit var speakerSwitch: Switch
    private var baseUrlInput: EditText? = null
    private var pairingCodeInput: EditText? = null
    private var activeCall: CallState? = null
    private var callStartedAt: Long = 0L
    private var callTimerActive = false
    private var homeClockActive = false
    private var reminderUiActive = false
    private var readyAutoStartRunnable: Runnable? = null
    private var callResultReturnRunnable: Runnable? = null
    private var nextReminder: ReminderState? = null
    private var callDurationText: TextView? = null
    private var remotePlaybackGainProfile = "normal"
    private var iceServers: List<PeerConnection.IceServer> =
        listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildShell()
        reminderTts.initialize()
        migrateLocalBackendUrl()
        if (deviceToken().isNullOrBlank()) {
            showSetup()
        } else {
            showIdle()
            startOnline()
        }
    }

    override fun onDestroy() {
        cancelReadyAutoStart()
        cancelCallResultReturn()
        signalingClient.close()
        reminderTts.shutdown()
        webrtc.stop()
        audioController.stopCallAudio()
        super.onDestroy()
    }

    override fun onHelloAck(deviceName: String, settings: JSONObject?, next: JSONObject?) {
        Log.i(LOG_TAG, "hello_ack deviceName=$deviceName")
        remotePlaybackGainProfile = remoteAudioGainProfile(settings)
        webrtc.setRemoteAudioGain(remoteAudioGain(settings))
        nextReminder = parseReminderState(next)
        status.text = "可以使用"
        if (activeCall == null && !reminderUiActive) {
            showIdle()
        }
    }

    override fun onConfigUpdated(settings: JSONObject?) {
        val profile = settings?.optString("remote_playback_gain_profile")
        Log.i(LOG_TAG, "config_updated gain=$profile")
        remotePlaybackGainProfile = remoteAudioGainProfile(settings)
        audioController.updateCallVolume(remotePlaybackGainProfile)
        webrtc.setRemoteAudioGain(remoteAudioGain(settings))
    }

    override fun onRemindersUpdated(next: ReminderState?) {
        Log.i(LOG_TAG, "reminders_updated next=${next?.title ?: "none"}")
        nextReminder = next
        if (activeCall == null && !reminderUiActive) {
            showIdle()
        }
    }

    override fun onIncomingCall(callId: String, callerName: String) {
        Log.i(LOG_TAG, "incoming_call callId=$callId callerName=$callerName")
        activeCall = CallState(callId, callerName, "ringing")
        audioController.startRingtone(remotePlaybackGainProfile)
        showIncoming(callerName)
    }

    override fun onNotification(reminder: ReminderState) {
        Log.i(LOG_TAG, "notification id=${reminder.notificationId} title=${reminder.title}")
        nextReminder = null
        if (activeCall != null) {
            signalingClient.sendNotificationEvent(
                reminder.notificationId,
                "failed",
                "DEVICE_BUSY",
            )
            return
        }
        signalingClient.sendNotificationEvent(reminder.notificationId, "received")
        playReminder(reminder)
    }

    override fun onCallUpdated(call: CallState) {
        Log.i(LOG_TAG, "call_updated callId=${call.id} status=${call.status}")
        activeCall = call
        when (call.status) {
            "connecting" -> {
                audioController.stopRingtone()
                showConnecting()
            }
            "connected" -> showInCall()
            "rejected", "missed", "failed", "ended" -> endLocalCall(call.status)
        }
    }

    override fun onSignal(callId: String, signal: JSONObject) {
        if (activeCall?.id != callId) return
        webrtc.handleSignal(signal)
    }

    override fun onDisconnected(reason: String) {
        Log.w(LOG_TAG, "signaling disconnected reason=$reason")
        webrtc.stop()
        audioController.stopCallAudio()
        activeCall = null
        status.text = "正在重新連線"
        showOffline(reason)
    }

    override fun onError(code: String) {
        Log.w(LOG_TAG, "signaling error code=$code")
        status.text = when (code) {
            "AUTH_FAILED" -> "配對失效，請重新設定"
            "DEVICE_BUSY" -> "裝置正在通話中"
            else -> "目前無法使用"
        }
        if (code == "AUTH_FAILED") {
            prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
            showSetup(status.text.toString())
        }
    }

    override fun sendSignal(signal: JSONObject) {
        activeCall?.let { signalingClient.sendSignal(it.id, signal) }
    }

    override fun sendMediaReady() {
        activeCall?.let { signalingClient.sendMediaReady(it.id) }
    }

    override fun onRemoteAudioReady() {
        showInCall()
    }

    private fun buildShell() {
        ui = ElderUi(this, fontSizeMode().scale)
        root = ui.screenRoot()
        val topBarControl = ui.topBar()
        topBar = topBarControl.row
        backButton = topBarControl.backButton
        brandText = topBarControl.brandText
        topStatus = topBarControl.statusText
        title = ui.titleText()
        subtitle = ui.screenLabel()
        status = ui.statusText()
        content = ui.contentColumn()
        contentScroll = ui.contentScroll(content)
        homeActions = ui.homeActionList()
        fontSizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        primaryButton = ui.actionButton(ElderActionStyle.PRIMARY)
        secondaryButton = ui.actionButton(ElderActionStyle.SECONDARY)
        tertiaryButton = ui.actionButton(ElderActionStyle.SECONDARY)
        dangerButton = ui.actionButton(ElderActionStyle.DANGER)
        val switchControl = ui.engineeringSwitchRow(
            label = "擴音",
            contentDescription = "擴音",
        )
        speakerRow = switchControl.row
        speakerSwitch = switchControl.switch

        root.addView(topBar, ui.matchWrap())
        root.addView(title, ui.matchWrap())
        root.addView(subtitle, ui.matchWrap())
        root.addView(status, ui.matchWrap())
        root.addView(contentScroll, ui.expandedContent())
        root.addView(speakerRow, ui.matchWrap())
        root.addView(homeActions, ui.matchWrap())
        root.addView(fontSizeRow, ui.matchWrap())
        root.addView(primaryButton, ui.matchWrap())
        root.addView(secondaryButton, ui.matchWrap())
        root.addView(tertiaryButton, ui.matchWrap())
        root.addView(dangerButton, ui.matchWrap())

        setContentView(
            root,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun showHeader(
        brand: String,
        state: String,
        showBack: Boolean = false,
        statusStyle: ElderStatusStyle = ElderStatusStyle.NORMAL,
        onBack: () -> Unit = { showIdle() },
    ) {
        brandText.text = brand
        topStatus.text = state
        topStatus.visibility = if (state.isBlank()) View.GONE else View.VISIBLE
        ui.applyStatusPill(topStatus, statusStyle)
        backButton.visibility = if (showBack) View.VISIBLE else View.GONE
        backButton.setOnClickListener { onBack() }
    }

    private fun showBodyStatus(message: String) {
        status.clearAnimation()
        status.alpha = 1f
        status.setTextColor(0xFF404956.toInt())
        status.text = message
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showPairingSuccessStatus() {
        showBodyStatus("配對成功")
        status.setTextColor(0xFF16856F.toInt())
        status.startAnimation(
            AlphaAnimation(0.35f, 1f).apply {
                duration = 320L
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            },
        )
    }

    private fun showTextStack(
        titleText: String,
        subtitleText: String = "",
        statusText: String = "",
    ) {
        ui.applyScreenTitle(title)
        ui.applyScreenLabel(subtitle)
        title.text = titleText
        subtitle.text = subtitleText
        subtitle.visibility = if (subtitleText.isBlank()) View.GONE else View.VISIBLE
        showBodyStatus(statusText)
    }

    private fun hideTextStack() {
        title.visibility = View.GONE
        subtitle.visibility = View.GONE
        showBodyStatus("")
    }

    private fun hideActions() {
        homeActions.visibility = View.GONE
        homeActions.removeAllViews()
        primaryButton.visibility = View.GONE
        secondaryButton.visibility = View.GONE
        tertiaryButton.visibility = View.GONE
        dangerButton.visibility = View.GONE
        speakerRow.visibility = View.GONE
        fontSizeRow.visibility = View.GONE
        fontSizeRow.removeAllViews()
    }

    private fun showSetup(message: String = "") {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "配對")
        showTextStack("設定這台裝置", "請家人協助完成配對", message)
        val savedBaseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        content.addView(
            ui.formSection(
                title = "1. 後端網址",
                detail = "Android 真機請用 HTTPS 連線，不要使用 127.0.0.1。",
            ),
            ui.matchWrap(),
        )
        baseUrlInput = ui.input("https://elderweb.classtutorbot.com", savedBaseUrl)
        baseUrlInput?.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        baseUrlInput?.imeOptions = EditorInfo.IME_ACTION_NEXT
        content.addView(baseUrlInput, ui.matchWrap())
        content.addView(
            ui.formSection(
                title = "2. 配對碼",
                detail = "請家人在管理台產生配對碼，再輸入到這台 Android 裝置。",
            ),
            ui.matchWrap(),
        )
        pairingCodeInput = ui.input("配對碼", "")
        pairingCodeInput?.imeOptions = EditorInfo.IME_ACTION_DONE
        pairingCodeInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                pairDevice()
                true
            } else {
                false
            }
        }
        content.addView(pairingCodeInput, ui.matchWrap())
        primaryButton.text = "設定"
        primaryButton.setOnClickListener { pairDevice() }
        secondaryButton.text = "掃描 QR"
        secondaryButton.setOnClickListener { scanPairingQr() }
        hideActions()
        primaryButton.visibility = View.VISIBLE
        secondaryButton.visibility = View.VISIBLE
    }

    private fun showReadyToStart(
        message: String = "",
        autoStart: Boolean = false,
    ) {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", if (autoStart) "配對成功" else "準備中")
        hideTextStack()
        val readyTitle = when {
            autoStart -> "配對成功"
            hasMicPermission() -> "準備完成"
            else -> "請允許麥克風"
        }
        val readyDetail = when {
            autoStart -> "2 秒後自動進入首頁"
            hasMicPermission() -> "可以開始等待家人來電"
            else -> "允許後才聽得到家人通話"
        }
        content.addView(
            ui.stateScreen(
                symbol = if (autoStart) "✓" else "麥",
                title = readyTitle,
                detail = message.ifBlank { readyDetail },
            ),
            ui.matchWrap(),
        )
        if (autoStart) {
            showPairingSuccessStatus()
        }
        primaryButton.text = if (hasMicPermission()) "開始" else "繼續"
        primaryButton.setOnClickListener { startFromReadyScreen() }
        hideActions()
        primaryButton.visibility = View.VISIBLE
        if (autoStart) {
            scheduleReadyAutoStart()
        }
    }

    private fun startFromReadyScreen() {
        cancelReadyAutoStart()
        if (hasMicPermission()) {
            startOnline()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun scheduleReadyAutoStart() {
        cancelReadyAutoStart()
        readyAutoStartRunnable = Runnable { startFromReadyScreen() }
        mainHandler.postDelayed(
            readyAutoStartRunnable!!,
            PAIRING_SUCCESS_AUTO_START_DELAY_MS,
        )
    }

    private fun cancelReadyAutoStart() {
        readyAutoStartRunnable?.let { mainHandler.removeCallbacks(it) }
        readyAutoStartRunnable = null
    }

    private fun cancelCallResultReturn() {
        callResultReturnRunnable?.let { mainHandler.removeCallbacks(it) }
        callResultReturnRunnable = null
    }

    private fun showIdle() {
        homeClockActive = true
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "● 裝置正常", statusStyle = ElderStatusStyle.OK)
        contentScroll.layoutParams = ui.homeContent()
        ui.applyHomeTime(title)
        ui.applyHomeDate(subtitle)
        title.text = currentTimeText()
        subtitle.text = currentDateText()
        subtitle.visibility = View.VISIBLE
        showBodyStatus("")
        content.addView(ui.reminderCard(nextReminder), ui.matchWrap())
        val playAction = ui.homeActionCard(
            title = "播放提醒",
            subtitle = nextReminder?.let { "播放下一個提醒" } ?: "目前沒有提醒",
            primary = true,
        ).apply {
            setOnClickListener {
                nextReminder?.let { playReminder(it) } ?: showBodyStatus("目前沒有提醒")
            }
        }
        val reconnectAction = ui.homeActionCard(
            title = "重新連線",
            subtitle = "重新連接家人端",
            primary = false,
        ).apply {
            setOnClickListener { startOnline() }
        }
        hideActions()
        showSpeakerSwitch()
        homeActions.addView(playAction, ui.homeActionParams(first = true))
        homeActions.addView(reconnectAction, ui.homeActionParams(first = false))
        homeActions.visibility = View.VISIBLE
        showFontSizeSelector()
        scheduleClockRefresh()
    }

    private fun showIncoming(callerName: String) {
        homeClockActive = false
        reminderUiActive = false
        reminderTts.stop()
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "家人來電")
        hideTextStack()
        content.addView(
            ui.callScreen(
                callerName = callerName,
                state = "家人來電",
                detail = "按接聽開始通話",
            ),
            ui.matchWrap(),
        )
        val callActions = ui.incomingCallActions()
        callActions.declineButton.setOnClickListener { rejectCall() }
        callActions.acceptButton.setOnClickListener { acceptCall() }
        content.addView(callActions.row, ui.matchWrap())
        hideActions()
    }

    private fun showConnecting() {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "接通中")
        hideTextStack()
        val callerName = activeCall?.callerName ?: "家人"
        content.addView(
            ui.callScreen(
                callerName = callerName,
                state = "正在接通",
                detail = "請稍等，不需要操作手機",
            ),
            ui.matchWrap(),
        )
        hideActions()
        dangerButton.visibility = View.VISIBLE
        dangerButton.text = "結束"
        dangerButton.setOnClickListener { hangup() }
        showSpeakerSwitch()
    }

    private fun showInCall() {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        if (callStartedAt == 0L) {
            callStartedAt = System.currentTimeMillis()
            callTimerActive = true
            tickCallTimer()
        }
        showHeader("ElderPTOD", "通話中")
        hideTextStack()
        callDurationText = ui.callDurationText(subtitle.text.toString())
        content.addView(
            ui.callScreen(
                callerName = activeCall?.callerName ?: "家人",
                state = "通話中",
                detail = "保持手機在身邊即可說話",
                durationText = callDurationText,
            ),
            ui.matchWrap(),
        )
        hideActions()
        dangerButton.visibility = View.VISIBLE
        dangerButton.text = "結束"
        dangerButton.setOnClickListener { hangup() }
        showSpeakerSwitch()
    }

    private fun showOffline(reason: String = "") {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "網路異常", statusStyle = ElderStatusStyle.WARNING)
        hideTextStack()
        content.addView(
            ui.stateScreen(
                symbol = "!",
                title = "連線中斷",
                detail = reason.ifBlank { "正在重新連線，請保持 Wi-Fi 開啟" },
                warning = true,
            ),
            ui.matchWrap(),
        )
        hideActions()
    }

    private fun playReminder(reminder: ReminderState) {
        homeClockActive = false
        reminderUiActive = true
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "● 正在播放", showBack = true)
        hideTextStack()
        content.addView(
            ui.reminderScreen(reminder),
            ui.matchWrap(),
        )
        content.addView(
            ui.audioStatusRow("正在播放中文語音提醒"),
            ui.matchWrap(),
        )
        primaryButton.text = "我知道了"
        primaryButton.setOnClickListener { acknowledgeReminder(reminder) }
        secondaryButton.text = "再說一次"
        secondaryButton.setOnClickListener { reminderTts.speak(reminder.message) }
        tertiaryButton.text = "打給家人"
        tertiaryButton.setOnClickListener { showCallPrompt(reminder) }
        hideActions()
        primaryButton.visibility = View.VISIBLE
        secondaryButton.visibility = View.VISIBLE
        tertiaryButton.visibility = View.VISIBLE
        reminderTts.speak(reminder.message)
        signalingClient.sendNotificationEvent(reminder.notificationId, "played")
    }

    private fun acknowledgeReminder(reminder: ReminderState) {
        homeClockActive = false
        reminderUiActive = true
        reminderTts.stop()
        if (nextReminder?.notificationId == reminder.notificationId) {
            nextReminder = null
        }
        signalingClient.sendNotificationEvent(reminder.notificationId, "acknowledged")
        clearContent()
        showHeader("ElderPTOD", "● 已回報", showBack = true)
        hideTextStack()
        content.addView(
            ui.stateScreen(
                symbol = "✓",
                title = "已經通知家人",
                detail = "家人端會看到確認時間",
            ),
            ui.matchWrap(),
        )
        primaryButton.text = "回首頁"
        primaryButton.setOnClickListener { showIdle() }
        hideActions()
        primaryButton.visibility = View.VISIBLE
    }

    private fun showCallPrompt(reminder: ReminderState) {
        homeClockActive = false
        reminderUiActive = true
        reminderTts.stop()
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "準備撥打", showBack = true) { playReminder(reminder) }
        hideTextStack()
        content.addView(
            ui.stateScreen(
                symbol = "話",
                title = "等待家人來電",
                detail = "請家人從家人端打進來。這裡只做語音，不會開視訊。",
            ),
            ui.matchWrap(),
        )
        primaryButton.text = "打給家人"
        primaryButton.setOnClickListener { showBodyStatus("請家人從家人端打進來") }
        dangerButton.text = "先不要"
        dangerButton.setOnClickListener { playReminder(reminder) }
        hideActions()
        primaryButton.visibility = View.VISIBLE
        dangerButton.visibility = View.VISIBLE
    }

    private fun pairDevice() {
        val baseUrl = normalizeBaseUrl(baseUrlInput?.text?.toString().orEmpty())
        val pairingCode = pairingCodeInput?.text?.toString().orEmpty().trim()
        if (baseUrl.isBlank() || pairingCode.isBlank()) {
            showBodyStatus("請輸入網址和配對碼")
            return
        }
        if (isAndroidLoopbackUrl(baseUrl)) {
            showBodyStatus("Android 真機不能用 127.0.0.1，請輸入 HTTPS 後端網址")
            return
        }
        showBodyStatus("正在設定，請稍等")
        primaryButton.isEnabled = false
        backendClient.pairDevice(baseUrl, pairingCode, "用戶裝置") { result ->
            primaryButton.isEnabled = true
            result.onSuccess { token ->
                prefs.edit()
                    .putString(KEY_BASE_URL, baseUrl)
                    .putString(KEY_DEVICE_TOKEN, token)
                    .apply()
                showReadyToStart(autoStart = true)
            }.onFailure { error ->
                showBodyStatus(pairingErrorMessage(error))
            }
        }
    }

    private fun scanPairingQr() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setCaptureActivity(ElderQrCaptureActivity::class.java)
            .setPrompt("掃描家人管理台的配對 QR code")
            .setBeepEnabled(true)
            .setOrientationLocked(true)
        qrScanLauncher.launch(options)
    }

    private fun applyPairingQr(contents: String) {
        val qr = parsePairingQr(contents)
        if (qr == null) {
            showBodyStatus("QR code 格式不正確，請重新產生")
            return
        }
        baseUrlInput?.setText(qr.baseUrl)
        if (qr.pairingCode.isNotBlank()) {
            pairingCodeInput?.setText(qr.pairingCode)
            pairDevice()
        } else {
            showBodyStatus("已帶入 HTTPS 後端網址，請輸入配對碼")
        }
    }

    private fun migrateLocalBackendUrl() {
        val savedBaseUrl = prefs.getString(KEY_BASE_URL, "") ?: ""
        if (savedBaseUrl.isNotBlank() && isPrivateNetworkUrl(savedBaseUrl)) {
            prefs.edit().putString(KEY_BASE_URL, DEFAULT_BASE_URL).apply()
        }
    }

    private fun startOnline() {
        cancelReadyAutoStart()
        val token = deviceToken()
        if (token.isNullOrBlank()) {
            showSetup()
            return
        }
        val baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        if (baseUrl.isBlank() || isAndroidLoopbackUrl(baseUrl)) {
            prefs.edit().remove(KEY_BASE_URL).remove(KEY_DEVICE_TOKEN).apply()
            showSetup("請輸入 HTTPS 後端網址，不要使用 127.0.0.1")
            return
        }
        showBodyStatus("正在連線")
        Log.i(LOG_TAG, "start_online baseUrl=$baseUrl")
        backendClient.fetchIceServers(baseUrl) { result ->
            result.onSuccess { servers ->
                if (servers.isNotEmpty()) {
                    iceServers = servers
                }
            }
            signalingClient.connect(baseUrl, token)
        }
    }

    private fun acceptCall() {
        if (!hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val call = activeCall ?: return
        Log.i(LOG_TAG, "accept_call callId=${call.id}")
        audioController.stopRingtone()
        audioController.startCallAudio(forceMediaSpeaker(), remotePlaybackGainProfile)
        webrtc.start(call.id, iceServers, forceMediaSpeaker())
        signalingClient.sendCallEvent("accept_call", call.id)
        showConnecting()
    }

    private fun rejectCall() {
        val call = activeCall ?: return
        Log.i(LOG_TAG, "reject_call callId=${call.id}")
        audioController.stopRingtone()
        signalingClient.sendCallEvent("reject_call", call.id)
        endLocalCall("rejected")
    }

    private fun hangup() {
        val call = activeCall
        if (call != null) {
            Log.i(LOG_TAG, "hangup callId=${call.id}")
            signalingClient.sendCallEvent("hangup", call.id)
        }
        endLocalCall("ended")
    }

    private fun endLocalCall(result: String) {
        val callerName = activeCall?.callerName ?: "家人"
        callTimerActive = false
        callStartedAt = 0L
        audioController.stopRingtone()
        audioController.stopCallAudio()
        webrtc.stop()
        activeCall = null
        showCallResult(callerName, result)
    }

    private fun showCallResult(callerName: String, result: String) {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        val state = when (result) {
            "missed" -> "未接來電"
            "failed" -> "通話失敗"
            "rejected" -> "已拒接"
            else -> "通話結束"
        }
        val detail = when (result) {
            "missed" -> "沒有接到這通家人來電"
            "failed" -> "網路不穩，已結束這通電話"
            "rejected" -> "已告訴家人現在不方便"
            else -> "已結束這通電話"
        }
        showHeader("ElderPTOD", state)
        hideTextStack()
        content.addView(
            ui.callScreen(
                callerName = callerName,
                state = state,
                detail = detail,
            ),
            ui.matchWrap(),
        )
        hideActions()
        callResultReturnRunnable = Runnable {
            callResultReturnRunnable = null
            showIdle()
        }
        mainHandler.postDelayed(callResultReturnRunnable!!, CALL_RESULT_AUTO_HOME_DELAY_MS)
    }

    private fun clearDynamicInputs() {
        baseUrlInput?.let { (it.parent as? LinearLayout)?.removeView(it) }
        pairingCodeInput?.let { (it.parent as? LinearLayout)?.removeView(it) }
        baseUrlInput = null
        pairingCodeInput = null
    }

    private fun clearContent() {
        cancelReadyAutoStart()
        cancelCallResultReturn()
        callDurationText = null
        content.removeAllViews()
        contentScroll.layoutParams = ui.expandedContent()
        contentScroll.post { contentScroll.scrollTo(0, 0) }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun deviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)

    private fun forceMediaSpeaker(): Boolean =
        prefs.getBoolean(KEY_FORCE_MEDIA_SPEAKER, true)

    private fun fontSizeMode(): ElderFontSizeMode =
        ElderFontSizeMode.fromStorage(prefs.getString(KEY_FONT_SIZE_MODE, null))

    private fun showFontSizeSelector() {
        fontSizeRow.removeAllViews()
        val control = ui.fontSizeSelector(fontSizeMode())
        control.standardButton.setOnClickListener { setFontSizeMode(ElderFontSizeMode.STANDARD) }
        control.largeButton.setOnClickListener { setFontSizeMode(ElderFontSizeMode.LARGE) }
        control.extraLargeButton.setOnClickListener { setFontSizeMode(ElderFontSizeMode.EXTRA_LARGE) }
        fontSizeRow.addView(
            control.row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        fontSizeRow.visibility = View.VISIBLE
    }

    private fun setFontSizeMode(mode: ElderFontSizeMode) {
        if (mode == fontSizeMode()) return
        prefs.edit().putString(KEY_FONT_SIZE_MODE, mode.storageValue).apply()
        Log.i(LOG_TAG, "font_size change mode=${mode.storageValue}")
        buildShell()
        showIdle()
    }

    private fun showSpeakerSwitch() {
        speakerSwitch.setOnCheckedChangeListener(null)
        speakerSwitch.isChecked = forceMediaSpeaker()
        speakerSwitch.setOnCheckedChangeListener { _, checked -> setSpeakerMode(checked) }
        speakerRow.setOnClickListener {
            speakerSwitch.isChecked = !speakerSwitch.isChecked
        }
        speakerRow.visibility = View.VISIBLE
    }

    private fun setSpeakerMode(enabled: Boolean) {
        if (enabled == forceMediaSpeaker()) return
        prefs.edit().putBoolean(KEY_FORCE_MEDIA_SPEAKER, enabled).apply()
        Log.i(LOG_TAG, "speaker_mode change forceMediaSpeaker=$enabled")
        audioController.updateCallAudioRoute(enabled)
        activeCall?.let { call ->
            if (call.status == "connecting" || call.status == "connected") {
                webrtc.restart(call.id, iceServers, enabled)
                signalingClient.sendSignal(call.id, JSONObject().put("restart_media", true))
            }
        }
    }

    private fun currentTimeText(): String {
        val calendar = java.util.Calendar.getInstance()
        return "%02d:%02d".format(
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
        )
    }

    private fun currentDateText(): String {
        val calendar = java.util.Calendar.getInstance()
        val weekdays = listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        return "${calendar.get(java.util.Calendar.YEAR)}年" +
            "${calendar.get(java.util.Calendar.MONTH) + 1}月" +
            "${calendar.get(java.util.Calendar.DAY_OF_MONTH)}日 " +
            weekdays[calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    }

    private fun scheduleClockRefresh() {
        mainHandler.postDelayed({
            if (homeClockActive && activeCall == null && deviceToken() != null) {
                title.text = currentTimeText()
                scheduleClockRefresh()
            }
        }, 30_000)
    }

    private fun tickCallTimer() {
        if (!callTimerActive || callStartedAt == 0L) return
        val elapsed = max(0L, (System.currentTimeMillis() - callStartedAt) / 1000L)
        val duration = "%02d:%02d".format(elapsed / 60, elapsed % 60)
        subtitle.text = duration
        callDurationText?.text = duration
        mainHandler.postDelayed({ tickCallTimer() }, 1_000)
    }

    private fun remoteAudioGain(settings: JSONObject?): Double =
        when (remoteAudioGainProfile(settings)) {
            "normal" -> 1.0
            "loud" -> 1.25
            "extra_loud" -> 1.8
            else -> 1.0
        }

    private fun remoteAudioGainProfile(settings: JSONObject?): String =
        when (settings?.optString("remote_playback_gain_profile")) {
            "loud" -> "loud"
            "extra_loud" -> "extra_loud"
            else -> "normal"
        }
}

data class ReminderState(
    val title: String,
    val message: String,
    val timeText: String,
    val notificationId: String? = null,
)

private data class PairingQr(
    val baseUrl: String,
    val pairingCode: String,
)

data class CallState(
    val id: String,
    val callerName: String,
    val status: String,
)

interface SignalingListener {
    fun onHelloAck(deviceName: String, settings: JSONObject?, next: JSONObject?)
    fun onConfigUpdated(settings: JSONObject?)
    fun onRemindersUpdated(next: ReminderState?)
    fun onNotification(reminder: ReminderState)
    fun onIncomingCall(callId: String, callerName: String)
    fun onCallUpdated(call: CallState)
    fun onSignal(callId: String, signal: JSONObject)
    fun onDisconnected(reason: String = "")
    fun onError(code: String)
}

interface WebRtcEvents {
    fun sendSignal(signal: JSONObject)
    fun sendMediaReady()
    fun onRemoteAudioReady()
}

private class ReminderTtsManager(
    private val context: Context,
    private val mainHandler: Handler,
) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(LOG_TAG, "tts init failed status=$status")
            return
        }
        val engine = tts ?: return
        val localeResult = setPreferredLocale(engine)
        ready = localeResult != TextToSpeech.LANG_MISSING_DATA &&
            localeResult != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ready) {
            Log.e(LOG_TAG, "tts locale unsupported result=$localeResult")
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(LOG_TAG, "tts start id=$utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.i(LOG_TAG, "tts done id=$utteranceId")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(LOG_TAG, "tts error id=$utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(LOG_TAG, "tts error id=$utteranceId code=$errorCode")
            }
        })
        pendingText?.let { text ->
            pendingText = null
            mainHandler.post { speak(text) }
        }
    }

    fun speak(text: String) {
        val message = text.trim()
        if (message.isBlank()) return
        val engine = tts
        if (!ready || engine == null) {
            pendingText = message
            Log.w(LOG_TAG, "tts queued before ready")
            return
        }
        val result = engine.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "reminder_${System.currentTimeMillis()}",
        )
        if (result == TextToSpeech.ERROR) {
            Log.e(LOG_TAG, "tts speak failed")
        }
    }

    fun stop() {
        pendingText = null
        tts?.stop()
    }

    fun shutdown() {
        pendingText = null
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun setPreferredLocale(engine: TextToSpeech): Int {
        val taiwanResult = engine.setLanguage(Locale.TAIWAN)
        if (
            taiwanResult != TextToSpeech.LANG_MISSING_DATA &&
            taiwanResult != TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return taiwanResult
        }
        val chineseResult = engine.setLanguage(Locale.CHINESE)
        if (
            chineseResult != TextToSpeech.LANG_MISSING_DATA &&
            chineseResult != TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(LOG_TAG, "tts fallback locale=zh")
            return chineseResult
        }
        Log.w(LOG_TAG, "tts fallback locale=default")
        return engine.setLanguage(Locale.getDefault())
    }
}

private class BackendClient(
    private val client: OkHttpClient,
    private val mainHandler: Handler,
) {
    fun pairDevice(
        baseUrl: String,
        pairingCode: String,
        deviceName: String,
        callback: (Result<String>) -> Unit,
    ) {
        val body = JSONObject()
            .put("pairing_code", pairingCode)
            .put("device_name", deviceName)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url("$baseUrl/api/devices/pair")
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        mainHandler.post {
                            callback(Result.failure(IOException("INVALID_PAIRING_CODE")))
                        }
                        return
                    }
                    val token = JSONObject(response.body?.string().orEmpty())
                        .optString("device_token")
                    mainHandler.post {
                        if (token.isBlank()) {
                            callback(Result.failure(IOException("BAD_PAIRING_RESPONSE")))
                        } else {
                            callback(Result.success(token))
                        }
                    }
                }
            }
        })
    }

    fun fetchIceServers(
        baseUrl: String,
        callback: (Result<List<PeerConnection.IceServer>>) -> Unit,
    ) {
        val request = Request.Builder().url("$baseUrl/api/config").get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback(Result.failure(e)) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        mainHandler.post { callback(Result.failure(IOException("config failed"))) }
                        return
                    }
                    val servers = parseIceServers(
                        JSONObject(response.body?.string().orEmpty()).optJSONArray("ice_servers"),
                    )
                    mainHandler.post { callback(Result.success(servers)) }
                }
            }
        })
    }

    private fun parseIceServers(array: JSONArray?): List<PeerConnection.IceServer> {
        if (array == null) return emptyList()
        val servers = mutableListOf<PeerConnection.IceServer>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val urlsValue = item.opt("urls") ?: continue
            val urls = when (urlsValue) {
                is JSONArray -> (0 until urlsValue.length()).mapNotNull { urlsValue.optString(it) }
                else -> listOf(urlsValue.toString())
            }.filter { it.isNotBlank() }
            if (urls.isEmpty()) continue
            val builder = PeerConnection.IceServer.builder(urls)
            val username = item.optString("username")
            val credential = item.optString("credential")
            if (username.isNotBlank()) builder.setUsername(username)
            if (credential.isNotBlank()) builder.setPassword(credential)
            servers += builder.createIceServer()
        }
        return servers
    }
}

private class SignalingClient(
    private val client: OkHttpClient,
    private val mainHandler: Handler,
    private val listener: SignalingListener,
) : WebSocketListener() {
    private var socket: WebSocket? = null
    private var baseUrl: String = ""
    private var token: String = ""
    private var closedByUser = false
    private var reconnectRunnable: Runnable? = null
    private var reconnectIndex = 0
    private val reconnectDelaysMs = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    private val pingRunnable = object : Runnable {
        override fun run() {
            socket?.send(JSONObject().put("type", "ping").toString())
            mainHandler.postDelayed(this, 30_000L)
        }
    }

    fun connect(baseUrl: String, token: String) {
        closeActiveSocket()
        mainHandler.removeCallbacks(pingRunnable)
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        closedByUser = false
        this.baseUrl = baseUrl
        this.token = token
        val wsUrl = baseUrl.replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws/elder"
        socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), this)
    }

    fun close() {
        closedByUser = true
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        closeActiveSocket()
    }

    private fun closeActiveSocket() {
        mainHandler.removeCallbacks(pingRunnable)
        socket?.close(1000, "closed")
        socket = null
    }

    fun sendCallEvent(type: String, callId: String) {
        socket?.send(JSONObject().put("type", type).put("call_id", callId).toString())
    }

    fun sendSignal(callId: String, signal: JSONObject) {
        socket?.send(
            JSONObject()
                .put("type", "signal")
                .put("call_id", callId)
                .put("signal", signal)
                .toString(),
        )
    }

    fun sendMediaReady(callId: String) {
        socket?.send(JSONObject().put("type", "media_ready").put("call_id", callId).toString())
    }

    fun sendNotificationEvent(notificationId: String?, status: String, error: String? = null) {
        if (notificationId.isNullOrBlank()) return
        val payload = JSONObject()
            .put("type", "notification_event")
            .put("notification_id", notificationId)
            .put("status", status)
        if (!error.isNullOrBlank()) {
            payload.put("error", error)
        }
        socket?.send(payload.toString())
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (socket !== webSocket) return
        reconnectIndex = 0
        webSocket.send(
            JSONObject()
                .put("type", "hello")
                .put("device_token", token)
                .put(
                    "client",
                    JSONObject()
                        .put("platform", "android_native")
                        .put("app_version", BuildConfig.VERSION_NAME)
                        .put("android_sdk", Build.VERSION.SDK_INT)
                        .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}"),
                )
                .toString(),
        )
        mainHandler.removeCallbacks(pingRunnable)
        mainHandler.postDelayed(pingRunnable, 30_000L)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (socket !== webSocket) return
        mainHandler.post {
            if (socket === webSocket) {
                handleMessage(JSONObject(text))
            }
        }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        mainHandler.post { handleDisconnect(webSocket, "連線已關閉：$code $reason") }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val responseText = response?.let { "HTTP ${it.code}" }.orEmpty()
        val message = listOf(responseText, t.message.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "WebSocket 連不上" }
        mainHandler.post { handleDisconnect(webSocket, message) }
    }

    private fun handleMessage(message: JSONObject) {
        when (message.optString("type")) {
            "hello_ack" -> listener.onHelloAck(
                message.optString("device_name"),
                message.optJSONObject("settings"),
                message.optJSONObject("next_reminder"),
            )
            "config_updated" -> listener.onConfigUpdated(message.optJSONObject("settings"))
            "reminders_updated" -> listener.onRemindersUpdated(
                parseReminderState(message.optJSONObject("next_reminder")),
            )
            "incoming_call" -> listener.onIncomingCall(
                message.optString("call_id"),
                message.optString("caller_name", "家人"),
            )
            "notification" -> {
                val notification = message.optJSONObject("notification") ?: return
                if (notification.optString("kind") != "reminder") return
                listener.onNotification(
                    ReminderState(
                        title = notification.optString("title"),
                        message = notification.optString("message"),
                        timeText = "現在",
                        notificationId = notification.optString("id"),
                    ),
                )
            }
            "call_updated" -> {
                val call = message.optJSONObject("call") ?: return
                listener.onCallUpdated(
                    CallState(
                        id = call.optString("id"),
                        callerName = call.optString("caller_name", "家人"),
                        status = call.optString("status"),
                    ),
                )
            }
            "signal" -> listener.onSignal(
                message.optString("call_id"),
                message.optJSONObject("signal") ?: JSONObject(),
            )
            "device_deleted" -> listener.onError("AUTH_FAILED")
            "error" -> listener.onError(message.optString("code"))
        }
    }

    private fun handleDisconnect(webSocket: WebSocket, reason: String) {
        if (socket !== webSocket) return
        mainHandler.removeCallbacks(pingRunnable)
        socket = null
        if (closedByUser) return
        listener.onDisconnected(reason)
        val delay = reconnectDelaysMs[minOf(reconnectIndex, reconnectDelaysMs.lastIndex)]
        reconnectIndex += 1
        reconnectRunnable = Runnable { connect(baseUrl, token) }
        mainHandler.postDelayed(reconnectRunnable!!, delay)
    }
}

private class CallAudioController(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var toneGenerator: ToneGenerator? = null
    private var ringtone: Ringtone? = null
    private var ringActive = false
    private var previousRingVolume: Int? = null
    private var previousMusicVolume: Int? = null
    private var previousVoiceCallVolume: Int? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    private var callAudioActive = false
    private var focusRequest: AudioFocusRequest? = null
    private val ringRunnable = object : Runnable {
        override fun run() {
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
            if (ringActive) mainHandler.postDelayed(this, 1_200L)
        }
    }

    fun startRingtone(gainProfile: String) {
        stopRingtone()
        val ringVolume = applyRingVolume(gainProfile)
        Log.i(LOG_TAG, "ringtone start stream=ring profile=$gainProfile")
        ringActive = true
        val defaultRingtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, defaultRingtone)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isLooping = true
                volume = ringVolume
            }
            play()
        }
        if (ringtone == null || ringtone?.isPlaying != true) {
            toneGenerator = ToneGenerator(AudioManager.STREAM_RING, (ringVolume * 100).toInt())
            ringRunnable.run()
        }
    }

    fun stopRingtone() {
        ringActive = false
        mainHandler.removeCallbacks(ringRunnable)
        ringtone?.stop()
        ringtone = null
        toneGenerator?.release()
        toneGenerator = null
        previousRingVolume?.let {
            audioManager.setStreamVolume(AudioManager.STREAM_RING, it, 0)
        }
        if (previousRingVolume != null) {
            Log.i(LOG_TAG, "ringtone stop")
        }
        previousRingVolume = null
    }

    @Suppress("DEPRECATION")
    fun startCallAudio(forceMediaSpeaker: Boolean, gainProfile: String) {
        stopRingtone()
        if (!callAudioActive) {
            previousMode = audioManager.mode
            previousSpeakerphone = audioManager.isSpeakerphoneOn
        }
        callAudioActive = true
        applyCallVolume(gainProfile)
        applyCallAudioRoute(forceMediaSpeaker)
    }

    @Suppress("DEPRECATION")
    fun updateCallAudioRoute(forceMediaSpeaker: Boolean) {
        if (!callAudioActive) return
        applyCallAudioRoute(forceMediaSpeaker)
    }

    fun updateCallVolume(gainProfile: String) {
        if (!callAudioActive) return
        applyCallVolume(gainProfile)
    }

    @Suppress("DEPRECATION")
    private fun applyCallAudioRoute(forceMediaSpeaker: Boolean) {
        requestFocus(forceMediaSpeaker)
        if (forceMediaSpeaker) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } else {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        audioManager.isSpeakerphoneOn = true
        if (!forceMediaSpeaker) {
            selectBuiltInSpeaker()
        }
        Log.i(
            LOG_TAG,
            "call_audio start mode=${audioManager.mode} speaker=${audioManager.isSpeakerphoneOn} " +
                "mediaSpeakerTest=$forceMediaSpeaker",
        )
    }

    @Suppress("DEPRECATION")
    fun stopCallAudio() {
        stopRingtone()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioManager.isSpeakerphoneOn = previousSpeakerphone
        audioManager.mode = previousMode
        previousMusicVolume?.let {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
        }
        previousVoiceCallVolume?.let {
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0)
        }
        previousMusicVolume = null
        previousVoiceCallVolume = null
        callAudioActive = false
        focusRequest?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(it)
            }
        } ?: audioManager.abandonAudioFocus(null)
        focusRequest = null
        Log.i(LOG_TAG, "call_audio stop mode=${audioManager.mode}")
    }

    private fun requestFocus(forceMediaSpeaker: Boolean) {
        focusRequest?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.abandonAudioFocusRequest(it)
            }
        } ?: audioManager.abandonAudioFocus(null)
        focusRequest = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (forceMediaSpeaker) {
                                AudioAttributes.USAGE_MEDIA
                            } else {
                                AudioAttributes.USAGE_VOICE_COMMUNICATION
                            },
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener({})
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                if (forceMediaSpeaker) {
                    AudioManager.STREAM_MUSIC
                } else {
                    AudioManager.STREAM_VOICE_CALL
                },
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    private fun applyCallVolume(gainProfile: String) {
        if (previousMusicVolume == null) {
            previousMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        if (previousVoiceCallVolume == null) {
            previousVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        }
        setCallStreamVolume(AudioManager.STREAM_MUSIC, gainProfile)
        setCallStreamVolume(AudioManager.STREAM_VOICE_CALL, gainProfile)
    }

    private fun setCallStreamVolume(stream: Int, gainProfile: String) {
        val maxVolume = audioManager.getStreamMaxVolume(stream)
        val ratio = profileVolumeRatio(gainProfile)
        val volume = (maxVolume * ratio).toInt().coerceIn(1, maxVolume)
        audioManager.setStreamVolume(stream, volume, 0)
        Log.i(LOG_TAG, "call_audio volume stream=$stream profile=$gainProfile value=$volume/$maxVolume")
    }

    private fun applyRingVolume(gainProfile: String): Float {
        if (previousRingVolume == null) {
            previousRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        }
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
        val ratio = profileVolumeRatio(gainProfile)
        val volume = (maxVolume * ratio).toInt().coerceIn(1, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_RING, volume, 0)
        Log.i(LOG_TAG, "ringtone volume profile=$gainProfile value=$volume/$maxVolume")
        return ratio
    }

    private fun profileVolumeRatio(gainProfile: String): Float {
        return when (gainProfile) {
            "extra_loud" -> 0.75f
            "loud" -> 0.45f
            else -> 0.22f
        }
    }

    private fun selectBuiltInSpeaker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val speaker = audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        if (speaker != null) {
            val selected = audioManager.setCommunicationDevice(speaker)
            Log.i(LOG_TAG, "communication_device speaker selected=$selected")
        }
    }
}

private class WebRtcCallManager(
    private val context: Context,
    private val mainHandler: Handler,
    private val events: WebRtcEvents,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private val remoteAudioTracks = mutableListOf<AudioTrack>()
    private var callId: String? = null
    private var mediaReadySent = false
    private var remoteAudioGain = 1.0
    private var factoryForceMediaSpeaker: Boolean? = null
    private var factoryInitialized = false
    private var statsRunnable: Runnable? = null
    private val pendingSignals = mutableListOf<JSONObject>()
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    fun setRemoteAudioGain(gain: Double) {
        remoteAudioGain = gain
        executor.execute {
            remoteAudioTracks.forEach { it.setVolume(remoteAudioGain) }
            Log.i(LOG_TAG, "remote_audio gain_config=$remoteAudioGain")
        }
    }

    fun start(
        callId: String,
        iceServers: List<PeerConnection.IceServer>,
        forceMediaSpeaker: Boolean,
    ) {
        this.callId = callId
        mediaReadySent = false
        Log.i(
            LOG_TAG,
            "webrtc start callId=$callId iceServers=${iceServers.size} " +
                "mediaSpeaker=$forceMediaSpeaker",
        )
        executor.execute {
            startLocked(callId, iceServers, forceMediaSpeaker)
        }
    }

    fun restart(
        callId: String,
        iceServers: List<PeerConnection.IceServer>,
        forceMediaSpeaker: Boolean,
    ) {
        this.callId = callId
        mediaReadySent = false
        Log.i(LOG_TAG, "webrtc restart callId=$callId mediaSpeaker=$forceMediaSpeaker")
        stopStatsLog()
        executor.execute {
            closePeerConnectionLocked(clearPending = true)
            startLocked(callId, iceServers, forceMediaSpeaker)
        }
    }

    fun handleSignal(signal: JSONObject) {
        executor.execute {
            if (peerConnection == null) {
                pendingSignals += signal
                return@execute
            }
            processSignal(signal)
        }
    }

    fun stop() {
        Log.i(LOG_TAG, "webrtc stop callId=$callId")
        stopStatsLog()
        executor.execute {
            closePeerConnectionLocked(clearPending = true)
            callId = null
            mediaReadySent = false
        }
    }

    private fun startLocked(
        callId: String,
        iceServers: List<PeerConnection.IceServer>,
        forceMediaSpeaker: Boolean,
    ) {
        closePeerConnectionLocked(clearPending = false)
        ensureFactory(forceMediaSpeaker)
        peerConnection = factory?.createPeerConnection(
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            },
            observer(),
        )
        val constraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = factory?.createAudioSource(constraints)
        localAudioTrack = factory?.createAudioTrack("elder_audio", audioSource)
        localAudioTrack?.setEnabled(true)
        localAudioTrack?.let {
            peerConnection?.addTrack(it, listOf("elderptod"))
        }
        Log.i(LOG_TAG, "webrtc peer ready callId=$callId mediaSpeaker=$forceMediaSpeaker")
        flushPendingSignals()
        startStatsLog()
    }

    private fun closePeerConnectionLocked(clearPending: Boolean) {
        peerConnection?.close()
        peerConnection = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        remoteAudioTracks.clear()
        audioSource?.dispose()
        audioSource = null
        if (clearPending) {
            pendingSignals.clear()
            pendingIceCandidates.clear()
        }
    }

    private fun flushPendingSignals() {
        val signals = pendingSignals.toList()
        pendingSignals.clear()
        signals.forEach { processSignal(it) }
    }

    private fun processSignal(signal: JSONObject) {
        val description = signal.optJSONObject("description")
        val candidate = signal.optJSONObject("candidate")
        when {
            description != null -> handleDescription(description)
            candidate != null -> handleCandidate(candidate)
        }
    }

    private fun handleDescription(description: JSONObject) {
        val pc = peerConnection ?: return
        val type = when (description.optString("type")) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            else -> return
        }
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    flushPendingIceCandidates()
                    if (type == SessionDescription.Type.OFFER) {
                        createAnswer(pc)
                    }
                }
            },
            SessionDescription(type, description.optString("sdp")),
        )
    }

    private fun createAnswer(pc: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        pc.createAnswer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    pc.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                events.sendSignal(
                                    JSONObject().put(
                                        "description",
                                        JSONObject()
                                            .put("type", sessionDescription.type.canonicalForm())
                                            .put("sdp", sessionDescription.description),
                                    ),
                                )
                            }
                        },
                        sessionDescription,
                    )
                }
            },
            constraints,
        )
    }

    private fun handleCandidate(candidate: JSONObject) {
        val iceCandidate = IceCandidate(
            candidate.optString("sdpMid"),
            candidate.optInt("sdpMLineIndex"),
            candidate.optString("candidate"),
        )
        val pc = peerConnection ?: return
        if (pc.remoteDescription == null) {
            pendingIceCandidates += iceCandidate
            return
        }
        pc.addIceCandidate(iceCandidate)
    }

    private fun flushPendingIceCandidates() {
        val pc = peerConnection ?: return
        val candidates = pendingIceCandidates.toList()
        pendingIceCandidates.clear()
        candidates.forEach { pc.addIceCandidate(it) }
    }

    private fun observer(): PeerConnection.Observer =
        object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(LOG_TAG, "ice_connection state=$state")
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    markMediaReady()
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                events.sendSignal(
                    JSONObject().put(
                        "candidate",
                        JSONObject()
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                            .put("candidate", candidate.sdp),
                    ),
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) {
                stream.audioTracks.forEach { amplifyRemoteAudio(it) }
                markMediaReady()
            }

            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(dataChannel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is AudioTrack) {
                    amplifyRemoteAudio(track)
                    markMediaReady()
                }
            }
        }

    private fun amplifyRemoteAudio(track: AudioTrack) {
        if (remoteAudioTracks.any { it.id() == track.id() }) return
        track.setVolume(remoteAudioGain)
        remoteAudioTracks += track
        Log.i(LOG_TAG, "remote_audio gain=$remoteAudioGain trackId=${track.id()}")
    }

    private fun startStatsLog() {
        stopStatsLog()
        val runnable = object : Runnable {
            override fun run() {
                executor.execute {
                    peerConnection?.getStats(
                        RTCStatsCollectorCallback { report ->
                            logAudioStats(report.statsMap.values)
                        },
                    )
                }
                mainHandler.postDelayed(this, 2_000L)
            }
        }
        statsRunnable = runnable
        mainHandler.postDelayed(runnable, 2_000L)
    }

    private fun stopStatsLog() {
        statsRunnable?.let { mainHandler.removeCallbacks(it) }
        statsRunnable = null
    }

    private fun logAudioStats(stats: Collection<RTCStats>) {
        val inbound = stats.firstOrNull {
            it.type == "inbound-rtp" && isAudioStats(it.members)
        }
        val candidatePair = stats.firstOrNull {
            it.type == "candidate-pair" && it.members["state"] == "succeeded"
        }
        if (inbound != null) {
            Log.i(
                LOG_TAG,
                "webrtc_stats inbound packets=${statValue(inbound, "packetsReceived")} " +
                    "lost=${statValue(inbound, "packetsLost")} " +
                    "jitter=${statValue(inbound, "jitter")} " +
                    "concealed=${statValue(inbound, "concealedSamples")} " +
                    "concealEvents=${statValue(inbound, "concealmentEvents")} " +
                    "jitterBufferDelay=${statValue(inbound, "jitterBufferDelay")}",
            )
        }
        if (candidatePair != null) {
            Log.i(
                LOG_TAG,
                "webrtc_stats pair rtt=${statValue(candidatePair, "currentRoundTripTime")} " +
                    "availableOutgoing=${statValue(candidatePair, "availableOutgoingBitrate")}",
            )
        }
    }

    private fun isAudioStats(members: Map<String, Any>): Boolean =
        members["kind"] == "audio" || members["mediaType"] == "audio"

    private fun statValue(stat: RTCStats, key: String): Any = stat.members[key] ?: "-"

    private fun markMediaReady() {
        if (mediaReadySent) return
        mediaReadySent = true
        Log.i(LOG_TAG, "media_ready callId=$callId")
        mainHandler.post {
            events.sendMediaReady()
            events.onRemoteAudioReady()
        }
    }

    private fun ensureFactory(forceMediaSpeaker: Boolean) {
        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            factoryInitialized = true
        }
        if (factory != null && factoryForceMediaSpeaker == forceMediaSpeaker) return
        factory?.dispose()
        val audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setAudioAttributes(callAudioAttributes(forceMediaSpeaker))
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        factoryForceMediaSpeaker = forceMediaSpeaker
        Log.i(LOG_TAG, "webrtc factory create mediaSpeaker=$forceMediaSpeaker")
        audioDeviceModule.release()
    }

    private fun callAudioAttributes(forceMediaSpeaker: Boolean): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(
                if (forceMediaSpeaker) {
                    AudioAttributes.USAGE_MEDIA
                } else {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
}

private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sessionDescription: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = Unit
    override fun onSetFailure(error: String) = Unit
}

private val JSON = "application/json; charset=utf-8".toMediaType()

private object ElderDns : Dns {
    private val cloudflareDns by lazy {
        DnsOverHttps.Builder()
            .client(OkHttpClient())
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
            )
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> =
        try {
            Dns.SYSTEM.lookup(hostname)
        } catch (error: UnknownHostException) {
            cloudflareDns.lookup(hostname)
        }
}

private fun isAndroidLoopbackUrl(baseUrl: String): Boolean {
    val host = try {
        URI(baseUrl).host
    } catch (error: Exception) {
        null
    } ?: return false
    return host == "127.0.0.1" || host == "localhost" || host == "::1"
}

private fun isPrivateNetworkUrl(baseUrl: String): Boolean {
    val uri = try {
        URI(baseUrl)
    } catch (error: Exception) {
        return false
    }
    if (uri.scheme != "http") return false
    val host = uri.host ?: return false
    if (host == "127.0.0.1" || host == "localhost" || host == "::1") return true
    if (host.startsWith("192.168.") || host.startsWith("10.")) return true
    val secondOctet = host
        .split('.')
        .takeIf { it.size == 4 && it[0] == "172" }
        ?.getOrNull(1)
        ?.toIntOrNull()
    return secondOctet in 16..31
}

private fun parsePairingQr(contents: String): PairingQr? {
    val trimmed = contents.trim()
    val parsed = if (trimmed.startsWith("{")) {
        parsePairingQrJson(trimmed)
    } else {
        parsePairingQrUrl(trimmed)
    } ?: return null
    val baseUrl = normalizeBaseUrl(parsed.baseUrl)
    if (!baseUrl.startsWith("https://") || isAndroidLoopbackUrl(baseUrl)) {
        return null
    }
    return PairingQr(baseUrl, parsed.pairingCode.trim().uppercase(Locale.US))
}

private fun parsePairingQrJson(contents: String): PairingQr? =
    try {
        val payload = JSONObject(contents)
        PairingQr(
            baseUrl = payload.optString("base_url", payload.optString("baseUrl", "")),
            pairingCode = payload.optString(
                "pairing_code",
                payload.optString("pairingCode", ""),
            ),
        )
    } catch (error: Exception) {
        null
    }

private fun parsePairingQrUrl(contents: String): PairingQr? =
    try {
        val uri = URI(contents)
        val query = parseQuery(uri.rawQuery)
        PairingQr(
            baseUrl = query["base_url"] ?: query["baseUrl"] ?: contents,
            pairingCode = query["pairing_code"] ?: query["pairingCode"] ?: "",
        )
    } catch (error: Exception) {
        null
    }

private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    return rawQuery.split("&").mapNotNull { part ->
        val keyValue = part.split("=", limit = 2)
        val key = decodeQueryValue(keyValue.getOrNull(0) ?: "")
        val value = decodeQueryValue(keyValue.getOrNull(1) ?: "")
        if (key.isBlank()) null else key to value
    }.toMap()
}

private fun decodeQueryValue(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())

private fun parseReminderState(reminder: JSONObject?): ReminderState? {
    if (reminder == null) return null
    val title = reminder.optString("title")
    val message = reminder.optString("message")
    if (title.isBlank() || message.isBlank()) return null
    return ReminderState(
        title = title,
        message = message,
        timeText = formatReminderTime(reminder.optString("scheduled_at")),
        notificationId = reminder.optString("notification_id").ifBlank { null },
    )
}

private fun formatReminderTime(value: String): String =
    try {
        val parsed = OffsetDateTime.parse(value)
        "%d年%d月%d日 %02d:%02d".format(
            parsed.year,
            parsed.monthValue,
            parsed.dayOfMonth,
            parsed.hour,
            parsed.minute,
        )
    } catch (error: Exception) {
        value.ifBlank { "下一次" }
    }

private fun pairingErrorMessage(error: Throwable): String =
    when (error.message) {
        "INVALID_PAIRING_CODE" -> "配對碼無效，請家人重新產生"
        "BAD_PAIRING_RESPONSE" -> "後端回應格式錯誤，請確認網址是否為 ElderPTOD 後端"
        else -> "後端連不上，請確認網址、Wi-Fi、後端服務和防火牆"
    }

private fun normalizeBaseUrl(raw: String): String =
    raw.trim().trimEnd('/').let {
        when {
            it.startsWith("http://") || it.startsWith("https://") -> it
            it.isNotBlank() -> "https://$it"
            else -> ""
        }
    }
