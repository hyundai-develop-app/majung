package com.arrival.mode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** 도착 모드 권한. 위치는 필수(위치 유형 포그라운드 서비스·주차 위치), 마이크·알림은 없어도 화면 탭으로 쓸 수 있다. */
object Permissions {
    val REQUIRED = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, "pleos.car.permission.FUSED_LOCATION")
    val OPTIONAL = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    val ALL = REQUIRED + OPTIONAL

    fun required(c: Context) = REQUIRED.all { granted(c, it) }
    fun mic(c: Context) = granted(c, Manifest.permission.RECORD_AUDIO)
    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
