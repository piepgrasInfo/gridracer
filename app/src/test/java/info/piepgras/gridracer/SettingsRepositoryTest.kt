package info.piepgras.gridracer

import info.piepgras.gridracer.engine.AppSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SettingsRepositoryTest {

    private lateinit var storageFile: File

    @Before
    fun setUp() {
        storageFile = File.createTempFile("settings", ".json")
        storageFile.delete()
    }

    @After
    fun tearDown() {
        storageFile.delete()
    }

    // Dispatchers.Unconfined runs the launched save() inline, so the file is written on return.
    private fun repository() = SettingsRepository(storageFile, Dispatchers.Unconfined)

    @Test
    fun freshInstall_connectsToThePublicServerOverTls() {
        assertEquals("wss://gridracer.piepgras.info/play", repository().settings.value.multiplayerServerUrl)
    }

    @Test
    fun aStoredPlainAddressOfThePublicServer_isMovedToTls_andStaysLoggedIn() {
        // What an earlier version wrote for a user who had typed the default address in by hand.
        storageFile.writeText(
            """{"multiplayerServerUrl":"ws://gridracer.piepgras.info:5521/play","sessionToken":"s3ss10n","accountDisplayName":"Ada"}""",
        )

        val settings = repository().settings.value

        assertEquals(AppSettings.PUBLIC_SERVER_URL, settings.multiplayerServerUrl)
        assertEquals("s3ss10n", settings.sessionToken)
        assertEquals("Ada", settings.accountDisplayName)
    }

    @Test
    fun aSelfHostedServer_isKeptAsItIs() {
        storageFile.writeText("""{"multiplayerServerUrl":"ws://racer.home.arpa:8080/play"}""")

        assertEquals("ws://racer.home.arpa:8080/play", repository().settings.value.multiplayerServerUrl)
    }

    @Test
    fun theMigratedAddress_survivesTheNextSave() {
        storageFile.writeText("""{"multiplayerServerUrl":"ws://gridracer.piepgras.info:5521/play"}""")
        repository().update { it.copy(playerName = "Ada") }

        assertEquals(AppSettings.PUBLIC_SERVER_URL, repository().settings.value.multiplayerServerUrl)
    }
}
