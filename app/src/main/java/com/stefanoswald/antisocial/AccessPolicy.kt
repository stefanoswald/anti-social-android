package com.stefanoswald.antisocial

import android.net.Uri

/** Which of the two sites a URL belongs to. */
enum class SiteKind { FACEBOOK, INSTAGRAM, OTHER }

/** Settings the policy needs, as a plain value that is cheap to copy around. */
data class PolicyOptions(
    val facebookUsername: String = "",
    val instagramUsername: String = "",
    val allowReelPermalinks: Boolean = false,
    val allowGroupPosts: Boolean = true,
    /**
     * Lets you publish: the composer, Marketplace listings, creating a Story,
     * and Go Live. Watching other people's Reels and Stories stays blocked
     * either way, so this opens the outbox, not the feed.
     */
    val allowPosting: Boolean = true
)

/** What to do with a navigation. */
sealed class PolicyDecision {
    /** Load it. */
    object Allow : PolicyDecision()

    /** Do not load it. [reason] is short and user-facing ("Home feed"). */
    data class Block(val reason: String) : PolicyDecision()

    /** The link leaves Facebook and Instagram. The app decides what to do. */
    data class External(val url: String) : PolicyDecision()
}

/**
 * The allowlist. Everything not explicitly allowed here is blocked.
 *
 * The rules work on URL paths, so they hold no matter what the page looks
 * like. Element hiding (see [Injected]) is only cosmetic on top of this.
 */
class AccessPolicy(var options: PolicyOptions = PolicyOptions()) {

    /**
     * [inPage] is true when the page changed its own URL (a single-page-app
     * navigation) rather than loading fresh. Some create flows reuse paths
     * that mean something else on a cold load, so the two are not identical.
     */
    fun decide(rawUrl: String, inPage: Boolean = false): PolicyDecision {
        val uri = try {
            Uri.parse(rawUrl)
        } catch (e: Exception) {
            return PolicyDecision.Block("Unknown link")
        }

        when (uri.scheme?.lowercase()) {
            null -> return PolicyDecision.Block("Unknown link")
            "about", "blob", "data" -> return PolicyDecision.Allow
            "http", "https" -> Unit
            "mailto", "tel", "sms", "geo" -> return PolicyDecision.External(rawUrl)
            else -> {
                // fb://, instagram://, intent:// and friends. These try to open
                // the real apps. Never.
                return PolicyDecision.Block("App link")
            }
        }

        val host = (uri.host ?: "").lowercase()

        if (hostMatches(host, LINK_SHIM_HOSTS)) {
            return shimDecision(uri)
        }

        // Messenger has no feed. Every path there is a conversation.
        if (hostMatches(host, MESSENGER_HOSTS)) return PolicyDecision.Allow

        // Image and attachment servers. A tapped photo or a downloaded file
        // can land here as a full page. Nothing to scroll.
        if (hostMatches(host, STATIC_HOSTS)) return PolicyDecision.Allow

        return when (siteFor(rawUrl)) {
            SiteKind.FACEBOOK -> decideFacebook(uri, inPage)
            SiteKind.INSTAGRAM -> decideInstagram(uri, inPage)
            SiteKind.OTHER -> PolicyDecision.External(rawUrl)
        }
    }

    /**
     * Login, checkpoint, and consent pages. After one of these finishes it
     * usually redirects to the home feed. When that redirect gets blocked the
     * app should jump to the tab's home instead of leaving the login form up.
     */
    fun isAuthPage(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrEmpty()) return true
        val uri = Uri.parse(rawUrl)
        val first = segments(uri).firstOrNull() ?: ""
        return when (siteFor(rawUrl)) {
            SiteKind.FACEBOOK -> first in FACEBOOK_AUTH_SEGMENTS
            SiteKind.INSTAGRAM -> first in INSTAGRAM_AUTH_SEGMENTS
            SiteKind.OTHER -> false
        }
    }

    // MARK: - Facebook

    private fun decideFacebook(uri: Uri, inPage: Boolean): PolicyDecision {
        val segments = segments(uri)
        val first = segments.firstOrNull() ?: return PolicyDecision.Block("Home feed")

        if (first == "l.php") return shimDecision(uri)

        if (first in FACEBOOK_ALLOWED_SEGMENTS) return PolicyDecision.Allow

        // Accounts without a vanity name live at /profile.php?id=<number>.
        if (first == "profile.php" && options.facebookUsername.isNotEmpty() &&
            queryValue(uri, "id") == options.facebookUsername
        ) {
            return PolicyDecision.Allow
        }

        // Publishing. These sit underneath segments that are otherwise
        // blocked, so they have to be checked before the blocklist.
        // Creating a Story is allowed; watching other people's is not.
        // Going live is allowed; the Live directory of other people's
        // broadcasts is not.
        if (options.allowPosting && segments.size >= 2) {
            val pair = first to segments[1]
            if (pair in FACEBOOK_PUBLISH_PATHS) return PolicyDecision.Allow
        }

        FACEBOOK_BLOCKED_SEGMENTS[first]?.let { return PolicyDecision.Block(it) }

        when (first) {
            "reel" -> {
                if (segments.size >= 2 && options.allowReelPermalinks) return PolicyDecision.Allow
                return PolicyDecision.Block("Reels")
            }

            "watch" -> {
                // /watch/?v=123 is one video. /watch/ alone is the video feed.
                if (queryValue(uri, "v") != null) return PolicyDecision.Allow
                return PolicyDecision.Block("Watch")
            }

            "video", "videos" -> {
                if (segments.size >= 2 || queryValue(uri, "v") != null) return PolicyDecision.Allow
                return PolicyDecision.Block("Video feed")
            }

            "groups" -> {
                // /groups/<id>/posts/<id> and /groups/<id>/permalink/<id> are
                // single posts. Anything shorter is a feed.
                if (segments.size >= 4 && segments[2] in setOf("posts", "permalink") &&
                    options.allowGroupPosts
                ) {
                    return PolicyDecision.Allow
                }
                return PolicyDecision.Block("Groups")
            }
        }

        // Everything left looks like /<username>/... which is a profile.
        val isOwnProfile = options.facebookUsername.isNotEmpty() &&
            first == options.facebookUsername.lowercase()

        if (segments.size >= 2) {
            return when (segments[1]) {
                "posts", "permalink", "photos" -> PolicyDecision.Allow
                // /<user>/videos/<id> is one video. /<user>/videos is a list.
                "videos" -> if (segments.size >= 3) PolicyDecision.Allow else PolicyDecision.Block("Video list")
                "reels" -> PolicyDecision.Block("Reels")
                else -> if (isOwnProfile) PolicyDecision.Allow else PolicyDecision.Block("Profile")
            }
        }

        return if (isOwnProfile) PolicyDecision.Allow else PolicyDecision.Block("Profile")
    }

    // MARK: - Instagram

    private fun decideInstagram(uri: Uri, inPage: Boolean): PolicyDecision {
        val segments = segments(uri)
        val first = segments.firstOrNull() ?: return PolicyDecision.Block("Home feed")

        if (first == "linkshim") return shimDecision(uri)

        if (first in INSTAGRAM_ALLOWED_SEGMENTS) return PolicyDecision.Allow

        // The create flow. Careful here: "create" is also a real person's
        // username, so instagram.com/create on its own is that profile, not a
        // composer. Only the deeper step paths are the create flow. When the
        // page pushes the URL itself the user cannot have typed it, so any
        // step name is fine; on a cold load only the known steps are.
        if (options.allowPosting && first == "create" && segments.size >= 2) {
            if (inPage || segments[1] in INSTAGRAM_CREATE_STEPS) return PolicyDecision.Allow
        }

        INSTAGRAM_BLOCKED_SEGMENTS[first]?.let { return PolicyDecision.Block(it) }

        when (first) {
            // A single post. This is where you answer comments.
            "p", "tv" -> return if (segments.size >= 2) PolicyDecision.Allow else PolicyDecision.Block("Feed")

            "reel" -> {
                if (segments.size >= 2 && options.allowReelPermalinks) return PolicyDecision.Allow
                return PolicyDecision.Block("Reels")
            }
        }

        // Newer links put the username first: /<user>/p/<code>/ and
        // /<user>/reel/<code>/. Still a single post.
        if (segments.size >= 3) {
            when (segments[1]) {
                "p", "tv" -> return PolicyDecision.Allow
                "reel" -> return if (options.allowReelPermalinks) PolicyDecision.Allow
                else PolicyDecision.Block("Reels")
            }
        }

        val isOwnProfile = options.instagramUsername.isNotEmpty() &&
            first == options.instagramUsername.lowercase()

        if (isOwnProfile) {
            if (segments.size >= 2 && segments[1] == "reels") return PolicyDecision.Block("Reels")
            return PolicyDecision.Allow
        }

        return PolicyDecision.Block("Profile")
    }

    // MARK: - Link shims

    /**
     * Facebook and Instagram wrap outbound links in a redirect page with the
     * real target in a `u` parameter. Unwrap it and judge the real target.
     */
    private fun shimDecision(uri: Uri): PolicyDecision {
        val target = queryValue(uri, "u")
        if (!target.isNullOrEmpty()) return decide(target)
        return PolicyDecision.Block("Redirect")
    }

    companion object {
        private val FACEBOOK_HOSTS = listOf("facebook.com", "fb.com", "messenger.com", "meta.com", "fb.me")
        private val MESSENGER_HOSTS = listOf("messenger.com")
        private val STATIC_HOSTS = listOf("fbcdn.net", "fbsbx.com", "facebook.net", "cdninstagram.com")
        private val INSTAGRAM_HOSTS = listOf("instagram.com", "cdninstagram.com", "ig.me")
        private val LINK_SHIM_HOSTS =
            listOf("l.facebook.com", "lm.facebook.com", "l.messenger.com", "l.instagram.com")

        private val FACEBOOK_AUTH_SEGMENTS = setOf(
            "login", "login.php", "checkpoint", "two_step_verification", "two_factor",
            "recover", "confirmemail.php", "device-based", "auth", "oauth", "dialog",
            "x", "security", "cookie", "consent", "privacy", "logout", "logout.php",
            "accountscenter", "recover.php"
        )

        /** First path segments that are always fine on Facebook. */
        private val FACEBOOK_ALLOWED_SEGMENTS = setOf(
            // The three things this app exists for.
            "messages", "marketplace", "notifications",
            // Login and account plumbing.
            "login", "login.php", "checkpoint", "two_step_verification", "two_factor",
            "recover", "recover.php", "confirmemail.php", "device-based", "auth", "oauth",
            "dialog", "x", "security", "cookie", "consent", "privacy", "policies",
            "policy", "legal", "terms", "help", "settings", "logout", "logout.php",
            "accountscenter", "ajax", "api", "async", "rsrc.php", "si", "images",
            "language", "mobile",
            // Redirect helpers used by notification links.
            "n", "nd", "share", "permalink.php", "story.php", "photo.php", "photo",
            "comment", "ufi", "a", "notifications.php", "messages.php",
            // Your own creator tools. No feed on these pages.
            "professional_dashboard", "content", "me"
        )

        /** Publishing paths that live under an otherwise blocked segment. */
        private val FACEBOOK_PUBLISH_PATHS = setOf(
            "stories" to "create",
            "live" to "producer",
            "reel" to "create",
            "media" to "upload",
            "video" to "upload"
        )

        private val FACEBOOK_BLOCKED_SEGMENTS = mapOf(
            "home.php" to "Home feed",
            "reels" to "Reels",
            "stories" to "Stories",
            "friends" to "Friends",
            "gaming" to "Gaming",
            "games" to "Gaming",
            "events" to "Events",
            "memories" to "Memories",
            "saved" to "Saved",
            "bookmarks" to "Menu",
            "menu" to "Menu",
            "pages" to "Pages",
            "hashtag" to "Hashtag feed",
            "search" to "Search",
            "fundraisers" to "Fundraisers",
            "dating" to "Dating",
            "jobs" to "Jobs",
            "weather" to "Weather",
            "news" to "News",
            "offers" to "Offers",
            "live" to "Live",
            "places" to "Places",
            "people" to "People",
            "public" to "Public posts",
            "topic" to "Topics",
            "explore" to "Explore",
            "feeds" to "Feeds",
            "profile.php" to "Profile"
        )

        private val INSTAGRAM_AUTH_SEGMENTS = setOf(
            "accounts", "challenge", "auth_platform", "oauth", "login", "two_factor",
            "consent", "session", "coming_soon", "logout"
        )

        private val INSTAGRAM_ALLOWED_SEGMENTS = setOf(
            "direct", "notifications",
            "accounts", "challenge", "auth_platform", "oauth", "api", "ajax", "graphql",
            "logging", "qp", "web", "login", "logout", "session", "consent", "terms",
            "legal", "privacy", "about", "emails", "two_factor", "coming_soon",
            "static", "data", "push", "help", "settings", "your_activity"
        )

        /** Steps in Instagram's post composer. */
        private val INSTAGRAM_CREATE_STEPS = setOf(
            "select", "style", "crop", "filter", "details", "caption", "share",
            "edit", "story", "reel", "post", "cover", "location", "tag"
        )

        private val INSTAGRAM_BLOCKED_SEGMENTS = mapOf(
            "explore" to "Explore",
            "reels" to "Reels",
            "stories" to "Stories",
            "search" to "Search",
            "shopping" to "Shopping",
            "shop" to "Shopping",
            "locations" to "Locations",
            "topics" to "Topics",
            "guides" to "Guides",
            "live" to "Live",
            "lite" to "Feed",
            "threads" to "Threads"
        )

        fun siteFor(rawUrl: String): SiteKind {
            val host = (try { Uri.parse(rawUrl).host } catch (e: Exception) { null } ?: "").lowercase()
            if (hostMatches(host, FACEBOOK_HOSTS)) return SiteKind.FACEBOOK
            if (hostMatches(host, INSTAGRAM_HOSTS)) return SiteKind.INSTAGRAM
            return SiteKind.OTHER
        }

        /**
         * Reads a username out of a Facebook profile URL, which is what
         * facebook.com/me redirects to. Returns null for anything not shaped
         * like a profile, so a redirect to the feed teaches nothing.
         */
        fun ownProfileName(rawUrl: String): String? {
            if (siteFor(rawUrl) != SiteKind.FACEBOOK) return null
            val uri = Uri.parse(rawUrl)
            val segments = segments(uri)

            if (segments.firstOrNull() == "profile.php") {
                val id = queryValue(uri, "id")
                if (!id.isNullOrEmpty()) return id
            }

            if (segments.size != 1) return null
            val name = segments[0]
            // Reject the things a vanity URL can never be.
            if (name.contains(".") || name.length < 3) return null
            if (FACEBOOK_BLOCKED_SEGMENTS.containsKey(name)) return null
            if (name in FACEBOOK_ALLOWED_SEGMENTS) return null
            return name
        }

        private fun hostMatches(host: String, domains: List<String>): Boolean =
            domains.any { host == it || host.endsWith(".$it") }

        /** Lowercased, non-empty path segments. */
        fun segments(uri: Uri): List<String> =
            (uri.path ?: "").lowercase().split("/").filter { it.isNotEmpty() }

        fun queryValue(uri: Uri, name: String): String? = try {
            uri.getQueryParameter(name)
        } catch (e: Exception) {
            null
        }
    }
}
