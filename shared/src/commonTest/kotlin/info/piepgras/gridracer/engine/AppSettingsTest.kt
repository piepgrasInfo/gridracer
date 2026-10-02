package info.piepgras.gridracer.engine

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppSettingsTest {

    // The app's own configuration: defaults are not written to the file.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun defaultServer_isThePublicServerOverTls() {
        assertEquals("wss://gridracer.piepgras.info/play", AppSettings().multiplayerServerUrl)
        assertTrue(AppSettings().multiplayerServerUrl.startsWith("wss://"))
    }

    @Test
    fun withCurrentServerUrl_movesTheOldPlainAddressAndKeepsTheSession() {
        val stored = AppSettings(
            multiplayerServerUrl = "ws://gridracer.piepgras.info:5521/play",
            sessionToken = "session",
            multiplayerToken = "anonymous",
        )

        val migrated = stored.withCurrentServerUrl()

        assertEquals(AppSettings.PUBLIC_SERVER_URL, migrated.multiplayerServerUrl)
        assertEquals("session", migrated.sessionToken)
        assertEquals("anonymous", migrated.multiplayerToken)
    }

    @Test
    fun withCurrentServerUrl_ignoresSurroundingWhitespace() {
        val stored = AppSettings(multiplayerServerUrl = " ws://gridracer.piepgras.info:5521/play ")

        assertEquals(AppSettings.PUBLIC_SERVER_URL, stored.withCurrentServerUrl().multiplayerServerUrl)
    }

    @Test
    fun withCurrentServerUrl_leavesAnyOtherServerAlone() {
        for (url in listOf("ws://racer.home.arpa:8080/play", "wss://races.example.org/play", "ws://10.0.2.2:8080/play")) {
            val stored = AppSettings(multiplayerServerUrl = url)
            assertEquals(stored, stored.withCurrentServerUrl())
        }
    }

    @Test
    fun aFileThatNeverStoredTheAddress_getsTheNewDefault() {
        // What 1.1 wrote for a user who never touched the server field: no URL at all.
        val old = json.encodeToString(AppSettings(playerName = "Ada"))

        assertTrue("multiplayerServerUrl" !in old)
        assertEquals(AppSettings.PUBLIC_SERVER_URL, json.decodeFromString<AppSettings>(old).multiplayerServerUrl)
    }
}
