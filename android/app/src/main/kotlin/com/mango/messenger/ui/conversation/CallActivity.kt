package com.mango.messenger.ui.conversation

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mango.core.common.Outcome
import com.mango.core.network.AccountFeaturesRemote
import com.mango.messenger.BuildConfig
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI
import javax.inject.Inject

/** Foreground-only call; no account token or JavaScript bridge is exposed to the page. */
@AndroidEntryPoint
class CallActivity : ComponentActivity() {
    @Inject @com.mango.core.network.di.PlainClient lateinit var assetClient: okhttp3.OkHttpClient
    @Inject lateinit var account:AccountFeaturesRemote
    private var web:WebView?=null
    private var permission:PermissionRequest?=null
    private val origin by lazy {URI(BuildConfig.SERVER_URL)}
    private fun sameOrigin(value:String)=runCatching {val u=URI(value);u.scheme==origin.scheme&&u.host==origin.host&&u.port==origin.port&&u.userInfo==null}.getOrDefault(false)
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val request=permission;permission=null
        if(request!=null){val allowed=request.resources.filter {r->
            val p=when(r){PermissionRequest.RESOURCE_AUDIO_CAPTURE->Manifest.permission.RECORD_AUDIO;PermissionRequest.RESOURCE_VIDEO_CAPTURE->Manifest.permission.CAMERA;else->null}
            p!=null&&ContextCompat.checkSelfPermission(this,p)==PackageManager.PERMISSION_GRANTED
        };if(allowed.size==request.resources.size&&sameOrigin(request.origin.toString()))request.grant(allowed.toTypedArray()) else request.deny()}
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;fitsSystemWindows=true}
        layout.addView(Button(this).apply {setText(com.mango.messenger.R.string.callactivity_text_0);setOnClickListener{finish()}})
        val status=TextView(this).apply{setText(com.mango.messenger.R.string.callactivity_text_1);setPadding(16,16,16,16)};layout.addView(status);setContentView(layout)
        val id=intent.getStringExtra("conversationId")?:return finish()
        lifecycleScope.launch {
            val result=account.joinCall(id)
            if(result !is Outcome.Success){status.setText(com.mango.messenger.R.string.callactivity_text_2);return@launch}
            val grant=result.value
            val view=WebView(this@CallActivity);web=view
            view.settings.apply {javaScriptEnabled=true;domStorageEnabled=false;allowFileAccess=false;allowContentAccess=false;mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;mediaPlaybackRequiresUserGesture=false;cacheMode=WebSettings.LOAD_NO_CACHE}
            view.webChromeClient=object:WebChromeClient(){
                override fun onPermissionRequest(request:PermissionRequest){runOnUiThread {
                    if(!sameOrigin(request.origin.toString())||request.resources.any{it !in setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE,PermissionRequest.RESOURCE_VIDEO_CAPTURE)}){request.deny();return@runOnUiThread}
                    permission?.deny();permission=request
                    permissions.launch(request.resources.map{if(it==PermissionRequest.RESOURCE_AUDIO_CAPTURE)Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA}.toTypedArray())
                }}
                override fun onPermissionRequestCanceled(request:PermissionRequest){if(permission===request)permission=null}
            }
            var initialized=false
            view.webViewClient=object:WebViewClient(){
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                    if(!sameOrigin(request.url.toString()))return null
                    if(request.method!="GET"||request.url.path !in setOf("/web/call.html","/web/call.css","/web/call.js","/web/livekit-client.umd.js"))
                        return WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),java.io.ByteArrayInputStream(ByteArray(0)))
                    return try{assetClient.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(okhttp3.Request.Builder().url(request.url.toString()).build()).execute().use{r->
                        if(r.code !in 200..299)throw IllegalStateException()
                        WebResourceResponse(r.body.contentType()?.let{"${it.type}/${it.subtype}"}?:"text/plain","UTF-8",r.code,"OK",r.headers.toMultimap().mapValues{it.value.joinToString(",")},java.io.ByteArrayInputStream(r.body.bytes()))
                    }}catch(_:Exception){WebResourceResponse("text/plain","UTF-8",503,"Unavailable",emptyMap(),java.io.ByteArrayInputStream(ByteArray(0)))}
                }
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)= !sameOrigin(request.url.toString())||request.url.path!="/web/call.html"
                override fun onPageFinished(view:WebView,url:String){
                    if(initialized||!sameOrigin(url)||URI(url).path!="/web/call.html")return
                    initialized=true
                    val payload=JSONObject().put("url",grant.url).put("token",grant.token).put("video",intent.getBooleanExtra("video",false)).put("relayOnly",grant.relayOnly).put("iceServers",org.json.JSONArray().apply { grant.iceServers.forEach { put(JSONObject().put("urls",org.json.JSONArray(it.urls)).put("username",it.username).put("credential",it.credential)) } }).toString()
                    view.evaluateJavascript("window.startMangoCall($payload)",null);status.visibility=android.view.View.GONE
                }
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(request.isForMainFrame){status.setText(com.mango.messenger.R.string.callactivity_text_3);status.visibility=android.view.View.VISIBLE}}
            }
            layout.addView(view,LinearLayout.LayoutParams(-1,0,1f));view.loadUrl(BuildConfig.SERVER_URL.trimEnd('/')+"/web/call.html")
        }
    }
    override fun onStop(){super.onStop();if(!isChangingConfigurations){release();finish()}}
    private fun release(){permission?.deny();permission=null;web?.apply{stopLoading();loadUrl("about:blank");removeAllViews();destroy()};web=null}
    override fun onDestroy(){release();super.onDestroy()}
}
