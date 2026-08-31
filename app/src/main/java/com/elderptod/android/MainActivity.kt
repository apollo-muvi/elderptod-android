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
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

private const val PREFS = "elderptod"
private const val KEY_DEVICE_TOKEN = "device_token"
private const val KEY_BASE_URL = "base_url"
private const val KEY_FORCE_MEDIA_SPEAKER = "force_media_speaker"
private const val DEFAULT_BASE_URL = "http://127.0.0.1:8000"
private const val LOG_TAG = "ElderPTOD"
class MainActivity : ComponentActivity(), SignalingListener, WebRtcEvents {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val backendClient by lazy { BackendClient(httpClient, mainHandler) }
    private val audioController by lazy { CallAudioController(this) }
    private val signalingClient by lazy { SignalingClient(httpClient, mainHandler, this) }
    private val reminderTts by lazy { ReminderTtsManager(this, mainHandler) }
    private val webrtc by lazy { WebRtcCallManager(this, mainHandler, this) }
    private val httpClient = OkHttpClient.Builder().build()
    private val demoReminder = ReminderState(
        title = "早上吃藥",
        message = "媽媽，現在該吃早上的藥了。",
        timeText = "08:00",
    )
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showReadyToStart()
            } else {
                showReadyToStart("請允許麥克風，家人接通後才聽得到你。")
            }
        }

    private lateinit var root: LinearLayout
    private lateinit var ui: ElderUi
    private lateinit var topBar: LinearLayout
    private lateinit var backButton: Button
    private lateinit var brandText: TextView
    private lateinit var topStatus: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private lateinit var homeActions: LinearLayout
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
    private var iceServers: List<PeerConnection.IceServer> =
        listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildShell()
        reminderTts.initialize()
        if (deviceToken().isNullOrBlank()) {
            showSetup()
        } else {
            showReadyToStart()
        }
    }

    override fun onDestroy() {
        signalingClient.close()
        reminderTts.shutdown()
        webrtc.stop()
        audioController.stopCallAudio()
        super.onDestroy()
    }

    override fun onHelloAck(deviceName: String, settings: JSONObject?) {
        Log.i(LOG_TAG, "hello_ack deviceName=$deviceName")
        webrtc.setRemoteAudioGain(remoteAudioGain(settings))
        status.text = "可以使用"
        if (activeCall == null && !reminderUiActive) {
            showIdle()
        }
    }

    override fun onConfigUpdated(settings: JSONObject?) {
        val profile = settings?.optString("remote_playback_gain_profile")
        Log.i(LOG_TAG, "config_updated gain=$profile")
        webrtc.setRemoteAudioGain(remoteAudioGain(settings))
    }

    override fun onIncomingCall(callId: String, callerName: String) {
        Log.i(LOG_TAG, "incoming_call callId=$callId callerName=$callerName")
        activeCall = CallState(callId, callerName, "ringing")
        audioController.startRingtone()
        showIncoming(callerName)
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
            "rejected", "missed", "failed", "ended" -> endLocalCall("可以使用")
        }
    }

    override fun onSignal(callId: String, signal: JSONObject) {
        if (activeCall?.id != callId) return
        webrtc.handleSignal(signal)
    }

    override fun onDisconnected() {
        Log.w(LOG_TAG, "signaling disconnected")
        webrtc.stop()
        audioController.stopCallAudio()
        activeCall = null
        status.text = "正在重新連線"
        showOffline()
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
        ui = ElderUi(this)
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
        homeActions = ui.homeActionList()
        primaryButton = ui.actionButton(ElderActionStyle.PRIMARY)
        secondaryButton = ui.actionButton(ElderActionStyle.SECONDARY)
        tertiaryButton = ui.actionButton(ElderActionStyle.SECONDARY)
        dangerButton = ui.actionButton(ElderActionStyle.DANGER)
        val switchControl = ui.engineeringSwitchRow(
            label = "工程設定：強制外放",
            contentDescription = "強制外放",
        )
        speakerRow = switchControl.row
        speakerSwitch = switchControl.switch

        root.addView(topBar, ui.matchWrap())
        root.addView(title, ui.matchWrap())
        root.addView(subtitle, ui.matchWrap())
        root.addView(status, ui.matchWrap())
        root.addView(content, ui.expandedContent())
        root.addView(speakerRow, ui.matchWrap())
        root.addView(homeActions, ui.matchWrap())
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
        onBack: () -> Unit = { showIdle() },
    ) {
        brandText.text = brand
        topStatus.text = state
        topStatus.visibility = if (state.isBlank()) View.GONE else View.VISIBLE
        backButton.visibility = if (showBack) View.VISIBLE else View.GONE
        backButton.setOnClickListener { onBack() }
    }

    private fun showBodyStatus(message: String) {
        status.text = message
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showTextStack(
        titleText: String,
        subtitleText: String = "",
        statusText: String = "",
    ) {
        ui.applyScreenTitle(title)
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
    }

    private fun showSetup(message: String = "") {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "配對")
        showTextStack("設定這台對講機", "請家人協助輸入配對碼", message)
        val savedBaseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        baseUrlInput = ui.input("後端網址", savedBaseUrl)
        pairingCodeInput = ui.input("配對碼", "")
        pairingCodeInput?.imeOptions = EditorInfo.IME_ACTION_DONE
        content.addView(baseUrlInput, ui.matchWrap())
        content.addView(pairingCodeInput, ui.matchWrap())
        primaryButton.text = "設定"
        primaryButton.setOnClickListener { pairDevice() }
        hideActions()
        primaryButton.visibility = View.VISIBLE
    }

    private fun showReadyToStart(message: String = "") {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "準備中")
        showTextStack(
            if (hasMicPermission()) "設定完成" else "請允許麥克風",
            if (hasMicPermission()) {
                "可以開始等待家人來電"
            } else {
                "設定完成後就可以等待家人來電"
            },
            message,
        )
        primaryButton.text = if (hasMicPermission()) "開始" else "繼續"
        primaryButton.setOnClickListener {
            if (hasMicPermission()) {
                startOnline()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        hideActions()
        primaryButton.visibility = View.VISIBLE
    }

    private fun showIdle() {
        homeClockActive = true
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "裝置正常")
        ui.applyHomeTime(title)
        title.text = currentTimeText()
        subtitle.text = currentDateText()
        subtitle.visibility = View.VISIBLE
        showBodyStatus("")
        content.addView(ui.reminderCard(demoReminder), ui.matchWrap())
        val playAction = ui.homeActionCard(
            title = "播放提醒",
            subtitle = "聽早上吃藥提醒",
            primary = true,
        ).apply {
            setOnClickListener { playReminder(demoReminder) }
        }
        val settingsAction = ui.homeActionCard(
            title = "聲音設定",
            subtitle = "確認擴音與中文語音",
            primary = false,
        ).apply {
            setOnClickListener { showSettings() }
        }
        hideActions()
        homeActions.addView(playAction, ui.homeActionParams(first = true))
        homeActions.addView(settingsAction, ui.homeActionParams(first = false))
        homeActions.visibility = View.VISIBLE
        scheduleClockRefresh()
    }

    private fun showIncoming(callerName: String) {
        homeClockActive = false
        reminderUiActive = false
        reminderTts.stop()
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "家人來電")
        showTextStack("家人正在找你", callerName)
        primaryButton.text = "接聽"
        primaryButton.setOnClickListener { acceptCall() }
        secondaryButton.text = "現在不方便"
        secondaryButton.setOnClickListener { rejectCall() }
        hideActions()
        primaryButton.visibility = View.VISIBLE
        secondaryButton.visibility = View.VISIBLE
    }

    private fun showConnecting() {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "接通中")
        showTextStack("正在接通", "請稍等")
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
        showTextStack("正在跟家人說話", subtitle.text.toString())
        hideActions()
        dangerButton.visibility = View.VISIBLE
        dangerButton.text = "結束"
        dangerButton.setOnClickListener { hangup() }
        showSpeakerSwitch()
    }

    private fun showOffline() {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("ElderPTOD", "網路異常")
        showTextStack("正在重新連線", "請稍等")
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
            ui.panel(
                label = "提醒",
                title = reminder.title,
                meta = "",
                style = ElderPanelStyle.SOFT,
            ),
            ui.matchWrap(),
        )
        content.addView(
            ui.messageCard(reminder.message),
            ui.matchWrap(),
        )
        content.addView(
            ui.audioStatusRow("系統正在用中文語音唸出這個提醒，不會和其他語音重疊。"),
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
    }

    private fun acknowledgeReminder(reminder: ReminderState) {
        homeClockActive = false
        reminderUiActive = true
        reminderTts.stop()
        clearContent()
        showHeader("ElderPTOD", "● 已回報", showBack = true)
        hideTextStack()
        content.addView(
            ui.supportMessageCard("已經通知家人"),
            ui.matchWrap(),
        )
        content.addView(
            ui.messageCard("你已按下「我知道了」。系統會記錄這個提醒已確認。", 28f),
            ui.matchWrap(),
        )
        content.addView(
            ui.panel(
                label = "回報狀態",
                title = "received → played → acknowledged",
                meta = "對應 NotificationDelivery 狀態。",
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
        content.addView(ui.supportMessageCard("要打給家人嗎？"), ui.matchWrap())
        content.addView(
            ui.messageCard("按下後會開始語音通話。提醒不會自動接聽，也不會開視訊。", 28f),
            ui.matchWrap(),
        )
        content.addView(
            ui.panel(
                label = "通話規則",
                title = "保留現有音訊路徑",
                meta = "通知與提醒改用 Android 原生 TTS。",
            ),
            ui.matchWrap(),
        )
        primaryButton.text = "打給家人"
        primaryButton.setOnClickListener { showBodyStatus("請家人從家人端打進來") }
        secondaryButton.text = "先不要"
        secondaryButton.setOnClickListener { playReminder(reminder) }
        hideActions()
        primaryButton.visibility = View.VISIBLE
        secondaryButton.visibility = View.VISIBLE
    }

    private fun showSettings() {
        homeClockActive = false
        reminderUiActive = false
        clearDynamicInputs()
        clearContent()
        showHeader("設定", "", showBack = true)
        showTextStack("聲音設定")
        content.addView(ui.settingRow("中文語音", "可用"), ui.matchWrap())
        content.addView(ui.settingRow("連線", "正常"), ui.matchWrap())
        content.addView(ui.footerNote("這裡只保留工程控制，不放提醒管理。"), ui.matchWrap())
        primaryButton.text = "回首頁"
        primaryButton.setOnClickListener { showIdle() }
        hideActions()
        showSpeakerSwitch()
        primaryButton.visibility = View.VISIBLE
    }

    private fun pairDevice() {
        val baseUrl = normalizeBaseUrl(baseUrlInput?.text?.toString().orEmpty())
        val pairingCode = pairingCodeInput?.text?.toString().orEmpty().trim()
        if (baseUrl.isBlank() || pairingCode.isBlank()) {
            status.text = "請輸入網址和配對碼"
            return
        }
        status.text = "正在設定"
        backendClient.pairDevice(baseUrl, pairingCode, "長者對講機") { result ->
            result.onSuccess { token ->
                prefs.edit()
                    .putString(KEY_BASE_URL, baseUrl)
                    .putString(KEY_DEVICE_TOKEN, token)
                    .apply()
                showReadyToStart()
            }.onFailure {
                status.text = "配對碼無效，請家人重新產生"
            }
        }
    }

    private fun startOnline() {
        val token = deviceToken()
        if (token.isNullOrBlank()) {
            showSetup()
            return
        }
        status.text = "正在連線"
        val baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
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
        audioController.startCallAudio(forceMediaSpeaker())
        webrtc.start(call.id, iceServers, forceMediaSpeaker())
        signalingClient.sendCallEvent("accept_call", call.id)
        showConnecting()
    }

    private fun rejectCall() {
        val call = activeCall ?: return
        Log.i(LOG_TAG, "reject_call callId=${call.id}")
        audioController.stopRingtone()
        signalingClient.sendCallEvent("reject_call", call.id)
        endLocalCall("可以使用")
    }

    private fun hangup() {
        val call = activeCall
        if (call != null) {
            Log.i(LOG_TAG, "hangup callId=${call.id}")
            signalingClient.sendCallEvent("hangup", call.id)
        }
        endLocalCall("可以使用")
    }

    private fun endLocalCall(nextStatus: String) {
        callTimerActive = false
        callStartedAt = 0L
        audioController.stopRingtone()
        audioController.stopCallAudio()
        webrtc.stop()
        activeCall = null
        status.text = nextStatus
        showIdle()
    }

    private fun clearDynamicInputs() {
        baseUrlInput?.let { (it.parent as? LinearLayout)?.removeView(it) }
        pairingCodeInput?.let { (it.parent as? LinearLayout)?.removeView(it) }
        baseUrlInput = null
        pairingCodeInput = null
    }

    private fun clearContent() {
        content.removeAllViews()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun deviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)

    private fun forceMediaSpeaker(): Boolean =
        prefs.getBoolean(KEY_FORCE_MEDIA_SPEAKER, true)

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
        return "今天 ${calendar.get(java.util.Calendar.MONTH) + 1}月" +
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
        subtitle.text = "%02d:%02d".format(elapsed / 60, elapsed % 60)
        mainHandler.postDelayed({ tickCallTimer() }, 1_000)
    }

    private fun remoteAudioGain(settings: JSONObject?): Double =
        when (settings?.optString("remote_playback_gain_profile")) {
            "normal" -> 1.0
            "loud" -> 1.25
            else -> 1.8
        }
}

data class ReminderState(
    val title: String,
    val message: String,
    val timeText: String,
)

data class CallState(
    val id: String,
    val callerName: String,
    val status: String,
)

interface SignalingListener {
    fun onHelloAck(deviceName: String, settings: JSONObject?)
    fun onConfigUpdated(settings: JSONObject?)
    fun onIncomingCall(callId: String, callerName: String)
    fun onCallUpdated(call: CallState)
    fun onSignal(callId: String, signal: JSONObject)
    fun onDisconnected()
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
                        mainHandler.post { callback(Result.failure(IOException("pair failed"))) }
                        return
                    }
                    val token = JSONObject(response.body?.string().orEmpty())
                        .optString("device_token")
                    mainHandler.post {
                        if (token.isBlank()) {
                            callback(Result.failure(IOException("missing token")))
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
        mainHandler.post { handleDisconnect(webSocket) }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        mainHandler.post { handleDisconnect(webSocket) }
    }

    private fun handleMessage(message: JSONObject) {
        when (message.optString("type")) {
            "hello_ack" -> listener.onHelloAck(
                message.optString("device_name"),
                message.optJSONObject("settings"),
            )
            "config_updated" -> listener.onConfigUpdated(message.optJSONObject("settings"))
            "incoming_call" -> listener.onIncomingCall(
                message.optString("call_id"),
                message.optString("caller_name", "家人"),
            )
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

    private fun handleDisconnect(webSocket: WebSocket) {
        if (socket !== webSocket) return
        mainHandler.removeCallbacks(pingRunnable)
        socket = null
        if (closedByUser) return
        listener.onDisconnected()
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

    fun startRingtone() {
        stopRingtone()
        maximizeRingVolume()
        Log.i(LOG_TAG, "ringtone start stream=ring")
        ringActive = true
        val defaultRingtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, defaultRingtone)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isLooping = true
                volume = 1.0f
            }
            play()
        }
        if (ringtone == null || ringtone?.isPlaying != true) {
            toneGenerator = ToneGenerator(AudioManager.STREAM_RING, 100)
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
    fun startCallAudio(forceMediaSpeaker: Boolean) {
        stopRingtone()
        if (!callAudioActive) {
            previousMode = audioManager.mode
            previousSpeakerphone = audioManager.isSpeakerphoneOn
        }
        callAudioActive = true
        maximizeCallVolume()
        applyCallAudioRoute(forceMediaSpeaker)
    }

    @Suppress("DEPRECATION")
    fun updateCallAudioRoute(forceMediaSpeaker: Boolean) {
        if (!callAudioActive) return
        applyCallAudioRoute(forceMediaSpeaker)
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

    private fun ensureMinimumVolume(stream: Int) {
        val maxVolume = audioManager.getStreamMaxVolume(stream)
        val minVolume = (maxVolume * 0.6f).toInt().coerceAtLeast(1)
        if (audioManager.getStreamVolume(stream) < minVolume) {
            audioManager.setStreamVolume(stream, minVolume, 0)
        }
    }

    private fun maximizeCallVolume() {
        if (previousMusicVolume == null) {
            previousMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        if (previousVoiceCallVolume == null) {
            previousVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        }
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0,
        )
        audioManager.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
            0,
        )
    }

    private fun maximizeRingVolume() {
        if (previousRingVolume == null) {
            previousRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        }
        audioManager.setStreamVolume(
            AudioManager.STREAM_RING,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_RING),
            0,
        )
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
    private var remoteAudioGain = 2.0
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

private fun normalizeBaseUrl(raw: String): String =
    raw.trim().trimEnd('/').let {
        when {
            it.startsWith("http://") || it.startsWith("https://") -> it
            it.isNotBlank() -> "http://$it"
            else -> ""
        }
    }
