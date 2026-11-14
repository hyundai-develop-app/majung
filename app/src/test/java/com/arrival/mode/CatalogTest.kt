package com.arrival.mode

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class CatalogTest {
    private val dept = ParkingLot("현대백화점 판교점 주차장", 37.392799, 127.111991, "", 0, "", 30, "", "건물")
    private val temp = ParkingLot("알파돔 임시 주차장", 37.394143, 127.110328, "", 0, "", 0, "", "임시", weekendOnly = true)

    @Test fun 평일에는_주말전용_주차장을_빼고_찾는다() {
        val near = Catalog.nearIn(listOf(dept, temp), 37.392799, 127.111991, 700, Calendar.MONDAY)
        assertEquals(listOf(dept.name), near.map { it.first.name })
    }

    @Test fun 주말에는_주말전용_주차장도_찾는다() {
        val near = Catalog.nearIn(listOf(dept, temp), 37.392799, 127.111991, 700, Calendar.SATURDAY)
        assertEquals(listOf(dept.name, temp.name), near.map { it.first.name })
    }
}
