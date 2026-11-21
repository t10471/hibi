package com.example.hibi.data

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.hibi.R
import com.example.hibi.notify.HibiFirebaseMessagingService
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Manages Firebase Authentication via Google Sign-In (Android Credential Manager)
 * so only the owner account can read/write Firestore collections.
 */
object AuthManager {

    private const val TAG = "AuthManager"
    const val ALLOWED_EMAIL = "t104711202@gmail.com"

    private val auth: FirebaseAuth by lazy { Firebase.auth }
    private val _currentUser = MutableStateFlow(runCatching { auth.currentUser }.getOrNull())
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    init {
        runCatching {
            auth.addAuthStateListener { firebaseAuth ->
                val u = firebaseAuth.currentUser
                _currentUser.value = u
                if (u?.email == ALLOWED_EMAIL) {
                    HibiFirebaseMessagingService.syncDeviceToken()
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "AuthStateListener init failed", e)
        }
    }

    fun isOwnerSignedIn(): Boolean = _currentUser.value?.email == ALLOWED_EMAIL

    suspend fun signInWithGoogle(activityContext: Context): Result<FirebaseUser> {
        return try {
            val webClientId = activityContext.getString(R.string.default_web_client_id)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(activityContext)
            val result = credentialManager.getCredential(
                request = request,
                context = activityContext,
            )

            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                signInToFirebase(firebaseCredential)
            } else {
                Result.failure(IllegalStateException("Google 認証トークンを取得できませんでした。"))
            }
        } catch (e: GetCredentialCancellationException) {
            Result.failure(Exception("ログインがキャンセルされました。"))
        } catch (e: GetCredentialException) {
            Log.w(TAG, "CredentialManager error", e)
            Result.failure(Exception("Google ログインエラー: ${e.message}"))
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected sign-in error", e)
            Result.failure(e)
        }
    }

    private suspend fun signInToFirebase(
        credential: com.google.firebase.auth.AuthCredential,
    ): Result<FirebaseUser> = suspendCancellableCoroutine { cont ->
        auth.signInWithCredential(credential)
            .addOnSuccessListener { authResult ->
                val user = authResult.user
                if (user == null) {
                    if (cont.isActive) cont.resume(Result.failure(IllegalStateException("ユーザー情報の取得に失敗しました。")))
                } else if (user.email != ALLOWED_EMAIL) {
                    val wrongEmail = user.email ?: "(不明)"
                    auth.signOut()
                    if (cont.isActive) {
                        cont.resume(
                            Result.failure(
                                SecurityException("アカウント ($wrongEmail) には権限がありません。管理者 ($ALLOWED_EMAIL) でログインしてください。")
                            )
                        )
                    }
                } else {
                    HibiFirebaseMessagingService.syncDeviceToken()
                    if (cont.isActive) cont.resume(Result.success(user))
                }
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resume(Result.failure(e))
            }
    }

    fun signOut() {
        auth.signOut()
    }
}
