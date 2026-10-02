package info.piepgras.gridracer.legal

import android.content.Context
import androidx.annotation.StringRes
import info.piepgras.gridracer.R

/** What the device has decided about a permission - never what the manifest asks for. */
enum class PermissionStatus { GRANTED, DENIED, NOT_REQUESTED }

/**
 * How a permission is granted, which decides how its status is read and whether the user can
 * still change it:
 *
 * - [INSTALL_TIME] - normal or signature permissions, granted when the app is installed. Android
 *   does not list them among the switches, so the item is not tappable.
 * - [RUNTIME] - dangerous permissions the user is asked for. Read with `checkSelfPermission`,
 *   changed on the app's details page in Android settings.
 * - [NOTIFICATIONS] - `POST_NOTIFICATIONS`, read through `areNotificationsEnabled()` because that
 *   is the switch the user actually sees, on every Android version, including the ones without
 *   the permission.
 * - [EXACT_ALARM] - `SCHEDULE_EXACT_ALARM`, a special-access permission with its own settings page.
 * - [OVERLAY] - `SYSTEM_ALERT_WINDOW`, likewise.
 * - [APP_MANAGED] - a grant that lives outside Android's permission system (Health Connect is
 *   the known case). The screen asks an [AppManagedPermission] supplied by the app.
 */
enum class PermissionKind { INSTALL_TIME, RUNTIME, NOTIFICATIONS, EXACT_ALARM, OVERLAY, APP_MANAGED }

/**
 * One item on the Permissions screen: one permission, or one group of permissions that are
 * granted and explained together (fine + coarse location, the foreground-service types).
 *
 * This list, the manifest, `legal/permissions.md` and the Play Data safety form are four faces
 * of one fact. `AppPermissionsTest` holds this list to the manifest; the rest is a release check.
 *
 * @param key the string-resource and test-tag suffix (`permission_<key>_title`, `_body`).
 * @param manifestNames the full constant(s), e.g. `android.permission.RECORD_AUDIO`. Shown to the
 *   user as the part after the last dot.
 * @param grantedRes a statement for the granted state that says more than the generic one, e.g.
 *   which library added an install-time permission. Null uses the generic per-kind statement.
 * @param deniedRes likewise for the denied state.
 * @param anyOf for a group: granted when any member is (location, where approximate-only counts).
 *   Default: granted only when every member is.
 * @param minSdk / [maxSdk] the Android versions on which this entry exists at all. The screen
 *   hides entries outside the device's version; the test checks all of them.
 *
 * @param kind How the permission is granted, which decides how its state is read; see
 *     [PermissionKind].
 * @param titleRes The item's title.
 * @param bodyRes What the permission is used for.
 * @param maxSdk The last Android version on which the entry exists; see `minSdk`.
 */
data class AppPermission(
    val key: String,
    val manifestNames: List<String>,
    val kind: PermissionKind,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    @param:StringRes val grantedRes: Int? = null,
    @param:StringRes val deniedRes: Int? = null,
    val anyOf: Boolean = false,
    val minSdk: Int = 1,
    val maxSdk: Int = Int.MAX_VALUE,
)

/**
 * A permission the app deliberately does not declare, as its own item. "This app cannot see
 * your location" is only reassuring if it is said; absence from a manifest is invisible.
 *
 * @param key The string-resource and test-tag suffix, as for [AppPermission.key].
 * @param titleRes The item's title.
 * @param bodyRes Why the app has no need of it.
 */
data class NeverRequested(
    val key: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
)

/**
 * The answer of an [AppManagedPermission], with an optional statement replacing the generic one.
 *
 * @param status Granted, denied, or not requested.
 * @param statementRes A statement replacing the generic one for that state, or null.
 */
data class AppManagedStatus(
    val status: PermissionStatus,
    @param:StringRes val statementRes: Int? = null,
)

/** Supplied by the app for every [PermissionKind.APP_MANAGED] entry, keyed by [AppPermission.key]. */
interface AppManagedPermission {
    /** Reads the current state of the app-managed grant. */
    suspend fun status(context: Context): AppManagedStatus
    /** Opens wherever the user can change the grant - the app's own screen or the provider's. */
    fun open(context: Context)
}

/**
 * The single list of what the Permissions screen shows, held to the manifest by
 * `AppPermissionsTest`.
 */
object AppPermissions {

    /**
     * Every permission the shipped APK declares - the hand-written ones and the ones libraries
     * merge in at build time - in the order the screen shows them: the ones the user decides
     * first, then the install-time ones.
     */
    val DECLARED: List<AppPermission> = listOf(
        AppPermission(
            key = "notifications",
            manifestNames = listOf("android.permission.POST_NOTIFICATIONS"),
            kind = PermissionKind.NOTIFICATIONS,
            titleRes = R.string.permission_notifications_title,
            bodyRes = R.string.permission_notifications_body,
            grantedRes = R.string.permission_notifications_granted,
            deniedRes = R.string.permission_notifications_denied,
        ),
        AppPermission(
            key = "internet",
            manifestNames = listOf("android.permission.INTERNET"),
            kind = PermissionKind.INSTALL_TIME,
            titleRes = R.string.permission_internet_title,
            bodyRes = R.string.permission_internet_body,
            grantedRes = R.string.permission_internet_granted,
        ),
        AppPermission(
            key = "internal_broadcasts",
            // Generated by the build tools for this applicationId; see legal/permissions.md.
            manifestNames = listOf("info.piepgras.gridracer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            kind = PermissionKind.INSTALL_TIME,
            titleRes = R.string.permission_internal_broadcasts_title,
            bodyRes = R.string.permission_internal_broadcasts_body,
            grantedRes = R.string.permission_internal_broadcasts_granted,
        ),
    )

    /** From the most asked-about downwards. */
    val NEVER_REQUESTED: List<NeverRequested> = listOf(
        NeverRequested(
            key = "camera",
            titleRes = R.string.permission_camera_title,
            bodyRes = R.string.permission_camera_body,
        ),
        NeverRequested(
            key = "microphone",
            titleRes = R.string.permission_microphone_title,
            bodyRes = R.string.permission_microphone_body,
        ),
        NeverRequested(
            key = "location",
            titleRes = R.string.permission_location_title,
            bodyRes = R.string.permission_location_body,
        ),
        NeverRequested(
            key = "contacts",
            titleRes = R.string.permission_contacts_title,
            bodyRes = R.string.permission_contacts_body,
        ),
    )
}
