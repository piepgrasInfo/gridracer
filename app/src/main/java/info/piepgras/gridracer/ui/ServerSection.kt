package info.piepgras.gridracer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.ConnectionState
import info.piepgras.gridracer.MultiplayerClient
import info.piepgras.gridracer.R
import info.piepgras.gridracer.multiplayer.AccountInfo
import info.piepgras.gridracer.multiplayer.AccountRules
import info.piepgras.gridracer.multiplayer.AuthRejection

private enum class AuthTab { LOGIN, REGISTER }

/**
 * The "Server" section of the settings screen: where to connect and who to log in as.
 *
 * This consolidates what used to be the server-URL field on the race-setup screen and the
 * whole standalone [AccountScreen] into one inline block, so a user manages their server
 * identity in one place rather than two. The connection state and account flows drive what is
 * shown; there is no local "is loading" state because the server's reply is the only source of
 * truth for whether a request is in flight.
 */
@Composable
fun ServerSection(
    serverUrl: String,
    onCommitServerUrl: (String) -> Unit,
    account: AccountInfo?,
    connectionState: ConnectionState,
    connectionEncrypted: Boolean,
    serverSupportsAccounts: Boolean,
    serverSupportsAccountDeletion: Boolean,
    authError: String?,
    accountDeleted: Boolean,
    onRegister: (username: String, password: String, displayName: String?) -> Unit,
    onLogin: (username: String, password: String) -> Unit,
    onLogout: () -> Unit,
    onDeleteAccount: (password: String) -> Unit,
    onDismissError: () -> Unit,
    onDismissAccountDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // No heading of its own: the caller wraps this in a [SettingsSection], which supplies one.
    // Printing both rendered "Server" twice, one line under the other.
    Column(modifier = modifier.fillMaxWidth()) {
        ServerUrlField(serverUrl, onCommitServerUrl)
        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connectionEncrypted) ConnectionSecurityIcon(encrypted = true)
            Text(
                stringResource(connectionStateLabel(connectionState)),
                style = MaterialTheme.typography.bodySmall,
                color = if (connectionState == ConnectionState.Connected) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (connectionEncrypted) {
                Text(
                    stringResource(R.string.multiplayer_encrypted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        // By the address, not the connection: the warning matters most before the first
        // password is typed, which may be before the server has even answered.
        val unencrypted = !MultiplayerClient.isEncryptedUrl(serverUrl)
        if (unencrypted) {
            Spacer(Modifier.height(8.dp))
            UnencryptedServerWarning(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))

        // Shown after the account is gone, where the logged-in card used to be: the deletion
        // is the one action whose result the user cannot verify by looking at the screen.
        if (accountDeleted) {
            Text(
                stringResource(R.string.account_delete_done),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDismissAccountDeleted) {
                Text(stringResource(R.string.account_dismiss_error))
            }
            Spacer(Modifier.height(12.dp))
        }

        when {
            account != null -> LoggedInCard(
                account = account,
                serverSupportsAccountDeletion = serverSupportsAccountDeletion,
                unencrypted = unencrypted,
                authError = authError,
                onLogout = onLogout,
                onDeleteAccount = onDeleteAccount,
                onDismissError = onDismissError,
            )
            connectionState != ConnectionState.Connected -> {
                Text(stringResource(R.string.account_not_connected), style = MaterialTheme.typography.bodyMedium)
            }
            !serverSupportsAccounts -> {
                Text(stringResource(R.string.account_server_no_accounts), style = MaterialTheme.typography.bodyMedium)
            }
            else -> AuthForm(
                authError = authError,
                onRegister = onRegister,
                onLogin = onLogin,
                onDismissError = onDismissError,
            )
        }
    }
}

/**
 * The server URL is committed on focus loss, not per keystroke: [info.piepgras.gridracer.MultiplayerClient]
 * fixes its URL at construction, so committing per keystroke would rebuild the WebSocket for
 * every character typed.
 */
@Composable
private fun ServerUrlField(serverUrl: String, onCommitServerUrl: (String) -> Unit) {
    var urlDraft by remember(serverUrl) { mutableStateOf(serverUrl) }
    var urlFocused by remember { mutableStateOf(false) }
    val commitUrl = { if (urlDraft.trim() != serverUrl) onCommitServerUrl(urlDraft) }
    OutlinedTextField(
        value = urlDraft,
        onValueChange = { urlDraft = it },
        label = { Text(stringResource(R.string.settings_multiplayer_server_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (urlFocused && !focusState.isFocused) commitUrl()
                urlFocused = focusState.isFocused
            },
    )
}

@Composable
private fun LoggedInCard(
    account: AccountInfo,
    serverSupportsAccountDeletion: Boolean,
    unencrypted: Boolean,
    authError: String?,
    onLogout: () -> Unit,
    onDeleteAccount: (password: String) -> Unit,
    onDismissError: () -> Unit,
) {
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.account_logged_in_as, account.displayName),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            StatRow(stringResource(R.string.account_username), account.username)
            StatRow(stringResource(R.string.account_rating_label), account.rating.toString())
            StatRow(stringResource(R.string.account_matches_label), account.matches.toString())
            StatRow(stringResource(R.string.account_wins_label), account.wins.toString())
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.account_logout))
            }
            Spacer(Modifier.height(8.dp))
            // Google Play requires an in-app path to account deletion for any app that offers
            // sign-up, and requires it to be easy to find - so it sits with the account it
            // deletes, not behind a link to a web page.
            OutlinedButton(
                onClick = {
                    onDismissError()
                    showDeleteDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.account_delete))
            }
        }
    }

    if (showDeleteDialog) {
        DeleteAccountDialog(
            canDeleteHere = serverSupportsAccountDeletion,
            unencrypted = unencrypted,
            authError = authError,
            onConfirm = onDeleteAccount,
            onDismiss = {
                showDeleteDialog = false
                onDismissError()
            },
        )
    }
}

/**
 * "Delete my account, and here is my password to prove it is me."
 *
 * Hand-built like [ConfirmDialog] rather than using [AlertDialog]'s slots for the controls:
 * the app is landscape-locked, where the `text` slot is a few lines tall and scrolls, so the
 * password field and the buttons live **below** the scrolling body where they stay reachable.
 *
 * When the server does not advertise account deletion the dialog still opens - Play requires
 * the in-app path to exist - and shows how to request deletion by mail instead.
 *
 * @param unencrypted Whether the server is plain `ws://`, so the password is sent readable.
 */
@Composable
private fun DeleteAccountDialog(
    canDeleteHere: Boolean,
    unencrypted: Boolean,
    authError: String?,
    onConfirm: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    val usernameHint = stringResource(
        R.string.account_hint_username,
        AccountRules.USERNAME_MIN_LENGTH,
        AccountRules.USERNAME_MAX_LENGTH,
    )
    val passwordHint = stringResource(R.string.account_hint_password, AccountRules.PASSWORD_MIN_LENGTH)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_delete_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(
                        if (canDeleteHere) R.string.account_delete_body else R.string.account_delete_unsupported,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.account_delete_local_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (canDeleteHere) {
                    // Beside the field rather than in the scrolling body, where in landscape
                    // it could sit below the fold while the password goes out.
                    if (unencrypted) {
                        Text(
                            stringResource(R.string.multiplayer_unencrypted_password),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.account_delete_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (authError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            friendlyAuthError(authError, usernameHint, passwordHint),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    if (canDeleteHere) {
                        Button(
                            onClick = { onConfirm(password) },
                            // The server checks the password properly; this only stops a
                            // request that is certain to be refused.
                            enabled = password.length >= AccountRules.PASSWORD_MIN_LENGTH,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(stringResource(R.string.account_delete_confirm))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AuthForm(
    authError: String?,
    onRegister: (String, String, String?) -> Unit,
    onLogin: (String, String) -> Unit,
    onDismissError: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(AuthTab.LOGIN) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = tab == AuthTab.LOGIN,
            onClick = { tab = AuthTab.LOGIN },
            label = { Text(stringResource(R.string.account_login_tab)) },
        )
        FilterChip(
            selected = tab == AuthTab.REGISTER,
            onClick = { tab = AuthTab.REGISTER },
            label = { Text(stringResource(R.string.account_register_tab)) },
        )
    }
    Spacer(Modifier.height(12.dp))

    // The rules are the server's, quoted from the same constants it validates against, so the
    // hint cannot drift from what will actually be accepted. It said "3-24" while the server
    // enforced 4-11, and the button was enabled for both.
    val usernameHint = stringResource(
        R.string.account_hint_username,
        AccountRules.USERNAME_MIN_LENGTH,
        AccountRules.USERNAME_MAX_LENGTH,
    )
    val passwordHint = stringResource(R.string.account_hint_password, AccountRules.PASSWORD_MIN_LENGTH)

    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text(stringResource(R.string.account_username)) },
        singleLine = true,
        isError = username.isNotEmpty() && !AccountRules.isUsernameValid(username),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        usernameHint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.account_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = password.isNotEmpty() && password.length < AccountRules.PASSWORD_MIN_LENGTH,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        passwordHint,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    if (tab == AuthTab.REGISTER) {
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text(stringResource(R.string.account_display_name_optional)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }

    if (authError != null) {
        Text(
            friendlyAuthError(authError, usernameHint, passwordHint),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        // "Dismiss", not "Cancel": this clears a message, it does not abandon anything.
        OutlinedButton(onClick = onDismissError) { Text(stringResource(R.string.account_dismiss_error)) }
        Spacer(Modifier.height(12.dp))
    }

    // Matches what the server will accept, so the button is not offered for input that is
    // certain to be refused.
    val canSubmit = AccountRules.isUsernameValid(username) &&
        password.length >= AccountRules.PASSWORD_MIN_LENGTH
    Button(
        onClick = {
            when (tab) {
                AuthTab.LOGIN -> onLogin(username.trim(), password)
                AuthTab.REGISTER -> onRegister(username.trim(), password, displayName.trim().ifBlank { null })
            }
        },
        enabled = canSubmit,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (tab == AuthTab.LOGIN) R.string.account_login_button else R.string.account_register_button,
            ),
        )
    }
}

/**
 * Turns a wire [AuthRejection] name into something a person can act on.
 *
 * Six of the seven reasons used to collapse into one generic "Could not complete the request",
 * so "that username is taken" and "wrong password" were indistinguishable - the user had no
 * way to tell whether to pick a different name or to try a different password.
 */
@Composable
private fun friendlyAuthError(reason: String, usernameHint: String, passwordHint: String): String {
    // Two of the codes on this flow are not rejections at all - they answer a delete request.
    when (reason) {
        MultiplayerClient.AUTH_ERROR_IN_ROOM -> return stringResource(R.string.account_error_in_room)
        MultiplayerClient.AUTH_ERROR_DELETE_FAILED -> return stringResource(R.string.account_error_delete_failed)
    }
    return when (runCatching { AuthRejection.valueOf(reason) }.getOrNull()) {
        AuthRejection.INVALID_USERNAME -> stringResource(R.string.account_error_invalid_username, usernameHint)
        AuthRejection.INVALID_PASSWORD -> stringResource(R.string.account_error_invalid_password, passwordHint)
        AuthRejection.INVALID_DISPLAY_NAME -> stringResource(R.string.account_error_invalid_display_name)
        AuthRejection.USERNAME_TAKEN -> stringResource(R.string.account_error_username_taken)
        AuthRejection.NO_SUCH_USER -> stringResource(R.string.account_error_no_such_user)
        AuthRejection.WRONG_PASSWORD -> stringResource(R.string.account_error_wrong_password)
        AuthRejection.RATE_LIMITED -> stringResource(R.string.account_error_rate_limited)
        // A rejection reason from a newer server than this build knows about.
        null -> stringResource(R.string.account_error_generic)
    }
}

private fun connectionStateLabel(state: ConnectionState): Int = when (state) {
    ConnectionState.Disconnected -> R.string.multiplayer_disconnected
    ConnectionState.Connecting -> R.string.multiplayer_connecting
    ConnectionState.Reconnecting -> R.string.multiplayer_reconnecting
    ConnectionState.Connected -> R.string.multiplayer_connected
    ConnectionState.Error -> R.string.multiplayer_error
}
