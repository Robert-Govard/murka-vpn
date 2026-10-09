package org.olcbox.app.vpn

/**
 * Russian apps that refuse to work while a VPN is on (banks, Госуслуги, marketplaces),
 * kept outside the Android VPN so they see the plain connection. Packages that are not
 * installed are skipped when the tunnel starts.
 */
object RussianApps {
    const val BYPASS_MODE = "bypass_selected"

    val packages: List<String> = listOf(
        // Banks and payments
        "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android", "ru.gazprombank.android.mobilebank.app", "ru.ozon.fintech.finance",
        "ru.nspk.mirpay", "ru.raiffeisen.rmobile", "ru.rosbank.android", "ru.psbank.mobile",
        // Государство
        "ru.rostel", "ru.fns.lkfl", "ru.mos.app",
        // Marketplaces and services
        "ru.ozon.app.android", "com.wildberries.ru", "com.avito.android", "ru.vk.store",
        "ru.yandex.market", "ru.hh.android", "ru.auto.ara", "ru.cian.main", "ru.domclick.mortgage",
        "ru.dublgis.dgismobile", "ru.rzd.pass", "ru.aviasales", "com.octopod.russianpost.client.android",
        // Yandex
        "ru.yandex.searchplugin", "com.yandex.browser", "ru.yandex.taxi", "ru.yandex.yandexmaps",
        "ru.yandex.yandexnavi", "ru.yandex.music", "ru.kinopoisk", "ru.yandex.mail", "ru.yandex.disk",
        // VK and messengers
        "com.vkontakte.android", "com.vk.vkvideo", "ru.ok.android", "ru.mail.mailapp", "ru.oneme.app",
        // Video
        "ru.rutube.app", "ru.ivi.client", "ru.more.play",
        // Mobile operators
        "ru.mts.mymts", "ru.megafon.mlk", "ru.beeline.services", "ru.tele2.mytele2",
    )

    /**
     * Split tunnel mode and bypass list from stored preferences. Until the user picks
     * a mode, Russian apps bypass the VPN; an explicit choice is always kept.
     */
    fun resolveSplitTunnel(storedMode: String?, storedBypass: Set<String>?): Pair<String, Set<String>> =
        if (storedMode == null) BYPASS_MODE to packages.toSet()
        else storedMode to storedBypass.orEmpty()
}
