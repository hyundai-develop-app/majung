package com.arrival.mode

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import android.speech.tts.TextToSpeech as SysTts

/**
 * 음성 안내. Pleos TextToSpeech(ON_DEVICE, 앱 등록 불필요)를 쓰고,
 * 초기화에 실패하면 Android 기본 TTS 로 대신 읽는다(에뮬레이터 대비).
 *
 * 내비 길안내 음성에 양보한다. 내비가 말하는 중이면 기다렸다가 말하고, 우리가 말하는 도중 내비가 시작하면
 * 멈췄다가 내비가 끝난 뒤 그 문장을 다시 말한다. 오디오 포커스로는 이렇게 할 수 없어서(에뮬레이터 실측:
 * ASSISTANT 용도로 포커스를 잡으면 내비 안내가 아예 안 나오고, 길안내 용도로 잡으면 둘이 동시에 나온다)
 * 재생 중인 소리의 용도(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)를 보고 판단한다.
 */
class Announcer(private val context: Context, private val log: (String) -> Unit) {
    private val pleos = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private var pleosReady = false
    private var sysTts: SysTts? = null
    private var androidReady = false
    private val queue = ArrayDeque<String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var speaking = false
    private var current: String? = null
    private var pendingAfter: (() -> Unit)? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private var navSpeaking = false
    /** 내비에 양보하느라 stop()한 경우. 그 뒤 오는 onDone/onStop을 "다 말함"으로 보지 않는다. */
    private var yielded = false
    /** 말하기·내비 대기 상태가 바뀔 때. 화면의 음성 카드가 쓴다. */
    var onVoice: ((VoiceState) -> Unit)? = null

    private val events = object : EventListener {
        override fun onReady() {}
        override fun onStart() {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onDone() { main.post { finished() } }
        override fun onStop() { main.post { finished() } }
        // 원본은 여기서 Pleos TTS를 버리고 Android TTS로 넘어갔는데, 에뮬레이터·차량엔 Android TTS가 없을 수 있어
        // 그 뒤 모든 안내가 무음이 됐다. 이 문장만 건너뛰고 다음 문장은 다시 Pleos TTS로 말한다.
        override fun onError(errorType: ErrorType) {
            main.post {
                log("Pleos TTS 오류: $errorType → 이 문장 건너뜀, 다음 문장은 다시 시도")
                speaking = false
                current = null
                onVoice?.invoke(VoiceState.TTS_FAILED)
                if (queue.isEmpty()) runAfter() else flush()
            }
        }
    }

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = onNav(isNav(configs))
    }

    fun init() {
        // SDK 순서: initialize → addEventListener → setLocale. 초기화 전에 리스너를 달면 SDK 내부 lateinit 오류가 난다.
        pleos.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                pleosReady = true
                pleos.addEventListener(events)
                // Locale.KOREAN("ko")은 엔진이 "unsupported locale: ko-"로 거부한다. 국가까지 있는 ko_KR이어야 한다(에뮬레이터 실측).
                pleos.setLocale(Locale.KOREA)
                log("Pleos TTS 준비됨 (ON_DEVICE)")
                flush()
            }
            override fun onFailure(errorType: ErrorType) {
                log("Pleos TTS 초기화 실패: $errorType → Android TTS 사용")
                startAndroidTts()
            }
        })
        navSpeaking = isNav(audio.activePlaybackConfigurations)
        audio.registerAudioPlaybackCallback(playback, main)
    }

    private fun isNav(configs: List<AudioPlaybackConfiguration>) =
        configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }

    private fun onNav(active: Boolean) {
        if (active == navSpeaking) return
        navSpeaking = active
        if (active && speaking && pleosReady) {
            log("내비 안내 시작 → 하던 말 멈추고 양보")
            yielded = true
            current?.let { queue.addFirst(it) }
            current = null
            speaking = false
            pleos.stop()
            onVoice?.invoke(VoiceState.WAITING_NAV)
        }
        if (!active) main.postDelayed({ flush() }, NAV_GAP_MS)
    }

    private fun finished() {
        if (yielded) { yielded = false; return }
        if (!speaking) return
        speaking = false
        current = null
        if (queue.isEmpty()) { onVoice?.invoke(VoiceState.IDLE); runAfter() } else flush()
    }

    private fun startAndroidTts() {
        if (sysTts != null) return
        sysTts = SysTts(context.applicationContext) { status ->
            androidReady = status == SysTts.SUCCESS
            if (androidReady) sysTts?.language = Locale.KOREAN
            log(if (androidReady) "Android TTS 준비됨" else "Android TTS 없음: 화면 로그만")
            flush()
        }
    }

    fun say(text: String) {
        log("🔊 $text")
        queue.addLast(text)
        flush()
    }

    /** 지금 말하는 문장이 끝난 뒤 cb를 부른다. 말하고 있지 않으면 바로 부른다. 듣기 시작 시점을 맞추는 데 쓴다. */
    fun afterSpeech(cb: () -> Unit) {
        if (!speaking && queue.isEmpty()) cb() else pendingAfter = cb
    }

    private fun runAfter() { val cb = pendingAfter; pendingAfter = null; cb?.invoke() }

    /** 한 번에 한 문장. 내비가 말하는 중이면 기다린다. */
    private fun flush() {
        if (speaking || queue.isEmpty()) return
        if (navSpeaking) { log("내비 안내 중 → 끝나면 말하기"); onVoice?.invoke(VoiceState.WAITING_NAV); return }
        val t = queue.removeFirst()
        when {
            pleosReady -> { speaking = true; current = t; onVoice?.invoke(VoiceState.SPEAKING); pleos.speak(t, true) }
            androidReady -> { sysTts?.speak(t, SysTts.QUEUE_ADD, null, t.hashCode().toString()); flush() }
            else -> { queue.addFirst(t); main.post { runAfter() } }
        }
    }

    fun release() {
        audio.unregisterAudioPlaybackCallback(playback)
        if (pleosReady) pleos.removeEventListener(events)
        pleos.release()
        sysTts?.shutdown()
    }

    companion object {
        /** 내비 안내가 끝나고 우리 말을 시작하기까지 쉬는 시간(ms). */
        private const val NAV_GAP_MS = 500L
    }
}
