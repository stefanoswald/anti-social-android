package com.stefanoswald.antisocial

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
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
import com.stefanoswald.antisocial.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), TabController.Callbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AppSettings

    private val controllers = LinkedHashMap<String, TabController>()
    private var currentTab: WebTab = WebTab.FACEBOOK_MESSAGES

    /** Set while the system file picker is open, for the page waiting on it. */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private lateinit var filePicker: ActivityResultLauncher<Intent>
    private lateinit var permissionPrompt: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings.registerListener(this)

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
            select(WebTab.byId(tabIdForMenu(item.itemId)))
            true
        }
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

        val restored = savedInstanceState?.getString(STATE_TAB)
        select(WebTab.byId(restored ?: WebTab.FACEBOOK_MESSAGES.id))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, currentTab.id)
    }

    override fun onDestroy() {
        settings.unregisterListener(this)
        controllers.values.forEach { it.destroy() }
        controllers.clear()
        super.onDestroy()
    }

    // MARK: - Tabs

    /** One controller per tab, kept alive so switching tabs never reloads. */
    private fun controller(tab: WebTab): TabController = controllers.getOrPut(tab.id) {
        TabController(this, tab, settings, this)
    }

    private fun select(tab: WebTab) {
        currentTab = tab
        val controller = controller(tab)

        binding.webHost.removeAllViews()
        (controller.webView.parent as? android.view.ViewGroup)?.removeView(controller.webView)
        binding.webHost.addView(controller.webView)
        controller.loadHomeIfNeeded()

        controller.tabLabel = getString(tab.titleRes)
        binding.tabTitle.setText(tab.titleRes)
        // Keep the highlighted tab in step after a restore.
        val menuId = menuIdForTab(tab)
        if (binding.bottomNav.selectedItemId != menuId) binding.bottomNav.selectedItemId = menuId
        binding.composeButton.visibility = if (settings.allowPosting) View.VISIBLE else View.GONE
        refreshChrome(controller)
    }

    private fun menuIdForTab(tab: WebTab): Int = when (tab) {
        WebTab.FACEBOOK_MESSAGES -> R.id.nav_messages
        WebTab.FACEBOOK_MARKETPLACE -> R.id.nav_marketplace
        WebTab.FACEBOOK_NOTIFICATIONS -> R.id.nav_alerts
        WebTab.INSTAGRAM_INBOX -> R.id.nav_ig_inbox
        WebTab.INSTAGRAM_NOTIFICATIONS -> R.id.nav_ig_alerts
    }

    private fun tabIdForMenu(itemId: Int): String = when (itemId) {
        R.id.nav_messages -> WebTab.FACEBOOK_MESSAGES.id
        R.id.nav_marketplace -> WebTab.FACEBOOK_MARKETPLACE.id
        R.id.nav_alerts -> WebTab.FACEBOOK_NOTIFICATIONS.id
        R.id.nav_ig_inbox -> WebTab.INSTAGRAM_INBOX.id
        else -> WebTab.INSTAGRAM_NOTIFICATIONS.id
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
                append(getString(R.string.diagnostics_page)).append("\n\n")
                append(map).append("\n\n")
                append(getString(R.string.diagnostics_blocks)).append("\n")
                if (log.isEmpty()) {
                    append(getString(R.string.diagnostics_none))
                } else {
                    log.forEach { append("${it.reason}\t${it.tabTitle}\t${it.url}\n") }
                }
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

    private fun refreshChrome(controller: TabController) {
        binding.progress.visibility = if (controller.isLoading) View.VISIBLE else View.GONE
        binding.pageHost.text = hostLabel(controller.currentUrl)
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
