package info.piepgras.gridracer.engine

import kotlinx.serialization.Serializable

/**
 * How the pre-race placement phase orders the players picking a start slot.
 *
 * [SEQUENTIAL] keeps the configured player order; [RANDOM] shuffles it once per race, so nobody
 * gets the best slot every time just by being player one. See [Placement].
 */
@Serializable
enum class PlacementOrder { SEQUENTIAL, RANDOM }

/**
 * Every user preference the app persists, plus the device's online identity.
 *
 * Shared rather than app-only so the defaults are stated once, next to the type, and the JSON file
 * the app writes can be decoded by anything that reads this module. Every field has a default, so a
 * settings file written by an older version still decodes after a field is added.
 *
 * @property placementOrder Who picks a starting slot first in a local race.
 * @property soundEffectsEnabled Whether race sound effects play. Off by default.
 * @property crashReportingEnabled Whether crash reports may be sent. Off until the user opts in.
 * @property legalDisclaimerAccepted Whether the first-run disclaimer has been accepted; the app
 *     shows nothing else until it has.
 */
@Serializable
data class AppSettings(
    val placementOrder: PlacementOrder = PlacementOrder.RANDOM,
    val soundEffectsEnabled: Boolean = false,
    val crashReportingEnabled: Boolean = false,
    val legalDisclaimerAccepted: Boolean = false,
    /**
     * WebSocket URL for the multiplayer server. Defaults to the public GridRacer server, over
     * TLS; can be overridden in settings for local development or a self-hosted instance, where
     * plain `ws://` is allowed and the app warns that the connection is not encrypted.
     */
    val multiplayerServerUrl: String = PUBLIC_SERVER_URL,
    /**
     * Whether the crash-reporting choice has been put to the user yet. Tracked separately
     * from [legalDisclaimerAccepted] so installs that predate the crash-reporting feature -
     * which are grandfathered past the disclaimer, see MainActivity - are still asked once,
     * since for them it is a change in what the app does with its internet permission.
     */
    val crashReportingPromptShown: Boolean = false,
    /**
     * Persistent server-issued player token used for reconnects and, later, account linking.
     * Stored as part of device-bound identity until accounts/auth are fully implemented.
     */
    val multiplayerToken: String? = null,
    /**
     * The name this device races under online. Persisted so hosting or joining doesn't start
     * with retyping it every time; blank falls back to the server-side default ("Host"/"Player").
     */
    val playerName: String = "",
    /**
     * Server-issued session token for a logged-in account. Presented to `Identify` on
     * connect so the server attributes this device to its account; cleared by logout.
     * Distinct from [multiplayerToken], which is the anonymous fallback when this is null.
     */
    val sessionToken: String? = null,
    /**
     * The last known display name of the logged-in account, cached so the UI can show it
     * before the server replies to `Identify`. Cleared by logout.
     */
    val accountDisplayName: String? = null,
    /**
     * Whether the notification permission has been put to the user yet. Android re-shows the
     * prompt on every request until it has been refused twice, so asking on each launch pesters
     * someone who already said no; [MainActivity] asks once and the permissions screen is where
     * they can change their mind afterwards.
     */
    val notificationPromptShown: Boolean = false,
    /**
     * Dark theme for the whole app, the race track included. On by default: the track used to be
     * a near-white sheet whatever the app's theme, which is tiring at night and is what made the
     * grid points hard to pick out - faint grey dots on near-white is the lowest-contrast pairing
     * on the board. Defaulting a *new* preference to true also means an existing install picks up
     * the dark look on update, which is the intent; the switch in Settings turns it off.
     */
    val darkMode: Boolean = true,
    /**
     * Whether a new race starts with the gravel trap switched on - the last choice made on the
     * setup screen. On by default: the trap forgives a car that runs wide instead of crashing it,
     * which is the friendlier rule for someone who has not met it yet.
     */
    val gravelTrapEnabled: Boolean = true,
    /**
     * The level a seat starts on when it is switched to the computer - the last level picked on
     * the setup screen. NORMAL by default: EASY is the old opponent, which most players outgrow
     * in a race or two, and BEST is one tap away.
     */
    val defaultAiLevel: AiLevel = AiLevel.NORMAL,
) {
    /**
     * These settings with a stored address of the public server moved to [PUBLIC_SERVER_URL].
     *
     * Only an install that saved the old address explicitly needs this: the settings file omits
     * values equal to their default, so an install that never edited the field picks up the new
     * default on its own. The session token is kept - it is the same server behind a new address.
     */
    fun withCurrentServerUrl(): AppSettings =
        if (multiplayerServerUrl.trim() in LEGACY_PUBLIC_SERVER_URLS) copy(multiplayerServerUrl = PUBLIC_SERVER_URL) else this

    companion object {
        /** The public GridRacer server, reached over TLS through the reverse proxy in front of it. */
        const val PUBLIC_SERVER_URL = "wss://gridracer.piepgras.info/play"

        /**
         * Addresses earlier versions used for the public server. Up to 1.2 the app talked to it in
         * plain `ws://` on port 5521, which stays open for those versions until they update.
         */
        val LEGACY_PUBLIC_SERVER_URLS = setOf("ws://gridracer.piepgras.info:5521/play")
    }
}
