package com.arrival.mode

/** 내비·차량에서 오는 이벤트. 실차(NaviGateway, CarGateway)와 시연(DemoDriver)이 같은 타입을 낸다. */
sealed class ArrivalEvent {
    /** 내비의 현재 목적지와 남은 거리(m). 주기적으로 온다. */
    data class Destination(val name: String, val lat: Double, val lng: Double, val remainM: Int, val remainSec: Int) : ArrivalEvent()
    data class Arrived(val name: String) : ArrivalEvent()
    /** 기어 P. 위치는 내비의 현재 위치. */
    data class Parked(val lat: Double, val lng: Double, val address: String) : ArrivalEvent()
    object GearDrive : ArrivalEvent()
}

/**
 * 도착 모드 상태 기계.
 * 주행 → (남은 거리 ≤ BRIEF_M) 주차 브리핑 → 도착 → (기어 P) 주차 기록 → 다음 시동에 주차 시간·무료 시간 안내.
 */
class ArrivalEngine(
    private val catalog: Catalog,
    private val memory: ParkingMemory,
    private val speak: (String) -> Unit,
    private val log: (String) -> Unit,
    private val onOffer: (ParkingLot?) -> Unit,   // 대안 주차장 제안(없으면 null) → 화면 버튼
) {
    var briefed = false; private set
    var chosen: ParkingLot? = null; private set
    private var destName = ""

    fun onEvent(e: ArrivalEvent) {
        when (e) {
            is ArrivalEvent.Destination -> onDestination(e)
            is ArrivalEvent.Arrived -> { log("도착: ${e.name}"); speak("도착했어요. 주차 후 기어를 P에 두면 위치를 기억해 둘게요.") }
            is ArrivalEvent.Parked -> onParked(e)
            ArrivalEvent.GearDrive -> onDrive()
        }
    }

    private fun onDestination(e: ArrivalEvent.Destination) {
        if (e.name != destName) { destName = e.name; briefed = false; chosen = null }
        if (briefed || (e.remainM > BRIEF_M && e.remainSec > BRIEF_SEC)) return
        briefed = true
        val near = catalog.near(e.lat, e.lng)
        if (near.isEmpty()) { speak("곧 도착이에요. 근처 주차장 정보가 없어요."); onOffer(null); return }
        val main = near.first().first
        // 수용 대수를 아는 곳끼리는 더 큰 곳, 모르면(0) 가장 가까운 다른 곳
        val alt = (near.drop(1).firstOrNull { it.first.capacity > main.capacity } ?: near.drop(1).firstOrNull())?.first
        chosen = main
        val free = if (main.freeMinutes > 0) " ${main.name}은 ${main.freeMinutes}분까지 무료예요." else ""
        val entrance = if (main.entrance.isNotBlank()) " 입구는 ${main.entrance}." else ""
        val msg = StringBuilder("${e.remainSec / 60}분 뒤 도착이에요.$entrance$free")
        if (alt != null) msg.append(" ${near.first { it.first == alt }.second}미터 옆 ${alt.name}도 있어요. ${alt.fee}. 그쪽으로 갈까요?")
        speak(msg.toString())
        onOffer(alt)
    }

    /** "응" → 대안 주차장을 목적지로. 호출자가 내비에 경로를 요청한다. */
    fun accept(alt: ParkingLot) { chosen = alt; speak("${alt.name}으로 안내할게요.") }

    private fun onParked(e: ArrivalEvent.Parked) {
        val lot = chosen ?: catalog.near(e.lat, e.lng, 300).firstOrNull()?.first
        val rec = ParkingMemory.Record(lot?.name ?: "주차 위치", e.lat, e.lng, e.address.ifEmpty { lot?.address ?: "" },
            lot?.fee ?: "요금 정보 없음", lot?.freeMinutes ?: 0, System.currentTimeMillis())
        memory.save(rec)
        log("주차 기록: ${rec.lotName} (${"%.5f".format(e.lat)}, ${"%.5f".format(e.lng)})")
        speak("${rec.lotName}에 주차했어요. 위치를 기억해 둘게요." + if (rec.freeMinutes > 0) " ${rec.freeMinutes}분까지 무료예요." else "")
    }

    /** 시동 후 첫 D단: 주차 끝. 주차 시간과 요금 기준을 알려 주고 기록을 지운다. */
    private fun onDrive() {
        val rec = memory.load() ?: return
        val min = rec.elapsedMinutes()
        val h = min / 60; val m = min % 60
        val time = if (h > 0) "${h}시간 ${m}분" else "${m}분"
        val freeNote = when {
            rec.freeMinutes == 0 -> " 요금은 ${rec.fee}${Reply.ieyo(rec.fee)}."
            min <= rec.freeMinutes -> " ${rec.freeMinutes}분까지 무료라 요금이 없어요."
            else -> " 무료 ${rec.freeMinutes}분을 ${min - rec.freeMinutes}분 넘겼어요. 요금은 ${rec.fee}."
        }
        val spot = memory.loadSpot()?.let { " ${it}에 세워 두셨어요." } ?: ""
        speak("${rec.lotName}에 $time 주차했어요.$spot$freeNote")
        memory.clear()
    }

    /** 남은 거리 BRIEF_M 이하 또는 남은 시간 BRIEF_SEC 이하(도착 10분 전)면 브리핑한다. */
    companion object { const val BRIEF_M = 2000; const val BRIEF_SEC = 600 }
}
