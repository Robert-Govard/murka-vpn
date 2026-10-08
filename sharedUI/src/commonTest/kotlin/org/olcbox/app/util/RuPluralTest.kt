package org.olcbox.app.util

import kotlin.test.Test
import kotlin.test.assertEquals

class RuPluralTest {
    @Test
    fun picksRussianForm() {
        val forms = listOf(1L, 2L, 4L, 5L, 11L, 12L, 21L, 22L, 25L, 101L, 111L).map {
            "$it ${ruPlural(it, "сервер", "сервера", "серверов")}"
        }
        assertEquals(
            listOf("1 сервер", "2 сервера", "4 сервера", "5 серверов", "11 серверов", "12 серверов",
                "21 сервер", "22 сервера", "25 серверов", "101 сервер", "111 серверов"),
            forms
        )
    }
}
