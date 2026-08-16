package com.hermes.mobile.ui.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

/**
 * Compose helper for the real Google Sign-In flow (Drive scope).
 * Returns a lambda that launches the account picker; results come back
 * through [onSuccess] with the account email or [onError] with a message.
 */
@Composable
fun rememberGoogleSignInAction(
    onSuccess: (String?) -> Unit,
    onError: (String?) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val signInClient = remember {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        GoogleSignIn.getClient(context, options)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .getResult(ApiException::class.java)
        }.onSuccess { account ->
            onSuccess(account?.email ?: account?.account?.name)
        }.onFailure { err ->
            onError((err as? ApiException)?.statusCode?.let { "code $it" } ?: err.message)
        }
    }

    return { launcher.launch(signInClient.signInIntent) }
}
