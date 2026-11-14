package com.arrival.mode

import android.content.Context

/** 마지막 주차 기록. 차 안에만 저장한다. shortcut: 폰 전송은 서드파티용 차↔폰 SDK가 없어 화면 표시로 대신한다. */
class ParkingMemory(context: Context) {
    private val prefs = context.getSharedPreferences("parking", Context.MODE_PRIVATE)

    data class Record(val lotName: String, val lat: Double, val lng: Double, val address: String,
                      val fee: String, val freeMinutes: Int, val startMillis: Long) {
        fun elapsedMinutes(now: Long = System.currentTimeMillis()) = ((now - startMillis) / 60000).toInt()
    }

    fun save(r: Record) = prefs.edit()
        .putString("lot", r.lotName).putString("addr", r.address).putString("fee", r.fee)
        .putLong("lat", r.lat.toBits()).putLong("lng", r.lng.toBits())
        .putInt("free", r.freeMinutes).putLong("start", r.startMillis).apply()

    fun load(): Record? {
        val lot = prefs.getString("lot", null) ?: return null
        return Record(lot, Double.fromBits(prefs.getLong("lat", 0)), Double.fromBits(prefs.getLong("lng", 0)),
            prefs.getString("addr", "")!!, prefs.getString("fee", "")!!, prefs.getInt("free", 0), prefs.getLong("start", 0))
    }

    /** 운전자가 말한 층·구역("지하 2층 C구역"). 주차 기록과 함께 지운다. */
    fun saveSpot(spot: String) = prefs.edit().putString("spot", spot).apply()
    fun loadSpot(): String? = prefs.getString("spot", null)

    fun clear() = prefs.edit().clear().apply()
}
