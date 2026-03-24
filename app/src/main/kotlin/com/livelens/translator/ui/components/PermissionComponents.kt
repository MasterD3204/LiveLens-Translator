package com.livelens.translator.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.MultiplePermissionsState
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale

/**
 * Reusable permission request dialog that integrates with Accompanist Permissions.
 * Shows a rationale dialog before requesting permissions.
 */
@Composable
fun PermissionRequestDialog(
    title: String,
    rationale: String,
    onGranted: () -> Unit,
    onDenied: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(onClick = onGranted) {
                Text("Grant Permission")
            }
        },
        dismissButton = {
            TextButton(onClick = onDenied) {
                Text("Not Now")
            }
        }
    )
}

/**
 * Wrapper that requests multiple permissions and invokes callbacks.
 * Shows rationale dialog before requesting.
 *
 * @param permissions List of permission strings to request
 * @param rationaleTitle Title for the rationale dialog
 * @param rationaleMessage Message explaining why permissions are needed
 * @param onAllGranted Called when all permissions are granted
 * @param onDenied Called when permissions are denied
 * @param content Content displayed while waiting for permission decision
 */
@Composable
fun RequirePermissions(
    permissions: List<String>,
    rationaleTitle: String,
    rationaleMessage: String,
    onAllGranted: @Composable () -> Unit,
    onDenied: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Permissions Required", style = MaterialTheme.typography.titleMedium)
            Text(rationaleMessage, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
) {
    val permissionsState = rememberMultiplePermissionsState(permissions)

    when {
        permissionsState.allPermissionsGranted -> onAllGranted()
        permissionsState.shouldShowRationale -> {
            PermissionRequestDialog(
                title = rationaleTitle,
                rationale = rationaleMessage,
                onGranted = { permissionsState.launchMultiplePermissionRequest() },
                onDenied = {},
                onDismiss = {}
            )
        }
        else -> {
            LaunchedEffect(Unit) {
                permissionsState.launchMultiplePermissionRequest()
            }
            onDenied()
        }
    }
}
