package com.arrival.mode

import android.os.Handler
import android.os.Looper

/** 하단 음성 카드에 보여 줄 상태. */
enum class VoiceState { IDLE, SPEAKING, WAITING_NAV, LISTENING, MISSED, TTS_FAILED }

/** 화면에 그릴 값. 서비스가 만들고 MainActivity가 그린다. null인 칸은 레이아웃 기본 문구를 그대로 둔다. */
data class ArrivalUi(
    val screen: Screen,
    val destName: String? = null,
    val remainText: String? = null,
    val main: ParkingLot? = null,
    val alt: ParkingLot? = null,
    val altDistM: Int = 0,
    val parkedLot: String? = null,
    val parkedTime: String? = null,
    val elapsedMin: Int = 0,
    val freeMin: Int = 0,
    val spot: String? = null,
)

/**
 * 서비스 ↔ 화면 연결. 같은 프로세스라 바인딩 없이 싱글턴으로 둔다.
 * 화면이 없을 때 온 상태는 [last]에 남겨 두었다가 화면이 붙을 때 그린다.
 */
object ArrivalBus {
    private val main = Handler(Looper.getMainLooper())
    var last: ArrivalUi = ArrivalUi(Screen.WAITING); private set
    private var listener: ((ArrivalUi) -> Unit)? = null

    /** 화면 → 서비스 요청. 서비스가 붙을 때 채운다. */
    var onAccept: (() -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    var onAskSpot: (() -> Unit)? = null

    /** 음성 상태는 화면 전환과 따로 자주 바뀌어 별도 통로로 보낸다. */
    var lastVoice = VoiceState.IDLE; private set
    private var voiceListener: ((VoiceState) -> Unit)? = null
    private var rmsListener: ((Double) -> Unit)? = null
    private var noticeListener: ((String?) -> Unit)? = null
    var lastNotice: String? = null; private set

    fun post(ui: ArrivalUi) = main.post { last = ui; listener?.invoke(ui) }

    fun postVoice(v: VoiceState) = main.post { lastVoice = v; voiceListener?.invoke(v) }
    fun postRms(rms: Double) = main.post { rmsListener?.invoke(rms) }
    /** 화면 위 안내 띠. null이면 숨김. */
    fun postNotice(text: String?) = main.post { lastNotice = text; noticeListener?.invoke(text) }

    fun attach(l: (ArrivalUi) -> Unit, v: (VoiceState) -> Unit, r: (Double) -> Unit, n: (String?) -> Unit) {
        listener = l; voiceListener = v; rmsListener = r; noticeListener = n; l(last); v(lastVoice); lastNotice?.let(n)
    }
    fun detach() { listener = null; voiceListener = null; rmsListener = null; noticeListener = null }
}
