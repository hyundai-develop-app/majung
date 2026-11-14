package com.arrival.mode

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 도착 모드 본체. 내비·기어·위치 이벤트를 받아 ArrivalEngine을 돌리고 화면 상태를 ArrivalBus로 보낸다.
 * 포그라운드 서비스인 이유: 경유지를 넣으면 내비가 앞으로 나와 앱이 뒤로 가는데, 일반 프로세스는
 * 약 1분 뒤 동결돼 P단·근접 이벤트가 밀린다(에뮬레이터 실측). 위치 유형은 FusedLocation을 쓰기 때문.
 */
class ArrivalService : Service() {
    private lateinit var announcer: Announcer
    private lateinit var navi: NaviGateway
    private lateinit var car: CarGateway
    private lateinit var memory: ParkingMemory
    private lateinit var engine: ArrivalEngine
    private lateinit var listener: Listener
    private val fused by lazy { FusedLocation(applicationContext) }
    private var here: LocationData? = null
    private var dest: ArrivalEvent.Destination? = null
    private var lastGear: VehicleGear? = null
    private val startedAt = SystemClock.elapsedRealtime()
    /** 받아들인 대안 주차장 이름. 내비 목적지가 이것으로 바뀌어도 새 목적지로 보지 않는다. */
    private var accepted: String? = null
    /** onCreate에서 포그라운드 시작에 실패해 아무것도 만들지 않은 경우. */
    private var failed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "도착 모드", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setContentTitle("도착 모드").setContentText("목적지와 주차를 살피는 중")
            .setSmallIcon(R.drawable.ic_launcher_fg).build()
        // 앱 화면이 떠 있을 땐 위치 유형으로 시작한다. 부팅 직후처럼 앱이 화면에 없으면 Android 14가 위치 유형을 막으므로
        // (위치 권한은 "앱 사용 중" 권한이라서, 에뮬레이터 실측) specialUse 유형으로 대신 시작한다.
        // 위치는 Pleos FusedLocation 서비스에서 받으므로 이 경우에도 들어온다(실측).
        val started = runCatching { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
            .onFailure { log("위치 유형 시작 불가(${it.javaClass.simpleName}) → specialUse 유형으로 시작") }
            .recoverCatching { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onSuccess { log("포그라운드 서비스 시작") }
        if (started.isFailure) {
            log("서비스 시작 실패: ${started.exceptionOrNull()?.message}")
            ArrivalBus.postNotice("도착 모드를 시작하지 못했어요 · 위치 권한을 확인해 주세요")
            stopSelf()
            failed = true
            return
        }

        announcer = Announcer(this, ::log).also { it.init() }
        listener = Listener(this, ::log).also { it.init() }
        announcer.onVoice = { ArrivalBus.postVoice(it) }
        listener.onListening = { ArrivalBus.postVoice(VoiceState.LISTENING) }
        listener.onRms = { ArrivalBus.postRms(it) }
        memory = ParkingMemory(this)
        engine = ArrivalEngine(Catalog(this), memory, announcer::say, ::log, ::onOffer)
        navi = NaviGateway(this, ::onNavi, ::log).also { it.connect() }
        car = CarGateway(this, ::onGear, ::log).also { it.connect() }
        runCatching { fused.initialize(); fused.registerFusedLocationCallback(track); log("위치 수신 시작") }
            .onFailure { log("위치 수신 실패: ${it.message}") }

        ArrivalBus.onAccept = ::accept
        ArrivalBus.onUndo = ::undo
        ArrivalBus.onAskSpot = ::askSpot
        ArrivalBus.onGoHome = ::goHome
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun onNavi(e: ArrivalEvent) {
        if (e is ArrivalEvent.Destination) {
            // 대안 주차장을 받아들여 내비 목적지가 그 주차장으로 바뀐 경우: 원래 목적지와 브리핑 상태를 유지한다.
            if (e.name == accepted) return
            val changed = e.name != dest?.name
            dest = e
            val remain = remainText(e.remainM, e.remainSec)
            if (changed) ArrivalBus.post(ArrivalUi(Screen.WAITING, e.name, remain))
            // 집 안내를 요청한 직후엔 내비가 아직 이전 목적지를 줄 수 있다. 화면에 띄운 목적지의 남은 거리만 갱신한다.
            else if (ArrivalBus.last.screen == Screen.WAITING && ArrivalBus.last.destName == e.name)
                ArrivalBus.post(ArrivalBus.last.copy(remainText = remain))
        }
        engine.onEvent(e)
    }

    /**
     * 실차에서는 내비 남은 거리로 브리핑한다. 에뮬레이터 내비는 판교에 고정돼 남은 거리가 줄지 않으므로
     * GPS(FusedLocation)가 목적지 NEAR_M 안에 들어와도 같은 이벤트를 낸다.
     */
    private val track = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            here = location
            val d = dest ?: return
            if (engine.briefed) return
            val m = Catalog.haversineM(location.latitude, location.longitude, d.lat, d.lng).toInt()
            if (m <= NEAR_M) engine.onEvent(d.copy(remainM = m, remainSec = m * 3600 / 30_000))
        }

        override fun onError(e: Exception) { log("위치 오류: ${e.message}") }
    }

    private fun onOffer(alt: ParkingLot?) {
        val d = dest ?: return
        val main = engine.chosen
        val altDist = alt?.let { Catalog.haversineM(it.lat, it.lng, d.lat, d.lng).toInt() } ?: 0
        ArrivalBus.post(ArrivalBus.last.copy(screen = Screen.BRIEF, main = main, alt = alt, altDistM = altDist))
        if (alt != null) announcer.afterSpeech { hear(REPLY_PCM, ::onReply) }
    }

    /** 브리핑 질문("그쪽으로 갈까요?")에 대한 대답. 실측상 한 단어는 오인식이 잦아 여러 표현을 받는다. */
    private fun onReply(text: String) {
        when (Reply.yesNo(text)) {
            true -> accept()
            false -> { announcer.say("그대로 안내할게요."); ArrivalBus.post(ArrivalBus.last.copy(screen = Screen.WAITING)) }
            null -> missed(if (text.isNotBlank()) "잘 못 들었어요. 그쪽으로 가려면 카드를 눌러 주세요." else null)
        }
    }

    /** P단 뒤 층·구역 묻기. 화면의 "듣고 있어요"·"다시 말해주세요"를 눌러도 다시 듣는다. */
    private fun askSpot() {
        announcer.say("몇 층 어디에 세우셨어요?")
        announcer.afterSpeech { hear(SPOT_PCM, ::onSpot) }
    }

    private fun onSpot(text: String) {
        val spot = Reply.spot(text)
        if (spot == null) {
            missed(if (text.isNotBlank()) "잘 못 들었어요. 다시 말해 주세요." else null)
            return
        }
        memory.saveSpot(spot)
        announcer.say("$spot${Reply.eulo(spot)} 기억해 둘게요.")
        ArrivalBus.post(ArrivalBus.last.copy(screen = Screen.SPOT_SAVED, spot = spot))
    }

    /** 못 알아들음: 안내할 말이 있으면 말한 뒤, 화면 카드를 "다시 말해 주세요"로. */
    private fun missed(say: String?) {
        if (say != null) announcer.say(say)
        announcer.afterSpeech { ArrivalBus.postVoice(VoiceState.MISSED) }
    }

    /**
     * 마이크로 듣는다. 앱 파일 폴더에 시연용 PCM(16kHz/16bit/mono)이 있으면 그 파일을 대신 넣고 지운다.
     * 에뮬레이터 마이크 없이 같은 인식 경로를 시험하기 위한 것이다.
     */
    private fun hear(injectName: String, onText: (String) -> Unit) {
        val f = java.io.File(filesDir, injectName)
        if (!f.exists() && !Permissions.mic(this)) { ArrivalBus.postVoice(VoiceState.IDLE); return }
        if (f.exists()) { log("시연용 음성 파일 사용: $injectName"); listener.listenFile(f) { f.delete(); onText(it) } }
        else listener.listen(onResult = onText)
    }

    private fun accept() {
        val alt = ArrivalBus.last.alt ?: return
        accepted = alt.name
        engine.accept(alt)
        navi.routeTo(alt)
        ArrivalBus.post(ArrivalBus.last.copy(screen = Screen.WAYPOINT))
    }

    private fun undo() {
        val d = dest ?: return
        accepted = null
        navi.routeTo(ParkingLot(d.name, d.lat, d.lng, "", 0, "", 0, "", ""))
        ArrivalBus.post(ArrivalBus.last.copy(screen = Screen.BRIEF))
    }

    /** 05 화면 '집' + '안내 시작': 내비 목적지를 집으로 바꾼다. 남은 거리는 다음 내비 조회(10초 이내)에서 채워진다. */
    private fun goHome() {
        navi.routeTo(HOME)
        ArrivalBus.post(ArrivalUi(Screen.WAITING, HOME.name, "경로를 찾고 있어요"))
    }

    private fun onGear(g: VehicleGear) {
        val prev = lastGear
        lastGear = g
        // 리스너 등록 직후 SDK가 이전 세션의 값(D)을 한 번 보내고 곧바로 현재 값(P)을 보낸다(에뮬레이터 실측).
        // 이것을 D→P 주차로 오인하지 않도록 연결 직후 GEAR_WARMUP_MS 동안은 기준값만 갱신한다.
        if (SystemClock.elapsedRealtime() - startedAt < GEAR_WARMUP_MS) return
        if (g == prev) return
        when (g) {
            VehicleGear.GEAR_PARK -> if (prev != null) onParked()
            VehicleGear.GEAR_DRIVE -> onDrive()
            else -> Unit
        }
    }

    /** 주차 위치는 내비 위치가 아니라 GPS. 에뮬레이터 내비 위치는 판교 고정이라 GPS만 실제 이동을 따라간다. */
    private fun onParked() {
        val at = here
        if (at == null) { log("주차: 위치 없음"); return }
        engine.onEvent(ArrivalEvent.Parked(at.latitude, at.longitude, ""))
        val rec = memory.load()
        ArrivalBus.post(ArrivalBus.last.copy(screen = Screen.ASK_SPOT, parkedLot = rec?.lotName,
            parkedTime = rec?.let { "${clock(it.startMillis)} 주차 · 위치 저장됨" }, spot = null))
        askSpot()
    }

    private fun onDrive() {
        val rec = memory.load() ?: return
        // 주차한 곳으로 가던 길안내가 남아 있어도 새 목적지로 보지 않는다.
        accepted = rec.lotName
        ArrivalBus.post(ArrivalBus.last.copy(screen = Screen.SUMMARY, parkedLot = rec.lotName,
            elapsedMin = rec.elapsedMinutes(), freeMin = rec.freeMinutes))
        engine.onEvent(ArrivalEvent.GearDrive)
    }

    private fun remainText(m: Int, sec: Int) = "도착까지 ${sec / 60}분 · ${"%.1f".format(m / 1000.0)}km"
    private fun clock(ms: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))
    private fun log(msg: String) = Log.i(TAG, msg)

    override fun onDestroy() {
        if (failed) { super.onDestroy(); return }
        ArrivalBus.onAccept = null
        ArrivalBus.onUndo = null
        ArrivalBus.onAskSpot = null
        ArrivalBus.onGoHome = null
        listener.release()
        runCatching { fused.unregisterFusedLocationCallback(track); fused.release() }
        navi.release()
        car.release()
        announcer.release()
        super.onDestroy()
    }

    companion object {
        const val TAG = "ArrivalMode"
        private const val CHANNEL = "arrival"
        /** GPS 기준 "곧 도착" 반경(m). */
        const val NEAR_M = 1500
        private const val GEAR_WARMUP_MS = 2000L
        private const val REPLY_PCM = "demo_reply.pcm"
        private const val SPOT_PCM = "demo_spot.pcm"
        // shortcut: 시연용 고정 집 주소(화서역), 사용자별 집 설정이 생기면 그 값으로 교체
        private val HOME = ParkingLot("화서역", 37.2840, 126.9896, "경기 수원시 팔달구 덕영대로 692", 0, "", 0, "", "")
    }
}
