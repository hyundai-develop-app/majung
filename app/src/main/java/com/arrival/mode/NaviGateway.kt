package com.arrival.mode

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/**
 * 차량 내비의 현재 목적지를 읽는다. 목적지 검색은 내비가 하고, 이 앱은 결과만 받는다.
 * 퍼미션: NAVI_ROUTE(getDestinationInfo, getCurrentLocationInfo), NAVI_ROUTE_SEARCH(requestRoute).
 */
class NaviGateway(context: Context, private val sink: (ArrivalEvent) -> Unit, private val log: (String) -> Unit) {
    private val navi = NaviHelper(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    var connected = false; private set
    private var onLocation: ((CurrentLocationInfo) -> Unit)? = null

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            if (destinationInfo.name.isEmpty()) return
            log("내비 목적지: ${destinationInfo.name} 남은 ${destinationInfo.remainDistance}m")
            sink(ArrivalEvent.Destination(destinationInfo.name, destinationInfo.latitude, destinationInfo.longitude,
                destinationInfo.remainDistance, destinationInfo.remainTime))
        }
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            onLocation?.invoke(currentLocationInfo); onLocation = null
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            sink(ArrivalEvent.Arrived(destinationArrivedInfo.name))
        override fun onError(errorCode: NaviErrorCode) = log("내비 오류: $errorCode")
    }

    private val poll = object : Runnable {
        override fun run() { navi.getDestinationInfo(); handler.postDelayed(this, POLL_MS) }
    }

    fun connect() {
        navi.initialize(
            onConnected = { connected = true; navi.addListener(listener); handler.post(poll); log("내비 서비스 연결됨, 목적지 ${POLL_MS / 1000}초마다 확인") },
            onFailed = { t -> log("내비 서비스 연결 실패: ${t.message}") },
        )
    }

    fun requestLocation(cb: (CurrentLocationInfo) -> Unit) {
        if (!connected) return
        onLocation = cb; navi.getCurrentLocationInfo()
    }

    fun routeTo(lot: ParkingLot) {
        if (!connected) { log("내비 미연결"); return }
        navi.requestRoute(RouteInfo(latitude = lot.lat, longitude = lot.lng, displayLatitude = null, displayLongitude = null,
            poiName = lot.name, poiId = "", address = lot.address, poiSubId = "0"))
        log("내비: 목적지 변경 → ${lot.name}")
    }

    fun release() {
        handler.removeCallbacks(poll)
        if (connected) navi.removeListener(listener)
        navi.release()
    }

    companion object { const val POLL_MS = 10_000L }
}
