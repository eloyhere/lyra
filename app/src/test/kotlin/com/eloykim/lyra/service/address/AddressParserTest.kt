package com.eloykim.lyra.service.address

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressParserTest {

    @Test
    fun extractsNamePhoneAndAddress() {
        val candidates = AddressParser.extract(
            listOf(
                "张三",
                "13800138000",
                "北京市朝阳区建国路88号",
            ),
        )

        assertEquals(1, candidates.size)
        assertEquals("张三", candidates[0].name)
        assertEquals("13800138000", candidates[0].phone)
        assertEquals("北京市朝阳区建国路88号", candidates[0].detail)
        assertEquals("北京市", candidates[0].region)
    }

    @Test
    fun ignoresTextWithoutAddressMarkers() {
        assertTrue(
            AddressParser.extract(
                listOf("你好", "13800138000", "这是一段普通聊天"),
            ).isEmpty(),
        )
    }

    @Test
    fun ignoresMultipleAddressLinesAsOneCandidate() {
        val candidates = AddressParser.extract(
            listOf(
                "李四",
                "北京市海淀区中关村大街1号",
                "上海市浦东新区世纪大道100号",
            ),
        )

        assertEquals(2, candidates.size)
    }
}
