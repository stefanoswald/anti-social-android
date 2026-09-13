package com.stefanoswald.antisocial

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.Date

/** A record of something the app refused to load. */
data class BlockEvent(
    val date: Date,
    val tabTitle: String,
    val url: String,
    val reason: String
)

/**
 * Owns one WebView and enforces the allowlist on it.
 *
 * There are two paths a navigation can take, and both are guarded:
 * - Real page loads go through [WebViewClient.shouldOverrideUrlLoading].
 * - In-page (single-page-app) URL changes are reported by the injected hook
 *   and arrive in [PageBridge.post].
 */
@SuppressLint("SetJavaScriptEnabled")
class TabController(
    context: Context,
    val tab: WebTab,
    private val settings: AppSettings,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBlocked(event: BlockEvent)
        fun onExternal(url: String)
        /** The app worked out which account is signed in. */
        fun onLearnUsername(site: SiteKind, username: String)
        fun onStateChanged(controller: TabController)
        /**
         * The page asked for a file. Posting a photo, listing an item and
         * sending an attachment all go through here, so without it the
         * composer is text-only.
         */
        fun onShowFileChooser(
            callback: ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams
        ): Boolean
        /** The page asked for the camera or the microphone (Go Live). */
        fun onPermissionRequest(request: PermissionRequest)
    }

    val webView: WebView = WebView(context)
    var policy = AccessPolicy(settings.policyOptions())
        private set

    var isLoading: Boolean = false
        private set
    var currentUrl: String? = null
        private set

    /** Human-readable tab name, for the diagnostics log. */
    var tabLabel: String = tab.id

    private var hasLoadedOnce = false
    private var lastBounceAt = 0L
    private var awaitingOwnProfile = false
    private var appliedUserAgent: String = ""
    private var appliedViewport: Int = -1
    private var appliedHiding: Boolean? = null
    private var documentStartScript: androidx.webkit.ScriptHandler? = null
    private var hidingScriptHandle: androidx.webkit.ScriptHandler? = null
    private var viewportScriptHandle: androidx.webkit.ScriptHandler? = null

    init {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            // Messenger opens outbound links in a new window. Without this
            // they silently do nothing instead of reaching the policy.
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(PageBridge(this), Injected.BRIDGE)
        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()

        applyConfiguration()
    }

    // MARK: - Public actions

    fun loadHomeIfNeeded() {
        if (hasLoadedOnce) return
        hasLoadedOnce = true
        goHome()
    }

    fun goHome() = load(tab.home)

    fun reload() {
        if (webView.url == null) goHome() else webView.reload()
    }

    fun goBack(): Boolean {
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    /**
     * Opens the right page for a compose action on this tab's site. Facebook
     * and Instagram both put the composer in a dialog on your own profile, so
     * most of these just land you there and let the page do the rest.
     *
     * Returns null on success, or a message explaining why it could not.
     */
    fun compose(action: ComposeAction): String? {
        if (tab.site == SiteKind.INSTAGRAM) {
            val name = settings.instagramUsername
            if (name.isEmpty()) {
                return "Open your Instagram inbox once so the app can learn your username, " +
                    "or type it into Settings."
            }
            load("https://www.instagram.com/$name/")
            return null
        }

        val url = when (action) {
            // Facebook resolves /me to your own profile, where the composer is.
            ComposeAction.POST -> "https://www.facebook.com/me"
            ComposeAction.STORY -> "https://www.facebook.com/stories/create"
            ComposeAction.LIVE -> "https://www.facebook.com/live/producer"
            ComposeAction.LISTING -> "https://www.facebook.com/marketplace/create"
        }
        load(url)
        return null
    }

    fun pageMap(callback: (String) -> Unit) {
        webView.evaluateJavascript(Injected.pageMap) { raw ->
            // evaluateJavascript hands back a JSON-encoded string.
            val text = try {
                if (raw == null || raw == "null") "{}" else JSONObject("{\"v\":$raw}").getString("v")
            } catch (e: Exception) {
                raw.orEmpty()
            }
            callback(text)
        }
    }

    fun destroy() {
        webView.removeJavascriptInterface(Injected.BRIDGE)
        webView.webChromeClient = null
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /** Re-reads settings and reloads if anything that affects the page changed. */
    fun applySettings() {
        policy = AccessPolicy(settings.policyOptions())
        val changed = applyConfiguration()
        if (changed && hasLoadedOnce) goHome()
    }

    // MARK: - Internals

    private fun load(url: String) {
        awaitingOwnProfile = Uri.parse(url).path?.lowercase()?.trim('/') == "me"
        webView.loadUrl(url)
    }

    /** Returns true if something changed that needs a reload. */
    private fun applyConfiguration(): Boolean {
        var changed = false

        val ua = settings.userAgent(tab.site)
        if (ua != appliedUserAgent) {
            appliedUserAgent = ua
            webView.settings.userAgentString = ua
            changed = true
        }

        val viewport = settings.viewportWidth(tab.site)
        val hiding = settings.hideDistractions
        if (viewport != appliedViewport || hiding != appliedHiding) {
            appliedViewport = viewport
            appliedHiding = hiding
            installScripts(viewport, hiding)
            changed = true
        }

        return changed
    }

    /**
     * Document-start injection is the Android equivalent of a WKUserScript at
     * document start. Older WebViews do not have it, so [Client.onPageStarted]
     * repeats the same scripts as a fallback.
     */
    private fun installScripts(viewport: Int, hiding: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return

        val origins = setOf(
            "https://*.facebook.com", "https://facebook.com",
            "https://*.instagram.com", "https://instagram.com",
            "https://*.messenger.com", "https://messenger.com"
        )

        documentStartScript?.remove()
        documentStartScript = WebViewCompat.addDocumentStartJavaScript(webView, Injected.navigationHook, origins)

        hidingScriptHandle?.remove()
        hidingScriptHandle = if (hiding) {
            WebViewCompat.addDocumentStartJavaScript(webView, Injected.hidingScript(), origins)
        } else {
            null
        }

        viewportScriptHandle?.remove()
        viewportScriptHandle = if (viewport > 0) {
            WebViewCompat.addDocumentStartJavaScript(webView, Injected.viewportScript(viewport), origins)
        } else {
            null
        }
    }

    private fun runFallbackScripts() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        webView.evaluateJavascript(Injected.navigationHook, null)
        if (appliedHiding == true) webView.evaluateJavascript(Injected.hidingScript(), null)
        if (appliedViewport > 0) webView.evaluateJavascript(Injected.viewportScript(appliedViewport), null)
    }

    private fun record(url: String, reason: String) {
        Log.i(TAG, "[${tab.id}] BLOCKED ($reason) $url <- from ${webView.url}")
        callbacks.onBlocked(BlockEvent(Date(), tabLabel, url, reason))
    }

    /**
     * Get away from a blocked in-page navigation. Prefer stepping back to the
     * previous allowed page so scroll position and state survive. Fall back to
     * the tab's home page.
     */
    private fun bounce() {
        webView.evaluateJavascript("window.__as_hide && window.__as_hide();", null)
        // If the recovery load never lands (offline, or it gets blocked too),
        // show the page again rather than leaving a blank screen forever.
        webView.postDelayed({
            webView.evaluateJavascript("window.__as_show && window.__as_show();", null)
        }, 4000)

        val now = System.currentTimeMillis()
        val tooSoon = now - lastBounceAt < 1000
        lastBounceAt = now

        val backUrl = webView.copyBackForwardList().let { list ->
            if (list.currentIndex > 0) list.getItemAtIndex(list.currentIndex - 1)?.url else null
        }

        if (!tooSoon && backUrl != null && policy.decide(backUrl) is PolicyDecision.Allow) {
            webView.goBack()
        } else {
            goHome()
        }
    }

    /** Called from the page bridge, already hopped onto the UI thread. */
    internal fun handleBridgeMessage(json: String) {
        val body = try {
            JSONObject(json)
        } catch (e: Exception) {
            return
        }

        when (body.optString("type")) {
            "url" -> {
                val url = body.optString("url")
                if (url.isEmpty()) return
                currentUrl = url
                callbacks.onStateChanged(this)
                when (val decision = policy.decide(url, inPage = true)) {
                    is PolicyDecision.Allow ->
                        webView.evaluateJavascript("window.__as_show && window.__as_show();", null)
                    is PolicyDecision.External -> {
                        record(url, "Outside link")
                        bounce()
                    }
                    is PolicyDecision.Block -> {
                        record(url, decision.reason)
                        bounce()
                    }
                }
            }

            "owner" -> {
                val username = body.optString("username")
                if (username.isEmpty()) return
                val site = if (body.optString("site") == "instagram") SiteKind.INSTAGRAM else SiteKind.FACEBOOK
                if (site == SiteKind.INSTAGRAM &&
                    !settings.instagramUsername.equals(username, ignoreCase = true)
                ) {
                    Log.i(TAG, "[${tab.id}] learned instagram username $username")
                    callbacks.onLearnUsername(site, username)
                }
            }
        }
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()

            // facebook.com/me lands on your own profile. That redirect is the
            // one reliable way to find out who you are, so let it through and
            // remember the answer.
            if (awaitingOwnProfile && AccessPolicy.siteFor(url) == SiteKind.FACEBOOK) {
                AccessPolicy.ownProfileName(url)?.let { learned ->
                    awaitingOwnProfile = false
                    Log.i(TAG, "[${tab.id}] learned facebook username $learned")
                    callbacks.onLearnUsername(SiteKind.FACEBOOK, learned)
                    policy.options = policy.options.copy(facebookUsername = learned)
                    return false
                }
            }

            return when (val decision = policy.decide(url)) {
                is PolicyDecision.Allow -> false
                is PolicyDecision.External -> {
                    callbacks.onExternal(decision.url)
                    true
                }
                is PolicyDecision.Block -> {
                    record(url, decision.reason)
                    // A login or consent page just finished and tried to send
                    // us to the feed. Go somewhere useful instead of sitting on
                    // the form.
                    if (webView.url == null || policy.isAuthPage(webView.url)) goHome()
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            isLoading = true
            currentUrl = url
            runFallbackScripts()
            callbacks.onStateChanged(this@TabController)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            isLoading = false
            currentUrl = url
            CookieManager.getInstance().flush()
            webView.evaluateJavascript("window.__as_show && window.__as_show();", null)
            callbacks.onStateChanged(this@TabController)
        }
    }

    /** Where a target="_blank" link actually wanted to go. */
    private fun handleNewWindowTarget(url: String) {
        when (val decision = policy.decide(url)) {
            is PolicyDecision.Allow -> webView.loadUrl(url)
            is PolicyDecision.External -> callbacks.onExternal(decision.url)
            is PolicyDecision.Block -> record(url, decision.reason)
        }
    }

    private inner class ChromeClient : WebChromeClient() {

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean = callbacks.onShowFileChooser(filePathCallback, fileChooserParams)

        override fun onPermissionRequest(request: PermissionRequest) {
            callbacks.onPermissionRequest(request)
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            isLoading = newProgress < 100
            callbacks.onStateChanged(this@TabController)
        }

        /**
         * Messenger and Facebook open outbound links in a new window. There is
         * no second window here, so hand the page a throwaway WebView just long
         * enough to learn where it wanted to go, then judge that URL like any
         * other navigation.
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

            val probe = WebView(view.context)
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    handleNewWindowTarget(request.url.toString())
                    v.post { v.destroy() }
                    return true
                }
            }

            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }
    }

    companion object {
        private const val TAG = "AntiSocial"
    }
}

/**
 * The page talks to the app through this. Everything arriving here is written
 * by Facebook's and Instagram's own pages, so it is treated as data: the only
 * thing the app does with it is compare it against the allowlist.
 */
class PageBridge(private val controller: TabController) {
    @JavascriptInterface
    fun post(json: String) {
        controller.webView.post { controller.handleBridgeMessage(json) }
    }
}
