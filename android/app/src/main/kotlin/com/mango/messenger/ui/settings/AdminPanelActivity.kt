package com.mango.messenger.ui.settings

import androidx.core.net.toUri
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mango.core.common.Outcome
import com.mango.core.database.MangoDatabase
import com.mango.core.network.AccountFeaturesRemote
import com.mango.core.network.auth.SessionManager
import org.json.JSONObject
import android.net.Uri
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import com.mango.core.network.di.PlainClient
import com.mango.core.network.di.AuthenticatedClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mango.messenger.BuildConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import javax.inject.Inject

/** Non-exported, role-gated window; admin login stays inside this WebView's memory. */
@AndroidEntryPoint
class AdminPanelActivity : ComponentActivity() {
    @Inject lateinit var account: AccountFeaturesRemote
    @Inject lateinit var database: MangoDatabase
    @Inject lateinit var session: SessionManager
    @Inject @PlainClient lateinit var plainClient: OkHttpClient
    @Inject @AuthenticatedClient lateinit var apiClient: OkHttpClient
    private var channel: WebMessagePort? = null
    private val policy by lazy { AdminPanelPolicy(BuildConfig.SERVER_URL) }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var web: WebView? = null
    private var sessionUser: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            fitsSystemWindows = true
        }
        content.addView(Button(this).apply { setText(com.mango.messenger.R.string.adminpanelactivity_text_0); setOnClickListener { finish() } })
        status = TextView(this).apply { setText(com.mango.messenger.R.string.adminpanelactivity_text_1); setPadding(12, 12, 12, 12) }
        content.addView(status)
        setContentView(content)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    try {
                    val user = database.userDao().currentUserId()
                    val result = if (user != null) account.adminAccess() else null
                    val stillCurrent = user != null && user == database.userDao().currentUserId()
                    if (stillCurrent && result is Outcome.Success && hasAdminPanelAccess(result.value.role)) {
                        if (sessionUser != user) { releaseWeb(); sessionUser = user }
                        if (web == null) showPanel()
                    } else {
                        releaseWeb()
                        status.visibility = View.VISIBLE
                        status.setText(if (user == null) com.mango.messenger.R.string.admin_login_required else com.mango.messenger.R.string.admin_access_unconfirmed)
                    }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) {
                        releaseWeb()
                        status.visibility = View.VISIBLE
                        status.setText(com.mango.messenger.R.string.admin_status_0)
                    }
                    delay(30_000)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPanel() {
        status.visibility = View.VISIBLE
        status.setText(com.mango.messenger.R.string.admin_status_1)
        val view = WebView(this)
        web = view
        view.settings.apply {
            userAgentString = userAgentString + " MangoNativeAdmin/1"
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url == policy.entryUrl) authenticatePanel(view)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !policy.allows(request.url.toString(), navigation = true)

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (!policy.allows(request.url.toString(), navigation = true) || request.method != "GET")
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                return try {
                    // Reuse Mango DNS fallback and certificate validation, without credentials for assets.
                    plainClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
                        .newCall(Request.Builder().url(request.url.toString()).build()).execute().use { response ->
                            if (response.code !in 200..299) throw IllegalStateException()
                            WebResourceResponse(response.body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "text/plain", "UTF-8",
                                response.code, "OK", response.headers.toMultimap().mapValues { it.value.joinToString(",") },
                                ByteArrayInputStream(response.body.bytes()))
                        }
                } catch (_: Exception) {
                    WebResourceResponse("text/plain", "UTF-8", 503, "Unavailable", emptyMap(), ByteArrayInputStream("Сервер недоступен. Вернитесь и попробуйте снова.".toByteArray()))
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    status.setText(com.mango.messenger.R.string.admin_status_2)
                    status.visibility = View.VISIBLE
                }
            }
            // Default SSL error handling cancels invalid certificates; never bypass TLS validation.
        }
        content.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        view.loadUrl(policy.entryUrl)
    }

    private fun authenticatePanel(view: WebView) {
        if (web !== view || view.url != policy.entryUrl || channel != null) return
        val ports = view.createWebMessageChannel()
        channel = ports[0]
        val expectedUser = sessionUser
        ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage?) {
                val raw = message?.data ?: return
                if (raw.length > 262144) return
                lifecycleScope.launch {
                    val reply = JSONObject()
                    try {
                        val input = JSONObject(raw)
                        reply.put("id", input.getLong("id"))
                        val path = input.getString("path")
                        val method = input.getString("method")
                        check(web === view && channel === port && view.url == policy.entryUrl)
                        check(expectedUser != null && session.currentUserId() == expectedUser)
                        check(policy.allowsApi(path, method))
                        val body = if (method == "GET") null else (input.opt("body") ?: JSONObject.NULL).toString().toRequestBody("application/json".toMediaType())
                        val response = withContext(Dispatchers.IO) {
                            apiClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
                                .newCall(Request.Builder().url(BuildConfig.SERVER_URL.trimEnd('/') + "/v1" + path).method(method,body).build()).execute().use {
                                    it.code to (it.body.string())
                                }
                        }
                        reply.put("status",response.first)
                        reply.put("data",org.json.JSONTokener(response.second).nextValue())
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { reply.put("status",403) }
                    if (web === view && channel === port) port.postMessage(WebMessage(reply.toString()))
                }
            }
        })
        val origin = BuildConfig.SERVER_URL.toUri().buildUpon().path("").query(null).fragment(null).build()
        view.postWebMessage(WebMessage("mango-native-admin",arrayOf(ports[1])),origin)
        status.visibility = View.GONE
    }

    private fun releaseWeb() {
        channel?.close()
        channel = null
        web?.let { view ->
            view.stopLoading()
            content.removeView(view)
            view.clearHistory()
            view.removeAllViews()
            view.destroy()
        }
        web = null
        sessionUser = null
    }

    override fun onStop() {
        // Drop the separate web session when leaving the foreground.
        releaseWeb()
        super.onStop()
    }

    override fun onDestroy() { releaseWeb(); super.onDestroy() }
}
