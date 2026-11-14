package com.arrival.mode

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.arrival.mode.databinding.ActivityMainBinding

/** 화면 순서. ViewFlipper 자식 순서와 같다. */
enum class Screen { WAITING, BRIEF, WAYPOINT, ASK_SPOT, SPOT_SAVED, SUMMARY }

/**
 * 도착 모드 화면(프론트). ArrivalService가 내비·기어·위치 이벤트로 만든 상태를 ArrivalBus로 받아 그린다.
 * 권한이 없거나 서비스가 못 뜨면 화면 위 안내 띠(notice)로 알린다.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.altCard.setOnClickListener { ArrivalBus.onAccept?.invoke() }
        b.listenBrief.root.setOnClickListener { ArrivalBus.onAccept?.invoke() }
        b.undoButton.setOnClickListener { ArrivalBus.onUndo?.invoke() }
        b.listenAsk.root.setOnClickListener { ArrivalBus.onAskSpot?.invoke() }
        b.retryRow.setOnClickListener { show(Screen.ASK_SPOT); ArrivalBus.onAskSpot?.invoke() }
        b.homeCard.setOnClickListener { selectDestination(home = true) }
        b.workCard.setOnClickListener { selectDestination(home = false) }
        b.startButton.setOnClickListener {
            val goHome = ArrivalBus.onGoHome
            if (homeSelected && goHome != null) goHome() else show(Screen.WAITING)
        }

        b.notice.setOnClickListener { askAgain() }

        val missing = Permissions.ALL.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) applyPermissions() else requestPermissions(missing.toTypedArray(), REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ) applyPermissions()
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 권한을 켜고 돌아온 경우
        if (asked) applyPermissions()
    }

    private var asked = false

    /** 위치가 없으면 서비스를 띄우지 않고 안내, 마이크만 없으면 띄우되 "카드로 선택" 안내. */
    private fun applyPermissions() {
        asked = true
        when {
            !Permissions.required(this) -> showNotice("위치 권한이 있어야 도착 모드가 동작해요 · 눌러서 허용")
            !Permissions.mic(this) -> { startArrival(); showNotice("마이크 권한이 없어 말로 답할 수 없어요 · 카드를 눌러 선택하거나 여기를 눌러 허용") }
            else -> { startArrival(); showNotice(null) }
        }
    }

    /** 다시 물을 수 있으면 권한 창, "다시 묻지 않음"이면 앱 설정 화면. */
    private fun askAgain() {
        val missing = Permissions.ALL.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        if (missing.any { shouldShowRequestPermissionRationale(it) }) requestPermissions(missing.toTypedArray(), REQ)
        else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun startArrival() {
        runCatching { startForegroundService(Intent(this, ArrivalService::class.java)) }
            .onFailure { showNotice("도착 모드를 시작하지 못했어요 · 권한을 확인해 주세요") }
    }

    private fun showNotice(text: String?) {
        b.notice.text = text
        b.notice.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        ArrivalBus.attach(::render, ::renderVoice, ::renderRms, ::showNotice)
    }

    override fun onStop() {
        ArrivalBus.detach()
        super.onStop()
    }

    private fun render(ui: ArrivalUi) {
        ui.destName?.let { b.waitDest.text = it; b.waitLot.text = "$it 주차장" }
        ui.remainText?.let { b.waitRemain.text = it }
        ui.main?.let { m ->
            b.briefMainName.text = m.name
            b.briefMainDetail.text = m.address
            b.briefMainSub.text = m.fee
        }
        val alt = ui.alt
        b.altCard.visibility = if (alt != null || ui.screen != Screen.BRIEF) View.VISIBLE else View.GONE
        alt?.let { a ->
            b.briefAltName.text = a.name
            b.briefAltDetail.text = "${ui.altDistM}m · ${a.kind}"
            b.briefAltSub.text = a.fee
            b.waypointTitle.text = "${a.name}${Reply.eulo(a.name)}\n목적지를 바꿨어요"
            b.waypointSub.text = "목적지에서 ${ui.altDistM}m"
        }
        ui.parkedLot?.let { b.parkedAsk.parkedName.text = it; b.parkedSaved.parkedName.text = it }
        ui.spot?.let { b.spotTitle.text = it; b.spotSub.text = "주차 위치와 함께 기억해 둘게요." }
        ui.parkedTime?.let { b.parkedAsk.parkedTime.text = it; b.parkedSaved.parkedTime.text = it }
        if (ui.screen == Screen.SUMMARY) renderSummary(ui)
        show(ui.screen)
    }

    /**
     * 하단 음성 카드. 화면은 바로 바뀌어도 음성은 내비 안내가 끝날 때까지 늦을 수 있어,
     * 지금 실제로 무엇을 하는지 보여 준다. 파형 아이콘은 실제로 들을 때만 진하게.
     */
    private fun renderVoice(v: VoiceState) {
        // 안내 음성이 실패한 직후 듣기로 넘어가면 "듣고 있어요"만으로는 실패가 가려진다. 다음 안내가 나갈 때까지 표시를 유지.
        if (v == VoiceState.TTS_FAILED) ttsFailed = true
        if (v == VoiceState.SPEAKING) ttsFailed = false
        val (title, sub) = if (v == VoiceState.LISTENING && ttsFailed) "음성 안내 없이 듣고 있어요" to "화면 질문에 말로 답하거나 카드를 눌러주세요" else when (v) {
            VoiceState.SPEAKING -> "안내하는 중" to "끝나면 바로 들을게요"
            VoiceState.WAITING_NAV -> "내비 안내 후 말씀드릴게요" to "기다리지 않으려면 카드를 눌러주세요"
            VoiceState.LISTENING -> "듣고 있어요" to "말로 답하거나 카드를 눌러주세요"
            VoiceState.MISSED -> "잘 못 들었어요" to "카드를 누르거나 다시 말해 주세요"
            VoiceState.IDLE -> "말로 답할 수 있어요" to "카드를 눌러 선택해도 돼요"
            VoiceState.TTS_FAILED -> "음성 안내를 하지 못했어요" to "화면 내용을 확인하고 카드를 눌러주세요"
        }
        val icon = when (v) {
            VoiceState.SPEAKING -> R.drawable.ic_voice_speaker
            VoiceState.WAITING_NAV -> R.drawable.ic_voice_navi
            VoiceState.LISTENING -> R.drawable.ic_wave
            VoiceState.MISSED -> R.drawable.ic_voice_retry
            VoiceState.IDLE -> R.drawable.ic_wave
            VoiceState.TTS_FAILED -> R.drawable.ic_voice_retry
        }
        rmsLow = Double.MAX_VALUE; rmsHigh = -Double.MAX_VALUE
        for (card in listOf(b.listenBrief, b.listenAsk)) {
            card.voiceTitle.text = title
            card.voiceSub.text = sub
            card.voiceIcon.setImageResource(icon)
            card.voiceIcon.alpha = if (v == VoiceState.IDLE) 0.3f else 1f
            card.voiceIcon.animate().cancel()
            card.voiceIcon.scaleY = 1f
        }
        voice = v
    }

    private var voice = VoiceState.IDLE
    private var ttsFailed = false
    private var rmsLow = Double.MAX_VALUE
    private var rmsHigh = -Double.MAX_VALUE

    /** 듣는 동안 파형 세로 크기를 소리 크기에 맞춘다. RMS 단위가 정해져 있지 않아 이번 듣기에서 본 최소~최대로 정규화한다. */
    private fun renderRms(rms: Double) {
        if (voice != VoiceState.LISTENING) return
        rmsLow = minOf(rmsLow, rms); rmsHigh = maxOf(rmsHigh, rms)
        val level = if (rmsHigh - rmsLow < 1e-6) 0.0 else (rms - rmsLow) / (rmsHigh - rmsLow)
        val scale = (0.35 + 0.65 * level).toFloat()
        for (card in listOf(b.listenBrief, b.listenAsk)) card.voiceIcon.animate().scaleY(scale).setDuration(90).start()
    }

    private fun renderSummary(ui: ArrivalUi) {
        val h = ui.elapsedMin / 60; val m = ui.elapsedMin % 60
        b.elapsed.text = if (h > 0) "${h}시간 ${m}분 경과" else "${m}분 경과"
        if (ui.freeMin > 0) {
            val left = ui.freeMin - ui.elapsedMin
            b.freeLeft.text = if (left > 0) "${left}분 남았어요" else "무료 시간이 끝났어요"
            b.freeTotal.text = "${ui.freeMin}분 무료"
            b.freeProgress.max = ui.freeMin
            b.freeProgress.progress = ui.elapsedMin.coerceAtMost(ui.freeMin)
        } else {
            b.freeLeft.text = "무료 시간 없음"
            b.freeTotal.text = ""
            b.freeProgress.progress = 0
        }
    }

    fun show(screen: Screen) { b.flipper.displayedChild = screen.ordinal }

    /** 05 화면에서 고른 카드. 레이아웃 기본값이 '집' 선택이다. */
    private var homeSelected = true

    private fun selectDestination(home: Boolean) {
        homeSelected = home
        b.homeCard.setBackgroundResource(if (home) R.drawable.bg_card_selected else R.drawable.bg_card)
        b.workCard.setBackgroundResource(if (home) R.drawable.bg_card else R.drawable.bg_card_selected)
    }

    companion object {
        private const val REQ = 1
    }
}
