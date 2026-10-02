package info.piepgras.gridracer.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.LifecycleResumeEffect
import info.piepgras.gridracer.R
import info.piepgras.gridracer.legal.AppManagedPermission
import info.piepgras.gridracer.legal.AppManagedStatus
import info.piepgras.gridracer.legal.AppPermission
import info.piepgras.gridracer.legal.AppPermissions
import info.piepgras.gridracer.legal.PermissionKind
import info.piepgras.gridracer.legal.PermissionStatus

/**
 * The house Permissions screen: one list item per permission the app declares, then the ones it
 * never asks for. The facts come from [AppPermissions] and the `permission_*` strings; this file
 * is only how they are shown, and it is the same in every app so a user who has read one has
 * read them all.
 *
 * Each item states what the **device** has decided - the coloured tag - never what the manifest
 * asks for. A permission the user can still change is one tap from the Android settings page
 * that changes it; one they cannot (granted at install, or never requested) is not tappable,
 * because a tap that opens a page without a switch is a dead end dressed up as an action.
 *
 * Status is read from the system and read again on every resume: the user leaves for Android
 * settings, flips a switch and comes back, and a screen still showing the state it was composed
 * with would be reporting the past.
 *
 * @param appManaged status and settings for every [PermissionKind.APP_MANAGED] entry, keyed by
 *   [AppPermission.key]. Grants Android does not manage (Health Connect) come from here.
 */
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    appManaged: Map<String, AppManagedPermission> = emptyMap(),
) {
    val context = LocalContext.current
    val declared = remember {
        AppPermissions.DECLARED.filter { Build.VERSION.SDK_INT in it.minSdk..it.maxSdk }
    }
    // Seeded eagerly so the first frame is already right, then re-read on every resume.
    var statuses by remember { mutableStateOf(context.statusOfAll(declared)) }
    var managed by remember { mutableStateOf(emptyMap<String, AppManagedStatus>()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        statuses = context.statusOfAll(declared)
        refreshKey++
        onPauseOrDispose {}
    }
    LaunchedEffect(refreshKey) {
        managed = declared
            .filter { it.kind == PermissionKind.APP_MANAGED }
            .mapNotNull { p -> appManaged[p.key]?.let { p.key to it.status(context) } }
            .toMap()
    }

    // contentPadding = 0: the list applies its own, like every other scrolling screen here.
    ScreenScaffold(title = stringResource(R.string.nav_permissions), onBack = onBack, contentPadding = 0.dp) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.permissions_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            declared.forEachIndexed { index, p ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                val status = managed[p.key]?.status ?: statuses[p.key] ?: PermissionStatus.DENIED
                val statement = managed[p.key]?.statementRes ?: p.statementRes(status)
                // Tappable only where Android (or the app, for APP_MANAGED) has a page to open.
                val actionable = when (p.kind) {
                    PermissionKind.INSTALL_TIME -> false
                    PermissionKind.APP_MANAGED -> appManaged.containsKey(p.key)
                    else -> true
                }
                PermissionListItem(
                    title = stringResource(p.titleRes),
                    technical = p.manifestNames.joinToString(", ") { it.substringAfterLast('.') },
                    status = status,
                    statement = stringResource(statement),
                    body = stringResource(p.bodyRes),
                    hint = if (!actionable) {
                        null
                    } else {
                        stringResource(
                            if (status == PermissionStatus.GRANTED) {
                                R.string.permission_tap_to_revoke
                            } else {
                                R.string.permission_tap_to_allow
                            },
                        )
                    },
                    onClick = if (!actionable) null else { { context.openSettingsFor(p, appManaged[p.key]) } },
                    testTag = "permission_item_${p.key}",
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.permissions_never_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            // Nothing may follow the last item. A closing paragraph is the natural thing to add
            // and exactly what turns a scannable inventory back into prose.
            AppPermissions.NEVER_REQUESTED.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                PermissionListItem(
                    title = stringResource(item.titleRes),
                    technical = null,
                    status = PermissionStatus.NOT_REQUESTED,
                    statement = stringResource(R.string.permission_grant_never_requested),
                    body = stringResource(item.bodyRes),
                    hint = null,
                    onClick = null,
                    testTag = "permission_item_${item.key}",
                )
            }
        }
    }
}

/**
 * One permission as a list item: title and status tag, the manifest constant, the one-line
 * grant statement, the rationale, and - only when [onClick] is given - the tap hint.
 */
@Composable
fun PermissionListItem(
    title: String,
    technical: String?,
    status: PermissionStatus,
    statement: String,
    body: String,
    hint: String?,
    onClick: (() -> Unit)?,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = statusColors(status)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClickLabel = hint, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .testTag(testTag)
                .padding(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Weighted so a long title - or a longer translation of a short one - wraps instead
                // of crushing the tag into one character per line.
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PermissionStatusTag(status)
            }
            if (technical != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = technical,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = statement,
                style = MaterialTheme.typography.labelLarge,
                color = colors.content,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            if (onClick != null && hint != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Granted is blue, denied is red, never requested is grey - in every app, whatever its theme. */
@Composable
fun PermissionStatusTag(status: PermissionStatus, modifier: Modifier = Modifier) {
    val colors = statusColors(status)
    Surface(
        shape = RoundedCornerShape(50),
        color = colors.container,
        contentColor = colors.content,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(
                when (status) {
                    PermissionStatus.GRANTED -> R.string.permission_status_granted
                    PermissionStatus.DENIED -> R.string.permission_status_denied
                    PermissionStatus.NOT_REQUESTED -> R.string.permission_status_not_requested
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private data class TagColors(val container: Color, val content: Color)

/**
 * Fixed colours rather than theme roles, so the tags mean the same thing in every app - a
 * brand-coloured or wallpaper-coloured "granted" would not. Light or dark is decided from the
 * theme's actual background, not from the system setting, because some apps drive dark mode
 * from their own preference.
 */
@Composable
private fun statusColors(status: PermissionStatus): TagColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (status) {
        PermissionStatus.GRANTED ->
            if (dark) TagColors(Color(0xFF2A4A8A), Color(0xFFD6E3FF)) else TagColors(Color(0xFFD6E3FF), Color(0xFF0B3D91))
        PermissionStatus.DENIED ->
            if (dark) TagColors(Color(0xFF93000A), Color(0xFFFFDAD6)) else TagColors(Color(0xFFFFDAD6), Color(0xFF93000A))
        PermissionStatus.NOT_REQUESTED ->
            if (dark) TagColors(Color(0xFF474747), Color(0xFFE3E3E3)) else TagColors(Color(0xFFE3E3E3), Color(0xFF474747))
    }
}

/** The one-line statement under the title: the entry's own wording if it has one, else the kind's. */
private fun AppPermission.statementRes(status: PermissionStatus): Int = when (kind) {
    PermissionKind.INSTALL_TIME -> grantedRes ?: R.string.permission_grant_install_time
    PermissionKind.NOTIFICATIONS ->
        if (status == PermissionStatus.GRANTED) {
            grantedRes ?: R.string.permission_grant_notifications_on
        } else {
            deniedRes ?: R.string.permission_grant_notifications_off
        }
    PermissionKind.RUNTIME, PermissionKind.EXACT_ALARM, PermissionKind.OVERLAY, PermissionKind.APP_MANAGED ->
        if (status == PermissionStatus.GRANTED) {
            grantedRes ?: R.string.permission_grant_runtime_granted
        } else {
            deniedRes ?: R.string.permission_grant_runtime_denied
        }
}

private fun Context.statusOfAll(declared: List<AppPermission>): Map<String, PermissionStatus> =
    declared.associate { it.key to statusOf(it) }

private fun Context.statusOf(p: AppPermission): PermissionStatus = when (p.kind) {
    PermissionKind.INSTALL_TIME -> PermissionStatus.GRANTED
    PermissionKind.RUNTIME -> {
        val granted = p.manifestNames.map {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val held = if (p.anyOf) granted.any { it } else granted.all { it }
        if (held) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }
    // areNotificationsEnabled(), not checkSelfPermission(POST_NOTIFICATIONS): it is the switch
    // the user sees, it answers on every Android version, and it also catches notifications
    // being turned off later in settings rather than refused at the prompt.
    PermissionKind.NOTIFICATIONS ->
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    PermissionKind.EXACT_ALARM ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            PermissionStatus.GRANTED
        } else if (getSystemService<AlarmManager>()?.canScheduleExactAlarms() == true) {
            PermissionStatus.GRANTED
        } else {
            PermissionStatus.DENIED
        }
    PermissionKind.OVERLAY ->
        if (Settings.canDrawOverlays(this)) PermissionStatus.GRANTED else PermissionStatus.DENIED
    // Answered asynchronously by the app's hook; until then the item reads as not granted.
    PermissionKind.APP_MANAGED -> PermissionStatus.DENIED
}

/**
 * Android's own permission-management screens, in the order the PermissionController exposes
 * them. These actions and extras are not in the public SDK - they are the contract the system
 * Settings app uses to link into itself, and the only way to open a page narrower than "this
 * app". They are spelled out as literals rather than taken from `Intent`, because the matching
 * constants are hidden on some API levels and `@SystemApi` on others.
 */
private const val ACTION_MANAGE_APP_PERMISSION = "android.intent.action.MANAGE_APP_PERMISSION"
private const val ACTION_MANAGE_APP_PERMISSIONS = "android.intent.action.MANAGE_APP_PERMISSIONS"
private const val EXTRA_PACKAGE_NAME = "android.intent.extra.PACKAGE_NAME"
private const val EXTRA_PERMISSION_NAME = "android.intent.extra.PERMISSION_NAME"
private const val EXTRA_USER = "android.intent.extra.USER"

/**
 * The Android settings page that carries this permission's switch, tried from the most specific
 * page down to the one that exists everywhere.
 *
 * For a runtime permission that means the permission's **own** page - the one with the Allow /
 * Don't allow choice for exactly this permission - rather than the app's details page, which
 * drops the user two taps away from what the item they tapped was about. Only
 * [EXTRA_PERMISSION_NAME] is passed and not the permission *group*: the PermissionController
 * derives the group from the permission itself, which is what makes the same Intent work from
 * API 23, where the group extra did not exist, up to today.
 *
 * Every step down the list still lands somewhere the user can act, and the app's details page
 * is the last one because it exists on every device and lists the permissions - some OEM builds
 * carry neither of the two pages above it. `runCatching` because an Intent with no handler
 * throws (and a guarded one throws `SecurityException`), and a permissions page that crashes
 * while explaining how careful the app is would be its own joke.
 */
private fun Context.openSettingsFor(p: AppPermission, hook: AppManagedPermission?) {
    if (p.kind == PermissionKind.APP_MANAGED) {
        hook?.open(this)
        return
    }
    val packageUri = Uri.fromParts("package", packageName, null)
    val candidates = buildList {
        when (p.kind) {
            PermissionKind.NOTIFICATIONS -> add(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
            PermissionKind.EXACT_ALARM ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri))
                }
            PermissionKind.OVERLAY -> add(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))
            PermissionKind.RUNTIME -> {
                // This permission's own page. For a group entry (fine + coarse location) any
                // member resolves to the one group, so the first name answers for all of them.
                add(
                    Intent(ACTION_MANAGE_APP_PERMISSION)
                        .putExtra(EXTRA_PACKAGE_NAME, packageName)
                        .putExtra(EXTRA_PERMISSION_NAME, p.manifestNames.first())
                        .putExtra(EXTRA_USER, Process.myUserHandle())
                )
                // Failing that, the app's permission list - still one level in from details.
                add(Intent(ACTION_MANAGE_APP_PERMISSIONS).putExtra(EXTRA_PACKAGE_NAME, packageName))
            }
            PermissionKind.INSTALL_TIME, PermissionKind.APP_MANAGED -> Unit
        }
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }
    for (intent in candidates) {
        if (runCatching { startActivity(intent) }.isSuccess) return
    }
}

