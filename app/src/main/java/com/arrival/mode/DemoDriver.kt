package com.arrival.mode

import android.os.Handler
import android.os.Looper

/** 코엑스 도착 시나리오를 시간차로 재생한다. */
class DemoDriver(private val sink: (ArrivalEvent) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        stop()
        val lat = 37.5125; val lng = 127.0590
        fun at(ms: Long, e: ArrivalEvent) = handler.postDelayed({ sink(e) }, ms)
        at(0, ArrivalEvent.Destination("코엑스", lat, lng, 5200, 780))
        at(3000, ArrivalEvent.Destination("코엑스", lat, lng, 3100, 540))
        at(6000, ArrivalEvent.Destination("코엑스", lat, lng, 1900, 420))
        at(16000, ArrivalEvent.Arrived("코엑스"))
        at(20000, ArrivalEvent.Parked(37.5118, 127.0596, "서울 강남구 영동대로 513 지하"))
    }

    fun stop() = handler.removeCallbacksAndMessages(null)
}
