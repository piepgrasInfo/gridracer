package info.piepgras.gridracer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.piepgras.gridracer.R

/**
 * The padlock for a connection that is up and encrypted, or the open padlock for one that is
 * up and is not.
 *
 * Shown only for a live connection: "encrypted" is something the handshake proves, so the
 * padlock is not drawn for an address that merely starts with `wss://`.
 *
 * @param encrypted Whether the connection is encrypted; the open padlock is drawn in the error
 *     colour, as the warning it is.
 */
@Composable
fun ConnectionSecurityIcon(encrypted: Boolean, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (encrypted) Icons.Filled.Lock else Icons.Filled.LockOpen,
        contentDescription = stringResource(
            if (encrypted) R.string.multiplayer_encrypted_description else R.string.multiplayer_unencrypted_description,
        ),
        tint = if (encrypted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = modifier.size(16.dp),
    )
}

/**
 * The warning shown while the configured server is plain `ws://`: what is exposed, and when
 * that is acceptable.
 *
 * Encryption is optional so that a server on a home network needs no certificate, but the
 * password forms sit directly under this, and what they send is readable by anyone on the
 * network.
 */
@Composable
fun UnencryptedServerWarning(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.LockOpen,
            // The text beside it says it all; a description would be read out twice.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Text(
            stringResource(R.string.multiplayer_unencrypted_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
