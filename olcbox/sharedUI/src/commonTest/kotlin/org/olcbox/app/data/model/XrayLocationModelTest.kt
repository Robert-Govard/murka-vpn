package org.olcbox.app.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrayLocationModelTest {
    private val config = """{"outbounds":[{"protocol":"vless","tag":"proxy"}]}"""

    @Test
    fun xrayEntrySurvivesNormalizeAndSerialization() {
        val entry = LocationEntry(
            storageId = "nl",
            name = "Нидерланды",
            subscriptionUrl = "https://sub.example/abc",
            kind = LocationConfig.KIND_XRAY,
            xrayConfig = config
        ).normalized()
        assertEquals(LocationConfig.KIND_XRAY, entry.kind)
        assertEquals(config, entry.xrayConfig)
        assertTrue(entry.location.isXray)
        assertTrue(entry.location.isComplete())

        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString(LocationEntry.serializer(), json.encodeToString(LocationEntry.serializer(), entry))
        assertEquals(entry, decoded.normalized())
    }

    @Test
    fun legacyEntryWithoutKindIsOlcRtc() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString(
            LocationEntry.serializer(),
            """{"storage_id":"a","name":"A","endpoint":{"room_id":"r","key":"${"a".repeat(64)}"},"auth_provider":"wbstream"}"""
        ).normalized()
        assertFalse(legacy.location.isXray)
        assertEquals(LocationConfig.KIND_OLCRTC, legacy.location.kind)
        assertTrue(legacy.location.isComplete())
    }

    @Test
    fun emptyXrayConfigIsIncomplete() {
        val entry = LocationEntry(storageId = "x", kind = LocationConfig.KIND_XRAY, xrayConfig = "")
        assertFalse(entry.location.isComplete())
    }
}
