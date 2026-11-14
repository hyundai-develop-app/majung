package com.arrival.mode

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class ParkingLot(
    val name: String, val lat: Double, val lng: Double, val address: String,
    val capacity: Int, val fee: String, val freeMinutes: Int, val entrance: String, val kind: String,
    /** 토·일에만 여는 곳(백화점 주말 임시 주차장 등). 공휴일은 아직 판단하지 않는다. */
    val weekendOnly: Boolean = false,
) {
    /** 이 요일(Calendar.DAY_OF_WEEK)에 운영하는지. */
    fun openOn(dayOfWeek: Int) = !weekendOnly || dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
}

/** 목적지 주변 주차장 후보. shortcut: 번들 JSON 10곳, 공공 주차장 데이터로 바꿀 때 업그레이드. */
class Catalog(context: Context) {
    val parking: List<ParkingLot> = JSONObject(context.assets.open("parking.json").bufferedReader().readText())
        .getJSONArray("parking").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ParkingLot(o.getString("name"), o.getDouble("lat"), o.getDouble("lng"), o.getString("address"),
                    o.getInt("capacity"), o.getString("fee"), o.getInt("freeMinutes"), o.getString("entrance"), o.getString("kind"),
                    o.optBoolean("weekendOnly", false))
            }
        }

    /** 목적지에서 radiusM 안에 있고 오늘 운영하는 주차장, 가까운 순. */
    fun near(lat: Double, lng: Double, radiusM: Int = 700,
             dayOfWeek: Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)): List<Pair<ParkingLot, Int>> =
        nearIn(parking, lat, lng, radiusM, dayOfWeek)

    companion object {
        /** near()의 계산 부분. Context 없이 단위 테스트하려고 분리했다. */
        fun nearIn(lots: List<ParkingLot>, lat: Double, lng: Double, radiusM: Int, dayOfWeek: Int): List<Pair<ParkingLot, Int>> =
            lots.filter { it.openOn(dayOfWeek) }
                .map { it to haversineM(lat, lng, it.lat, it.lng).toInt() }
                .filter { it.second <= radiusM }.sortedBy { it.second }

        fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
            return r * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
