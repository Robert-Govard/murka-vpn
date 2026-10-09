package org.olcbox.app.telemetry

/** Host of Murka subscriptions: https://<host>/<shortUuid>[/...]. */
internal const val SUBSCRIPTION_HOST = "subgovard.mooo.com"

private val shortUuidRe = Regex("""^https?://${Regex.escape(SUBSCRIPTION_HOST)}(?::\d+)?/([A-Za-z0-9_-]{4,64})(?:[/?#].*)?$""", RegexOption.IGNORE_CASE)

/**
 * sha256(shortUuid) of the first Murka subscription link. The shortUuid itself grants
 * VPN access, so only its hash is sent; the server maps it to the panel user.
 */
internal fun subscriptionHash(urls: List<String>): String? = urls.firstNotNullOfOrNull { url ->
    shortUuidRe.find(url.trim())?.groupValues?.get(1)
}?.let(::sha256Hex)
