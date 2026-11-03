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
import org.json.JSONArray
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
    private val ownership: OwnershipChecker,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBlocked(event: BlockEvent)
        fun onExternal(url: String)
        /** The app worked out which account is signed in. */
        fun onLearnUsername(site: SiteKind, username: String)
        fun onStateChanged(controller: TabController)
        /** A page finished loading. Used to read your account numbers from the login cookies. */
        fun onPageFinished()
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
        /** A short note for you, like where to tap to post. */
        fun onNotice(controller: TabController, message: String)
    }

    val webView: WebView = WebView(context)
    var policy = AccessPolicy(settings.policyOptions())
        private set

    var isLoading: Boolean = false
        private set
    var currentUrl: String? = null
        private set

    /** True while the app checks whether the post being opened is yours. */
    var isCheckingOwner: Boolean = false
        private set

    /** Human-readable tab name, for the diagnostics log. */
    var tabLabel: String = tab.id

    private var hasLoadedOnce = false
    private var lastBounceAt = 0L
    private var awaitingOwnProfile = false
    private var appliedUserAgent: String = ""
    private var appliedViewport: Int = -1
    private var appliedHiding: Boolean? = null
    private var appliedConfig: String = ""
    /** Bumped for every in-page ownership check, so a slow answer about a post you already left is ignored. */
    private var checkGeneration = 0
    /** True while an in-page check runs. */
    private var inPageCheckActive = false
    /** Real page loads waiting for an ownership answer. */
    private var loadChecksRunning = 0
    /** True while the page is hidden (a post being checked, or on the way out after a block). */
    private var pageHidden = false
    private var destroyed = false
    /** Posts you tapped, and when. A tapped post opens; the next one does not open by itself. */
    private val tapGrants = HashMap<String, Long>()
    /** Posts already open or opened in this tab. Coming back to one is fine. */
    private val openedKeys = HashSet<String>()
    /** The last post shown, to return to when a player moves on to someone else's. */
    private var lastShownPostUrl: String? = null
    /** How the page last changed its own address (pushState, replaceState, ...). */
    private var lastInPageWhy: String = ""
    /** The address the page last reported, to tell where an in-page change came from. */
    private var lastPageUrl: String? = null
    /**
     * When you last tapped anywhere on the page. A tap that does not name a
     * post (a shared-post card, a share link that redirects) still opens the
     * first post it leads to, as long as that post is reached from a page that
     * is not a post itself. A player moving on from one post to the next never
     * counts.
     */
    private var lastClickAt = 0L
    /** A composer to open once its page has loaded. */
    private class PendingCompose(val action: ComposeAction, val site: SiteKind, val steps: JSONArray, val started: Long)
    private var pendingCompose: PendingCompose? = null
    /** The compose action whose script is running on the page. */
    private var composeInFlight: ComposeAction? = null
    /** True while the page shows the "learn your username first" note instead of a site. */
    private var showingHomeNote = false
    private var configScriptHandle: androidx.webkit.ScriptHandler? = null
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

    /**
     * Where this page starts. Your Instagram posts live at your own profile,
     * so that one depends on knowing your username.
     */
    val homeUrl: String?
        get() {
            if (tab != WebTab.INSTAGRAM_MY_POSTS) return tab.home
            val name = settings.instagramUsername
            return if (name.isEmpty()) null else "https://www.instagram.com/$name/"
        }

    fun goHome() {
        val url = homeUrl
        if (url == null) {
            showHomeNote()
            return
        }
        showingHomeNote = false
        load(url)
    }

    /** Your Instagram posts page cannot open before the app knows your username. */
    private fun showHomeNote() {
        showingHomeNote = true
        endOwnerCheck()
        val html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'></head>" +
            "<body style='font-family:sans-serif;padding:32px;text-align:center;color:#666'>" +
            "<p>Open IG Inbox once so the app can learn your Instagram username. Then come back here.</p>" +
            "</body></html>"
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    fun reload() {
        endOwnerCheck()
        val here = webView.url
        // A hidden page is a post that was refused or is still being checked.
        // Reloading it would only show it. Start over instead.
        if (here == null || showingHomeNote || pageHidden || !isAllowedWithoutChecking(here)) goHome()
        else webView.reload()
    }

    fun goBack(): Boolean {
        if (!webView.canGoBack()) return false
        endOwnerCheck()
        pendingCompose = null
        webView.goBack()
        return true
    }

    /**
     * Checks the page you are on against the current rules, and leaves it if
     * it no longer passes. Used when "Open other people's posts I tap" is
     * switched off.
     */
    fun recheckCurrentPage() {
        val here = webView.url ?: return
        if (!hasLoadedOnce || !here.startsWith("http")) return
        if (!isAllowedWithoutChecking(here)) goHome()
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
            // Instagram's + button opens your photo picker, which only works
            // from a real tap, so the app makes it glow instead of pressing it.
            startCompose(action, "https://www.instagram.com/$name/", SiteKind.INSTAGRAM,
                JSONArray().put(JSONObject().put("svg", "New post").put("glow", true).put("name", "plus")))
            return null
        }

        when (action) {
            // Facebook resolves /me to your own profile. Its post box and
            // Reel button sit far down that page, so the app scrolls to them
            // and opens them.
            ComposeAction.POST -> startCompose(action, "https://www.facebook.com/me", SiteKind.FACEBOOK,
                JSONArray().put(JSONObject().put("startsWith", "What's on your mind").put("name", "post-box")))
            ComposeAction.REEL -> startCompose(action, "https://www.facebook.com/me", SiteKind.FACEBOOK,
                JSONArray().put(JSONObject().put("label", "Reel").put("name", "reel-button")))
            ComposeAction.STORY -> load("https://www.facebook.com/stories/create")
            ComposeAction.LIVE -> load("https://www.facebook.com/live/producer")
            ComposeAction.LISTING -> load("https://www.facebook.com/marketplace/create")
        }
        return null
    }

    private fun startCompose(action: ComposeAction, url: String, site: SiteKind, steps: JSONArray) {
        load(url)
        pendingCompose = PendingCompose(action, site, steps, System.currentTimeMillis())
    }

    /** Runs the composer script once the page it needs has loaded. */
    private fun runPendingCompose(url: String?) {
        val pending = pendingCompose ?: return
        if (System.currentTimeMillis() - pending.started > 30_000) {
            pendingCompose = null
            return
        }
        if (url == null || AccessPolicy.siteFor(url) != pending.site) return
        pendingCompose = null
        composeInFlight = pending.action
        webView.evaluateJavascript(Injected.composeScript(pending.steps), null)
    }

    /** What to tell you once the composer script has done its part. */
    private fun composeNotice(action: ComposeAction, opened: Boolean): String? {
        val res = if (tab.site == SiteKind.INSTAGRAM) {
            when {
                action == ComposeAction.STORY && opened -> R.string.notice_ig_story_glow
                action == ComposeAction.STORY -> R.string.notice_ig_story_find
                opened -> R.string.notice_ig_post_glow
                else -> R.string.notice_ig_post_find
            }
        } else {
            if (opened) return null
            if (action == ComposeAction.REEL) R.string.notice_fb_reel_failed else R.string.notice_fb_post_failed
        }
        return webView.context.getString(res)
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
        destroyed = true
        checkGeneration++
        webView.removeJavascriptInterface(Injected.BRIDGE)
        webView.webChromeClient = null
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /** Re-reads settings and reloads if anything that affects the page changed. */
    fun applySettings() {
        val tappedBefore = policy.options.openTappedPosts
        policy = AccessPolicy(settings.policyOptions())
        val turnedOffTapped = tappedBefore && !policy.options.openTappedPosts
        if (turnedOffTapped) {
            openedKeys.clear()
            tapGrants.clear()
            lastClickAt = 0L
        }
        val changed = applyConfiguration()
        if ((changed || showingHomeNote) && hasLoadedOnce) {
            if (!showingHomeNote || homeUrl != null) goHome()
        } else if (turnedOffTapped) {
            // Switching tapped posts off applies to the open page too.
            recheckCurrentPage()
        }
    }

    // MARK: - Internals

    private fun load(url: String) {
        endOwnerCheck()
        // Any other page load cancels a composer that was waiting to open.
        pendingCompose = null
        composeInFlight = null
        awaitingOwnProfile = Uri.parse(url).path?.lowercase()?.trim('/') == "me"
        webView.loadUrl(url)
    }

    /** Drops the in-page ownership check in progress. Its answer will be about a page you have left. */
    private fun endOwnerCheck() {
        checkGeneration++
        inPageCheckActive = false
        updateCheckingState()
    }

    private fun updateCheckingState() {
        val checking = inPageCheckActive || loadChecksRunning > 0
        if (checking != isCheckingOwner) {
            isCheckingOwner = checking
            callbacks.onStateChanged(this)
        }
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
        val config = pageConfig()
        val scriptsChanged = viewport != appliedViewport || hiding != appliedHiding
        val configChanged = config != appliedConfig
        if (scriptsChanged || configChanged) {
            appliedViewport = viewport
            appliedHiding = hiding
            appliedConfig = config
            installScripts(viewport, hiding, config)
            if (scriptsChanged) {
                changed = true
            } else if (webView.url != null) {
                // Who you are and which posts are yours reach the open page
                // directly. No reload needed for that.
                webView.evaluateJavascript(config, null)
            }
        }

        return changed
    }

    /**
     * What the pages need to know: whether only your posts may open, every
     * name the sites use for you, and the posts already known to be yours.
     */
    private fun pageConfig(): String {
        val identity = settings.policyOptions().ownIdentity
        return Injected.configScript(
            tapOpensPosts = settings.openTappedPosts,
            facebookNames = identity.facebook.sorted(),
            instagramNames = identity.instagram.sorted(),
            knownPosts = ownership.recentKeys(600)
        )
    }

    /**
     * Document-start injection is the Android equivalent of a WKUserScript at
     * document start. Older WebViews do not have it, so [Client.onPageStarted]
     * repeats the same scripts as a fallback.
     */
    private fun installScripts(viewport: Int, hiding: Boolean, config: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return

        val origins = setOf(
            "https://*.facebook.com", "https://facebook.com",
            "https://*.instagram.com", "https://instagram.com",
            "https://*.messenger.com", "https://messenger.com"
        )

        configScriptHandle?.remove()
        configScriptHandle = WebViewCompat.addDocumentStartJavaScript(webView, config, origins)

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
        webView.evaluateJavascript(appliedConfig, null)
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
        hidePage()
        // If the recovery load never lands (offline, or it gets blocked too),
        // show the page again rather than leaving a blank screen forever. Not
        // while a post is being checked, though: that one stays covered.
        webView.postDelayed({
            if (!isCheckingOwner) showPage()
        }, 4000)

        val now = System.currentTimeMillis()
        val tooSoon = now - lastBounceAt < 1000
        lastBounceAt = now

        val backUrl = webView.copyBackForwardList().let { list ->
            if (list.currentIndex > 0) list.getItemAtIndex(list.currentIndex - 1)?.url else null
        }

        if (!tooSoon && backUrl != null && isAllowedWithoutChecking(backUrl)) {
            webView.goBack()
        } else {
            goHome()
        }
    }

    /** Allowed outright, or a post already known to be yours. */
    private fun isAllowedWithoutChecking(url: String): Boolean = when (val decision = policy.decide(url)) {
        is PolicyDecision.Allow -> true
        is PolicyDecision.Verify -> ownership.verdict(decision.ref) is Ownership.Mine ||
            (policy.options.openTappedPosts && decision.ref.key in openedKeys)
        else -> false
    }

    private fun hidePage() {
        pageHidden = true
        webView.evaluateJavascript("window.__as_hide && window.__as_hide();", null)
    }

    private fun showPage() {
        pageHidden = false
        webView.evaluateJavascript("window.__as_show && window.__as_show();", null)
    }

    /** The post a URL points at, judged the same way the rules judge it (redirect wrappers included). */
    private fun pendingKey(url: String?): String? {
        if (url == null) return null
        return (policy.decide(url, inPage = true) as? PolicyDecision.Verify)?.ref?.key
    }

    // MARK: - Your posts

    /**
     * A single post is about to show. Open it if it is yours; otherwise block
     * it. For a real page load ([inPage] false) the load was stopped and is
     * started again once the post turns out to be yours. An in-page change has
     * already happened, so the page is hidden until the answer comes back.
     */
    private fun verify(ref: PostRef, url: String, inPage: Boolean) {
        if (inPage) checkGeneration++
        val generation = checkGeneration

        // Yours, or something you tapped: open it now.
        val mine = ownership.verdict(ref) is Ownership.Mine
        if (mine || (policy.options.openTappedPosts && isTapped(ref.key))) {
            if (inPage) inPageCheckActive = false
            updateCheckingState()
            openPost(ref, url, inPage, generation)
            return
        }

        val known = ownership.verdict(ref)
            ?: ref.otherOwner?.let { Ownership.SomeoneElse(it) }
        if (known != null) {
            finishCheck(known, ref, url, inPage, generation)
            return
        }

        if (inPage) {
            inPageCheckActive = true
            hidePage()
        } else {
            loadChecksRunning++
        }
        updateCheckingState()

        Log.i(TAG, "[${tab.id}] checking owner of ${ref.key}")
        ownership.check(ref, policy.options.ownIdentity) { answer ->
            if (!inPage) loadChecksRunning = maxOf(0, loadChecksRunning - 1)
            finishCheck(answer, ref, url, inPage, generation)
        }
    }

    private fun finishCheck(answer: Ownership, ref: PostRef, url: String, inPage: Boolean, generation: Int) {
        if (destroyed) return
        // For in-page changes, act only if this is still the latest check and
        // you are still on that post.
        val isCurrent = generation == checkGeneration
        if (inPage && isCurrent) inPageCheckActive = false
        updateCheckingState()

        // The rules may have changed while the check ran (your name was just
        // learned, say), so the page may now pass outright.
        val here = currentUrl ?: webView.url
        if (inPage && isCurrent && here != null && policy.decide(here, inPage = true) is PolicyDecision.Allow) {
            showPage()
            return
        }
        val actOnPage = isCurrent && pendingKey(here) == ref.key

        when (answer) {
            is Ownership.Mine -> {
                Log.i(TAG, "[${tab.id}] yours: ${ref.key}")
                pushKnown(listOf(ref.key))
                if (!inPage || actOnPage) openPost(ref, url, inPage, generation)
            }
            is Ownership.SomeoneElse, is Ownership.Unknown -> {
                val reason = blockReason(ref, answer)
                if (inPage) {
                    if (actOnPage) {
                        record(url, reason)
                        leaveBlockedPost(ref)
                    }
                } else {
                    record(url, reason)
                    if (isCurrent && (webView.url == null || policy.isAuthPage(webView.url))) goHome()
                }
            }
        }
    }

    /** Shows a post that passed: yours, or one you tapped. */
    private fun openPost(ref: PostRef, url: String, inPage: Boolean, generation: Int) {
        markOpened(ref, url)
        if (inPage) {
            webView.evaluateJavascript(Injected.openedScript(listOf(ref.key)), null)
            showPage()
        } else if (generation == checkGeneration) {
            // The load was stopped while the app checked. Start it again.
            // loadUrl does not come back through shouldOverrideUrlLoading.
            webView.loadUrl(url)
        }
    }

    /** Short words for the "Blocked" banner. */
    private fun blockReason(ref: PostRef, answer: Ownership): String = when {
        !policy.options.openTappedPosts ->
            if (answer is Ownership.Unknown) "Could not confirm it is your post" else "Not your post"
        ref.kind.isVideo -> "Next Reel"
        ref.kind.isStory -> "Next story"
        else -> "Only posts you tap open"
    }

    /**
     * Gets away from a post that moved on by itself. Facebook's Reels player
     * swaps the address in place (replaceState), so there is no history entry
     * to step back to. Going back to the last post shown is the natural way out.
     */
    private fun leaveBlockedPost(ref: PostRef) {
        val previous = lastShownPostUrl
        if (lastInPageWhy == "replaceState" && previous != null && pendingKey(previous) != ref.key) {
            hidePage()
            load(previous)
        } else {
            bounce()
        }
    }

    /**
     * Remembers a tap. A tap on a link to a post lets that post open. Any tap
     * at all also lets the next post reached from a non-post page open (see
     * [lastClickAt]).
     */
    private fun noteTap(href: String?) {
        val now = System.currentTimeMillis()
        lastClickAt = now
        val key = if (href.isNullOrEmpty()) null else pendingKey(href)
        if (key != null) tapGrants[key] = now
        if (tapGrants.size > 200) {
            val cutoff = now - TAP_WINDOW_MS
            tapGrants.entries.removeAll { it.value < cutoff }
        }
    }

    /**
     * Uses up a recent tap for this post. [from] is the page the post was
     * reached from. Null means a real page load, which a player never makes
     * on its own, so it needs no such check.
     */
    private fun useRecentTap(key: String, from: String?, inPage: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastClickAt >= TAP_WINDOW_MS) return false
        if (inPage && (from == null || AccessPolicy.postLink(from) != null)) return false
        lastClickAt = 0L
        tapGrants[key] = now
        return true
    }

    /** A post is open. Coming back to it later is fine. */
    private fun markOpened(ref: PostRef, url: String) {
        openedKeys.add(ref.key)
        lastShownPostUrl = url
        lastClickAt = 0L
    }

    private fun isTapped(key: String): Boolean {
        if (key in openedKeys) return true
        val at = tapGrants[key] ?: return false
        return System.currentTimeMillis() - at < TAP_WINDOW_MS
    }

    /** A link that names you proves the post is yours. Remember it. */
    private fun learnFromUrl(url: String) {
        val key = policy.ownedPostKey(url) ?: return
        if (ownership.markMine(listOf(key)).isNotEmpty()) pushKnown(listOf(key))
    }

    /**
     * Links the page pointed out as yours. Each one is checked here again: it
     * counts only if it names you, or if it sits on your own grid and is the
     * kind of post that grid holds (Reels and videos on Facebook).
     */
    private fun learnLinks(hrefs: List<String>, reportedPage: String) {
        val page = webView.url ?: reportedPage
        val onGrid = policy.isOwnPostGrid(page)
        val gridKind = if (AccessPolicy.siteFor(page) == SiteKind.FACEBOOK) "fb:video:" else "ig:"
        val keys = mutableListOf<String>()
        for (href in hrefs.take(300)) {
            val key = policy.ownedPostKey(href)
                ?: (if (onGrid) AccessPolicy.postLink(href)?.ref?.key?.takeIf { it.startsWith(gridKind) } else null)
                ?: continue
            keys.add(key)
        }
        val learned = ownership.markMine(keys)
        if (learned.isNotEmpty()) {
            Log.i(TAG, "[${tab.id}] learned ${learned.size} of your posts")
            pushKnown(learned)
        }
    }

    /** Tells the open page about posts now known to be yours. */
    private fun pushKnown(keys: List<String>) {
        if (keys.isEmpty() || webView.url == null) return
        webView.evaluateJavascript(Injected.addKnownScript(keys), null)
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
                val why = body.optString("why")
                lastInPageWhy = why
                val from = lastPageUrl
                lastPageUrl = url
                callbacks.onStateChanged(this)
                when (val decision = policy.decide(url, inPage = true)) {
                    is PolicyDecision.Allow -> {
                        endOwnerCheck()
                        learnFromUrl(url)
                        // A post that opens outright (yours, or in a group)
                        // is where a player that moves on gets sent back to.
                        lastShownPostUrl = if (AccessPolicy.postLink(url) != null) url else null
                        showPage()
                    }
                    is PolicyDecision.Verify -> {
                        if (why == "pushState" || why == "replaceState" || why == "poll") {
                            useRecentTap(decision.ref.key, from, inPage = true)
                        }
                        verify(decision.ref, url, inPage = true)
                    }
                    is PolicyDecision.External -> {
                        endOwnerCheck()
                        record(url, "Outside link")
                        bounce()
                    }
                    is PolicyDecision.Block -> {
                        endOwnerCheck()
                        record(url, decision.reason)
                        bounce()
                    }
                }
            }

            "tap" -> noteTap(body.optString("href"))

            "compose" -> {
                val action = composeInFlight ?: return
                composeInFlight = null
                val opened = body.optBoolean("ok")
                Log.i(TAG, "[${tab.id}] compose $action opened: $opened")
                composeNotice(action, opened)?.let { callbacks.onNotice(this, it) }
            }

            "links" -> {
                val page = body.optString("page")
                val list = body.optJSONArray("hrefs") ?: return
                val hrefs = (0 until list.length()).mapNotNull { list.optString(it).takeIf { h -> h.isNotEmpty() } }
                learnLinks(hrefs, page)
            }

            "owner" -> {
                val username = body.optString("username")
                if (username.isEmpty()) return
                val site = if (body.optString("site") == "instagram") SiteKind.INSTAGRAM else SiteKind.FACEBOOK
                // Fill in an empty username only. Pages also show other
                // people's pictures (commenters, say), so a guess never
                // replaces a known name.
                if (site == SiteKind.INSTAGRAM && settings.instagramUsername.isEmpty()) {
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

            // A link you tapped yourself. Leaving the page also cancels a
            // composer that was waiting to open.
            if (request.isForMainFrame && request.hasGesture()) {
                noteTap(url)
                pendingCompose = null
            }

            return when (val decision = policy.decide(url)) {
                is PolicyDecision.Allow -> {
                    learnFromUrl(url)
                    false
                }
                is PolicyDecision.Verify -> {
                    val ref = decision.ref
                    // A share link you tapped redirects here.
                    if (request.isForMainFrame) useRecentTap(ref.key, null, inPage = false)
                    if (ownership.verdict(ref) is Ownership.Mine ||
                        (policy.options.openTappedPosts && isTapped(ref.key))
                    ) {
                        markOpened(ref, url)
                        false
                    } else if (!request.isForMainFrame) {
                        // A frame inside the page never replaces the page.
                        true
                    } else {
                        // Stop the load while the app checks. It starts again
                        // if the post turns out to be yours.
                        verify(decision.ref, url, inPage = false)
                        true
                    }
                }
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
            lastPageUrl = url
            // A new page starts out visible.
            pageHidden = false
            runFallbackScripts()
            callbacks.onStateChanged(this@TabController)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            isLoading = false
            currentUrl = url
            CookieManager.getInstance().flush()
            // Never show a post that is still being checked or was refused.
            if (!isCheckingOwner && url != null && isAllowedWithoutChecking(url)) showPage()
            runPendingCompose(url)
            callbacks.onStateChanged(this@TabController)
            callbacks.onPageFinished()
        }
    }

    /** Where a target="_blank" link actually wanted to go. */
    private fun handleNewWindowTarget(url: String, tapped: Boolean) {
        if (tapped) noteTap(url)
        when (val decision = policy.decide(url)) {
            is PolicyDecision.Allow -> webView.loadUrl(url)
            is PolicyDecision.Verify ->
                if (ownership.verdict(decision.ref) is Ownership.Mine) webView.loadUrl(url)
                else verify(decision.ref, url, inPage = false)
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
                    handleNewWindowTarget(request.url.toString(), isUserGesture)
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
        /** How long a tap counts for the post it opens. */
        private const val TAP_WINDOW_MS = 10_000L
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
