package com.stefanoswald.antisocial

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.stefanoswald.antisocial.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), TabController.Callbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings
    /** Which posts are yours. Shared by every tab. */
    private lateinit var ownership: OwnershipChecker

    private val controllers = LinkedHashMap<String, TabController>()
    private var currentGroup: TabGroup = TabGroup.MESSAGES
    private var currentTab: WebTab = WebTab.FACEBOOK_MESSAGES
    /** Which page each two-page tab is showing (Alerts or My Posts). */
    private val pageByGroup = HashMap<TabGroup, WebTab>()
    /** Set while the page switch is being filled in, so that does not count as a tap. */
    private var fillingPageTabs = false
    /** Set while the bottom bar is moved by code, so that does not count as a tap. */
    private var syncingNav = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var configPushPending = false
    private var lastIdentityRefresh = 0L
    private var lookingUpUsername = false
    private var lastUsernameLookup = 0L

    /** Set while the system file picker is open, for the page waiting on it. */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private lateinit var filePicker: ActivityResultLauncher<Intent>
    private lateinit var permissionPrompt: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        ownership = OwnershipChecker(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings.registerListener(this)
        // New posts learned as yours go into every page's settings, in one
        // batch, a moment later.
        ownership.onChange = { scheduleConfigPush() }

        filePicker = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            // The page blocks until it hears back, so a cancel must still
            // answer, with an empty list.
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                    ?: emptyArray()
            )
        }

        permissionPrompt = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted ->
            val request = pendingPermissionRequest
            pendingPermissionRequest = null
            if (request == null) return@registerForActivityResult
            if (granted.values.all { it }) request.grant(request.resources) else request.deny()
        }

        binding.backButton.setOnClickListener { controller(currentTab).goBack() }
        binding.reloadButton.setOnClickListener { controller(currentTab).reload() }
        binding.composeButton.setOnClickListener { showComposeMenu(it) }
        binding.moreButton.setOnClickListener { showMoreMenu(it) }

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (!syncingNav) selectGroup(TabGroup.byMenu(item.itemId))
            true
        }

        binding.pageTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (fillingPageTabs) return
                val page = tab.tag as? WebTab ?: return
                pageByGroup[currentGroup] = page
                select(page)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                controller(currentTab).goHome()
            }
        })
        binding.bottomNav.setOnItemReselectedListener {
            // Tapping the tab you are already on jumps back to its home page.
            controller(currentTab).goHome()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!controller(currentTab).goBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        val restored = WebTab.byId(savedInstanceState?.getString(STATE_TAB) ?: WebTab.FACEBOOK_MESSAGES.id)
        val group = TabGroup.containing(restored)
        pageByGroup[group] = restored
        selectGroup(group)

        refreshIdentity(force = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, currentTab.id)
    }

    override fun onDestroy() {
        settings.unregisterListener(this)
        ownership.onChange = null
        ownership.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        controllers.values.forEach { it.destroy() }
        controllers.clear()
        super.onDestroy()
    }

    // MARK: - Tabs

    /** One controller per page, kept alive so switching never reloads. */
    private fun controller(tab: WebTab): TabController = controllers.getOrPut(tab.id) {
        TabController(this, tab, settings, ownership, this)
    }

    /** A bottom-bar tab. Shows the page it was last on. */
    private fun selectGroup(group: TabGroup) {
        currentGroup = group
        val page = pageByGroup[group] ?: group.pages.first()

        // The page switch, on the tabs that hold more than one page.
        fillingPageTabs = true
        binding.pageTabs.removeAllTabs()
        if (group.pages.size > 1) {
            for (item in group.pages) {
                val tab = binding.pageTabs.newTab().setText(item.shortTitleRes).setTag(item)
                binding.pageTabs.addTab(tab, item == page)
            }
            binding.pageTabs.visibility = View.VISIBLE
        } else {
            binding.pageTabs.visibility = View.GONE
        }
        fillingPageTabs = false

        // Keep the highlighted tab in step after a restore.
        if (binding.bottomNav.selectedItemId != group.menuId) {
            syncingNav = true
            binding.bottomNav.selectedItemId = group.menuId
            syncingNav = false
        }
        binding.tabTitle.setText(group.titleRes)
        select(page)
    }

    private fun select(tab: WebTab) {
        currentTab = tab
        val controller = controller(tab)

        binding.webHost.removeAllViews()
        (controller.webView.parent as? android.view.ViewGroup)?.removeView(controller.webView)
        binding.webHost.addView(controller.webView)
        controller.loadHomeIfNeeded()

        controller.tabLabel = getString(tab.titleRes)
        binding.composeButton.visibility = if (settings.allowPosting) View.VISIBLE else View.GONE
        refreshChrome(controller)
    }

    // MARK: - Menus

    /**
     * Instagram has no web page for creating a Story or going live, and
     * Marketplace is a Facebook thing, so the Instagram tabs only offer a post.
     */
    private fun showComposeMenu(anchor: View) {
        val actions = if (currentTab.site == SiteKind.INSTAGRAM) {
            listOf(ComposeAction.POST)
        } else {
            ComposeAction.entries.toList()
        }

        if (actions.size == 1) {
            runCompose(actions.first())
            return
        }

        PopupMenu(this, anchor).apply {
            actions.forEachIndexed { index, action -> menu.add(0, index, index, getString(action.labelRes)) }
            setOnMenuItemClickListener { item ->
                runCompose(actions[item.itemId])
                true
            }
            show()
        }
    }

    private fun runCompose(action: ComposeAction) {
        controller(currentTab).compose(action)?.let { problem ->
            Snackbar.make(binding.root, problem, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 1, getString(R.string.menu_home, getString(currentTab.titleRes)))
            menu.add(0, 2, 2, getString(R.string.menu_diagnostics))
            menu.add(0, 3, 3, getString(R.string.menu_settings))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> controller(currentTab).goHome()
                    2 -> showDiagnostics()
                    3 -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                true
            }
            show()
        }
    }

    private fun showDiagnostics() {
        controller(currentTab).pageMap { map ->
            val log = BlockLog.recent()
            val body = buildString {
                // Who the app thinks you are comes first, so it is never cut off.
                append(
                    getString(
                        R.string.accounts_line,
                        identityLabel(settings.facebookUsername, settings.facebookUserId),
                        identityLabel(settings.instagramUsername, settings.instagramUserId)
                    )
                ).append("\n")
                append(getString(R.string.known_posts, ownership.knownCount)).append("\n\n")
                append(getString(R.string.diagnostics_blocks)).append("\n")
                if (log.isEmpty()) {
                    append(getString(R.string.diagnostics_none))
                } else {
                    log.forEach { append("${it.reason}\t${it.tabTitle}\t${it.url}\n") }
                }
                append("\n\n").append(getString(R.string.diagnostics_page)).append("\n\n")
                append(map)
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.menu_diagnostics)
                .setMessage(body.take(4000))
                .setPositiveButton(R.string.share) { _, _ ->
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    startActivity(Intent.createChooser(share, getString(R.string.share)))
                }
                .setNegativeButton(R.string.close, null)
                .setNeutralButton(R.string.forget_posts) { _, _ -> ownership.forgetAll() }
                .show()
        }
    }

    // MARK: - TabController.Callbacks

    override fun onBlocked(event: BlockEvent) {
        BlockLog.add(event)
        if (settings.showBlockToasts) {
            Snackbar.make(
                binding.root,
                getString(R.string.blocked_toast, event.reason),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    override fun onExternal(url: String) {
        if (!settings.externalLinksInBrowser) {
            onBlocked(BlockEvent(java.util.Date(), currentTab.id, url, "Outside link"))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Snackbar.make(binding.root, R.string.no_browser, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onLearnUsername(site: SiteKind, username: String) {
        when (site) {
            SiteKind.FACEBOOK -> settings.facebookUsername = username
            SiteKind.INSTAGRAM -> settings.instagramUsername = username
            SiteKind.OTHER -> Unit
        }
        controllers.values.forEach { it.applySettings() }
    }

    override fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        // Only one picker at a time; release any earlier one so the page that
        // opened it is not left waiting forever.
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        return try {
            filePicker.launch(params.createIntent())
            true
        } catch (e: Exception) {
            pendingFileCallback = null
            Snackbar.make(binding.root, R.string.no_picker, Snackbar.LENGTH_SHORT).show()
            false
        }
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        // Posting a photo or going live needs the camera and the microphone.
        // Ask Android for them the first time the page asks us.
        val needed = mutableListOf<String>()
        request.resources.forEach { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> needed.add(Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> needed.add(Manifest.permission.RECORD_AUDIO)
            }
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            request.grant(request.resources)
            return
        }

        pendingPermissionRequest = request
        permissionPrompt.launch(missing.toTypedArray())
    }

    override fun onStateChanged(controller: TabController) {
        if (controller.tab == currentTab) refreshChrome(controller)
    }

    override fun onPageFinished() {
        refreshIdentity(force = false)
    }

    private fun refreshChrome(controller: TabController) {
        binding.progress.visibility = if (controller.isLoading) View.VISIBLE else View.GONE
        binding.checkingOverlay.visibility = if (controller.isCheckingOwner) View.VISIBLE else View.GONE
        binding.pageHost.text = hostLabel(controller.currentUrl)
    }

    private fun identityLabel(username: String, id: String): String {
        val parts = listOf(username, id).filter { it.isNotEmpty() }
        return if (parts.isEmpty()) getString(R.string.not_known_yet) else parts.joinToString(" · ")
    }

    // MARK: - Who you are

    /**
     * Reads your account numbers from the sites' own login cookies (c_user on
     * Facebook, ds_user_id on Instagram). Links often name you by number, so
     * the app needs both your username and your number to spot your posts.
     */
    private fun refreshIdentity(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastIdentityRefresh < 20_000) return
        lastIdentityRefresh = now

        cookieValue("https://www.facebook.com", "c_user")?.let { id ->
            if (AccessPolicy.isDigits(id) && settings.facebookUserId != id) settings.facebookUserId = id
        }
        cookieValue("https://www.instagram.com", "ds_user_id")?.let { id ->
            if (AccessPolicy.isDigits(id) && settings.instagramUserId != id) settings.instagramUserId = id
        }

        // Knows your Facebook number but not your username yet: look it up on
        // your own profile page.
        // Accounts without a custom username have nothing to find, so this
        // runs at most every few hours.
        val facebookId = settings.facebookUserId
        if (settings.facebookUsername.isEmpty() && facebookId.isNotEmpty() && !lookingUpUsername &&
            now - lastUsernameLookup > USERNAME_LOOKUP_GAP_MS
        ) {
            lookingUpUsername = true
            lastUsernameLookup = now
            ownership.facebookUsername(facebookId) { name ->
                lookingUpUsername = false
                if (name != null && settings.facebookUsername.isEmpty()) settings.facebookUsername = name
            }
        }
    }

    private fun cookieValue(url: String, name: String): String? {
        val header = try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Exception) {
            null
        } ?: return null
        return header.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun scheduleConfigPush() {
        if (configPushPending) return
        configPushPending = true
        mainHandler.postDelayed({
            configPushPending = false
            controllers.values.forEach { it.applySettings() }
        }, 1000)
    }

    private fun hostLabel(url: String?): String {
        if (url.isNullOrEmpty()) return " "
        val uri = Uri.parse(url)
        val host = (uri.host ?: "").removePrefix("www.")
        val path = uri.path.orEmpty()
        return if (path.isEmpty() || path == "/") host else host + path
    }

    // MARK: - Settings changes

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        controllers.values.forEach { it.applySettings() }
        binding.composeButton.visibility = if (settings.allowPosting) View.VISIBLE else View.GONE
    }

    companion object {
        private const val STATE_TAB = "currentTab"
        private const val USERNAME_LOOKUP_GAP_MS = 6 * 3_600_000L
    }
}

/** A small in-memory ring of blocked navigations, for the Diagnostics screen. */
object BlockLog {
    private val events = ArrayDeque<BlockEvent>()

    @Synchronized
    fun add(event: BlockEvent) {
        events.addFirst(event)
        while (events.size > 100) events.removeLast()
    }

    @Synchronized
    fun recent(): List<BlockEvent> = events.toList()

    @Synchronized
    fun clear() = events.clear()
}
