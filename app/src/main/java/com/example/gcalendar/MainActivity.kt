package com.example.gcalendar

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.openid.appauth.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import kotlin.coroutines.CoroutineContext

class MainActivity : AppCompatActivity(), CoroutineScope {

    val SHARED_PREFERENCES_NAME = "AuthStatePreference"
    val AUTH_STATE = "AUTH_STATE"
    val USED_INTENT = "USED_INTENT"
    val LOG_TAG = "MainActivity"

    private var job: Job = Job()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    var mAuthState: AuthState? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        enablePostAuthorizationFlows()

        authorize.setOnClickListener {
            val serviceConfiguration =
                AuthorizationServiceConfiguration(
                    Uri.parse("https://accounts.google.com/o/oauth2/v2/auth") /* auth endpoint */,
                    Uri.parse("https://www.googleapis.com/oauth2/v4/token") /* token endpoint */
                )

            val clientId =
                "926798702243-p4gfe9e8dfd3luo42s4e094pnke9gdbg.apps.googleusercontent.com"
            val redirectUri =
                Uri.parse("com.example.gcalendar:/oauth2callback")
            val builder = AuthorizationRequest.Builder(
                serviceConfiguration,
                clientId,
                "code",
                redirectUri
            )
            builder.setScopes("profile")
            val request = builder.build()

            val authorizationService = AuthorizationService(this)

            val action = "com.google.codelabs.appauth.HANDLE_AUTHORIZATION_RESPONSE"
            val postAuthorizationIntent = Intent(action)
            val pendingIntent = PendingIntent.getActivity(
                this,
                request.hashCode(),
                postAuthorizationIntent,
                0
            )

            authorizationService.performAuthorizationRequest(request, pendingIntent)
        }
    }

    override fun onStart() {
        super.onStart()
        checkIntent(intent)
    }

    private fun checkIntent(intent: Intent?) {
        intent?.let {
            when (intent.action) {
                "com.google.codelabs.appauth.HANDLE_AUTHORIZATION_RESPONSE" -> if (!intent.hasExtra(
                        USED_INTENT
                    )
                ) {
                    handleAuthorizationResponse(it)
                    intent.putExtra(USED_INTENT, true)
                }
                else -> {
                }
            }
        }
    }

    private fun handleAuthorizationResponse(intent: Intent) {
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)
        val authState = AuthState(response, error)
        if (response != null) {
            Log.i(LOG_TAG, "Handled Authorization Response ${authState.jsonSerialize()} ")

            val service = AuthorizationService(this)
            service.performTokenRequest(
                response.createTokenExchangeRequest()
            ) { tokenResponse, exception ->
                if (exception != null) {
                    Log.w(LOG_TAG, "Token Exchange failed", exception)
                } else {
                    if (tokenResponse != null) {
                        authState.update(tokenResponse, exception)
                        persistAuthState(authState)
                        Log.i(
                            LOG_TAG,
                            "Token Response [ Access Token: ${tokenResponse.accessToken}, ID Token: ${tokenResponse.idToken} ]"
                        )
                    }
                }
            }
        }
    }

    private fun persistAuthState(authState: AuthState) {
        getSharedPreferences(
            SHARED_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(AUTH_STATE, authState.jsonSerializeString())
            .apply()
        enablePostAuthorizationFlows()
    }

    private fun enablePostAuthorizationFlows() {
        mAuthState = restoreAuthState()

        mAuthState?.let { authState ->
            if (authState.isAuthorized) {
                if (makeApiCall.getVisibility() === View.GONE) {
                    makeApiCall.setVisibility(View.VISIBLE)
                    makeApiCall.setOnClickListener {
                        authState.performActionWithFreshTokens(AuthorizationService(this),
                        object : AuthState.AuthStateAction {
                            override fun execute(
                                accessToken: String?,
                                idToken: String?,
                                ex: AuthorizationException?
                            ) {
                                launch {

                                    val client = OkHttpClient()
                                    val request = Request.Builder()
                                        .url("https://www.googleapis.com/oauth2/v3/userinfo")
                                        .addHeader(
                                            "Authorization",
                                            String.format("Bearer %s", accessToken)
                                        )
                                        .build()

                                    try {
                                        val response: Response =
                                            client.newCall(request).execute()
                                        val jsonBody = response.body!!.string()
                                        Log.i(LOG_TAG, "User Info Response $jsonBody")

//                                        return JSONObject(jsonBody)

                                    } catch (exception: Exception) {
                                        Log.w(LOG_TAG, exception)
                                    }
                                }
                            }

                        })
                    }
                }
                if (signOut.getVisibility() === View.GONE) {
                    signOut.setVisibility(View.VISIBLE)
                    signOut.setOnClickListener {
                        mAuthState = null
                        clearAuthState()
                        enablePostAuthorizationFlows()
                    }
                }
            } else {
                makeApiCall.setVisibility(View.GONE)
                signOut.setVisibility(View.GONE)
            }
        }
    }

    private fun restoreAuthState(): AuthState? {
        getSharedPreferences(
            SHARED_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).getString(AUTH_STATE, null)?.let {
            try {
                return AuthState.jsonDeserialize(it)
            } catch (jsonException: JSONException) {
            }
        }
        return null
    }

    private fun clearAuthState() {
        getSharedPreferences(
            SHARED_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .remove(AUTH_STATE)
            .apply()
    }
}