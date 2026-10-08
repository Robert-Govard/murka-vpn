package org.olcbox.app.util

/** Russian plural form for [n]: 1 сервер, 2 сервера, 5 серверов. */
fun ruPlural(n: Long, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1L -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

fun ruPlural(n: Int, one: String, few: String, many: String): String = ruPlural(n.toLong(), one, few, many)
