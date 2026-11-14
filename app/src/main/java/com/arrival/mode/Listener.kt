package com.arrival.mode

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Locale

/**
 * 짧은 음성 응답 듣기. SpeechToText 3.3.1 ON_DEVICE(앱 등록 불필요).
 * 한 번에 하나만 듣고, 최종 결과나 시간 초과(빈 문자열) 중 먼저 온 것을 돌려준다.
 */
class Listener(context: Context, private val log: (String) -> Unit) {
    private val stt = SpeechToText(context.applicationContext, Mode.ON_DEVICE)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: ((String) -> Unit)? = null
    /** 마지막 중간 결과. 엔진이 최종 결과 전에 오류로 끊길 때(에뮬레이터 실측) 이것을 쓴다. */
    private var partial = ""
    private val timeout = Runnable { finish(partial) }
    /** 듣기 시작할 때. 화면의 음성 카드가 쓴다. */
    var onListening: (() -> Unit)? = null
    /** 듣는 동안 들어오는 소리 크기. 화면 파형을 움직이는 데 쓴다. */
    var onRms: ((Double) -> Unit)? = null

    private val results = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) { main.post { if (isFinal) finish(text) else partial = text } }
        override fun onUpdatedEpdData(start: Long, end: Long) {}
        override fun onStartedRecognition() {}
        override fun onEndedRecognition() {}
        override fun onError(errorType: ErrorType) { log("음성 인식 오류: $errorType, 중간 결과 \"$partial\" 사용"); main.post { finish(partial) } }
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) { main.post { if (pending != null) onRms?.invoke(rms) } }
    }

    fun init() {
        stt.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                ready = true
                stt.addListener(results)
                stt.setLocale(Locale.KOREA)
                log("음성 인식 준비됨 (ON_DEVICE)")
            }
            override fun onFailure(errorType: ErrorType) { log("음성 인식 초기화 실패: $errorType") }
        })
    }

    /** 마이크로 듣는다. 결과는 메인 스레드에서 한 번 온다. */
    fun listen(timeoutMs: Long = 6000, onResult: (String) -> Unit) {
        if (!ready) { onResult(""); return }
        begin(timeoutMs, onResult)
        stt.request()
    }

    /**
     * 시연·테스트용: 16kHz/16bit/mono PCM 파일을 마이크 대신 넣는다.
     * 에뮬레이터 마이크 없이 같은 인식 경로를 확인할 때 쓴다.
     */
    fun listenFile(pcm: File, onResult: (String) -> Unit) {
        if (!ready) { onResult(""); return }
        begin(8000, onResult)
        stt.request()
        val bytes = pcm.readBytes()
        Thread {
            bytes.toList().chunked(CHUNK).forEach { stt.sendAudio(it.toByteArray()); Thread.sleep(100) }
            stt.completeAudioSend()
        }.start()
    }

    private fun begin(timeoutMs: Long, onResult: (String) -> Unit) {
        pending?.invoke("")
        pending = onResult
        partial = ""
        onListening?.invoke()
        main.postDelayed(timeout, timeoutMs)
    }

    private fun finish(text: String) {
        main.removeCallbacks(timeout)
        val cb = pending ?: return
        pending = null
        runCatching { stt.stop() }
        log("들은 말: \"$text\"")
        cb(text)
    }

    fun release() {
        main.removeCallbacks(timeout)
        if (ready) stt.removeListener(results)
        stt.release()
    }

    companion object {
        /** 100ms 분량(16000Hz × 2바이트 × 0.1초). */
        private const val CHUNK = 3200
    }
}

/** 짧은 응답 해석. 실측상 한 단어 응답은 오인식이 잦아 여러 표현을 받는다. */
object Reply {
    private val YES = listOf("응", "네", "예", "그래", "좋아", "갈게", "가자", "그쪽", "오케이", "콜")
    private val NO = listOf("아니", "싫어", "됐어", "그냥", "말고")

    /** true=수락, false=거절, null=모름 */
    fun yesNo(text: String): Boolean? {
        val t = text.replace(" ", "")
        if (NO.any { it in t }) return false
        if (YES.any { it in t }) return true
        return null
    }

    private val NUM = mapOf("일" to 1, "이" to 2, "삼" to 3, "사" to 4, "오" to 5, "육" to 6, "칠" to 7)
    private val LETTER = mapOf("에이" to "A", "비" to "B", "씨" to "C", "시" to "C", "디" to "D", "이" to "E", "에프" to "F", "지" to "G")
    /** "구역" 앞 첫 음절로 알파벳 추정. 실측상 "씨 구역"이 "식기구역"처럼 들려 첫 음절만 본다. */
    private val FIRST = mapOf('에' to "A", '비' to "B", '씨' to "C", '시' to "C", '식' to "C", '디' to "D", '지' to "G")

    /** 받침 있으면 "으로", 없거나 ㄹ받침이면 "로". */
    fun eulo(word: String): String {
        val c = word.lastOrNull() ?: return "로"
        if (c !in '가'..'힣') return "로"
        val jong = (c - '가') % 28
        return if (jong == 0 || jong == 8) "로" else "으로"
    }

    /** 받침 있으면 "이에요", 없으면 "예요". */
    fun ieyo(word: String): String {
        val c = word.lastOrNull() ?: return "이에요"
        if (c !in '가'..'힣') return "이에요"
        return if ((c - '가') % 28 == 0) "예요" else "이에요"
    }

    /** "지하 2층 C구역", "비투 씨구역" 같은 말을 "지하 2층 C구역"으로. 못 알아들으면 null. */
    fun spot(text: String): String? {
        val t = text.replace(" ", "").uppercase(Locale.ROOT)
        val floor = Regex("지하(\\d|[일이삼사오육칠])층").find(t)?.groupValues?.get(1)?.let { it.toIntOrNull() ?: NUM[it] }?.let { "지하 ${it}층" }
            ?: Regex("B(\\d)").find(t)?.groupValues?.get(1)?.let { "지하 ${it}층" }
            ?: Regex("(\\d+)층").find(t)?.groupValues?.get(1)?.let { "${it}층" }
        val zone = Regex("([A-Z])구역").find(t)?.groupValues?.get(1)
            ?: LETTER.entries.firstOrNull { "${it.key}구역" in t }?.value
            ?: Regex("층(.{1,3})구역").find(t)?.groupValues?.get(1)?.firstOrNull()?.let { FIRST[it] }
        return listOfNotNull(floor, zone?.let { "${it}구역" }).joinToString(" ").ifEmpty { null }
    }
}
