package com.arrival.mode

import android.content.Context
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener

/** Vehicle SDK 기어 상태. 퍼미션: CAR_DRIVE. */
class CarGateway(context: Context, private val onGear: (VehicleGear) -> Unit, private val log: (String) -> Unit) {
    private val vehicle = Vehicle(context.applicationContext)
    private val gear get() = VehicleAccess.gear(vehicle)
    private val listener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { log("기어 이벤트: $gear"); gear?.let(onGear) }
        override fun onFailed(e: Exception) = log("기어 리스너 오류: ${e.message}")
    }

    fun connect() {
        runCatching {
            vehicle.initialize()
            // 리스너는 서비스가 첫 응답을 준 뒤 등록한다. initialize 직후 등록하면 이벤트가 안 온다(에뮬레이터 실측).
            gear.getCurrentGearSelectionState(
                { g -> log("차량 서비스 연결됨, 현재 기어 $g"); g?.let(onGear); gear.registerGearSelection(listener); log("기어 리스너 등록") },
                { e -> log("차량 기어 읽기 실패: ${e.message}") },
            )
        }.onFailure { log("차량 서비스 연결 실패: ${it.message}") }
    }

    fun release() {
        runCatching { gear.unregisterGearSelection(listener) }
        runCatching { vehicle.release() }
    }
}
