package com.arrival.mode

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyTest {
    @Test fun 수락과_거절을_구분한다() {
        assertEquals(true, Reply.yesNo("응 그쪽으로 갈게"))
        assertEquals(true, Reply.yesNo("응 그쪽"))
        assertEquals(false, Reply.yesNo("아니 그냥 가자"))
        assertEquals(null, Reply.yesNo("음"))
    }

    // 에뮬레이터에서 실제로 인식된 문장들
    @Test fun 층과_구역을_읽는다() {
        assertEquals("지하 2층 C구역", Reply.spot("지하 2층 C구역"))
        assertEquals("지하 2층 C구역", Reply.spot("지하 이층 식기구역"))
        assertEquals("지하 2층", Reply.spot("지하 2층 119년"))
        assertEquals("지하 2층", Reply.spot("지하 이층 국력"))
        assertEquals(null, Reply.spot("잘 모르겠어"))
    }

    @Test fun 조사를_고른다() {
        assertEquals("으로", Reply.eulo("알파돔 임시 주차장"))
        assertEquals("로", Reply.eulo("지하 2층 C구역".dropLast(2) + "자리"))
        assertEquals("예요", Reply.ieyo("무료"))
        assertEquals("이에요", Reply.ieyo("10분당 1,000원"))
    }
}
