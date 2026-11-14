package com.arrival.mode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 시동(부팅) 때 앱을 열지 않아도 도착 모드 서비스를 띄운다. "다음 시동 때 주차 요약"이 이것에 달려 있다.
 * 위치 권한이 없으면 위치 유형 포그라운드 서비스가 시작되지 않으므로 아무것도 하지 않는다(첫 실행에서 화면이 권한을 받는다).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Permissions.required(context)) { Log.i(ArrivalService.TAG, "부팅: 위치 권한 없음, 서비스 시작 안 함"); return }
        runCatching { context.startForegroundService(Intent(context, ArrivalService::class.java)) }
            .onSuccess { Log.i(ArrivalService.TAG, "부팅: 서비스 시작") }
            .onFailure { Log.i(ArrivalService.TAG, "부팅: 서비스 시작 실패 ${it.javaClass.simpleName}: ${it.message}") }
    }
}
