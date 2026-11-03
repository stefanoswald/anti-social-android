package com.stefanoswald.antisocial

import android.net.Uri

/** Which of the two sites a URL belongs to. */
enum class SiteKind { FACEBOOK, INSTAGRAM, OTHER }

/** Settings the policy needs, as a plain value that is cheap to copy around. */
data class PolicyOptions(
    val facebookUsername: String = "",
    val instagramUsername: String = "",
    /**
     * Your numeric account IDs, read from each site's own login cookie. Links
     * often name you by number instead of by username.
     */
    val facebookUserId: String = "",
    val instagramUserId: String = "",
    /**
     * Lets you publish: the composer, Marketplace listings, creating a Story,
     * and Go Live.
     */
    val allowPosting: Boolean = true,
    /**
     * Other people's posts, Reels, and stories open when you tap them. The
     * next one never opens by itself: swiping or autoplay into it is blocked.
     * When this is off, only your own posts open, plus everything inside
     * your groups.
     */
    val openTappedPosts: Boolean = true
) {
    /** Every name the sites might use for you, lowercased. */
    val ownIdentity: OwnIdentity
        get() = OwnIdentity(
            facebook = names(facebookUsername, facebookUserId),
            instagram = names(instagramUsername, instagramUserId)
        )

    private fun names(vararg values: String): Set<String> =
        values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}

/** Who you are on each site, as lowercased usernames and numeric IDs. */
data class OwnIdentity(
    val facebook: Set<String> = emptySet(),
    val instagram: Set<String> = emptySet()
) {
    fun names(site: SiteKind): Set<String> = when (site) {
        SiteKind.FACEBOOK -> facebook
        SiteKind.INSTAGRAM -> instagram
        SiteKind.OTHER -> emptySet()
    }
}

/** What kind of single thing a link opens. */
enum class PostKind {
    FACEBOOK_REEL, FACEBOOK_VIDEO, FACEBOOK_PHOTO, FACEBOOK_POST, FACEBOOK_GROUP_POST, FACEBOOK_STORY,
    INSTAGRAM_POST, INSTAGRAM_REEL,
    /** instagram.com/reels/<code>/ opens the swipeable Reels player. */
    INSTAGRAM_REELS_PLAYER,
    INSTAGRAM_STORY, INSTAGRAM_HIGHLIGHT;

    /** Reels and videos, the things a player swipes through. */
    val isVideo: Boolean
        get() = this == FACEBOOK_REEL || this == FACEBOOK_VIDEO || this == INSTAGRAM_REEL || this == INSTAGRAM_REELS_PLAYER

    val isStory: Boolean
        get() = this == FACEBOOK_STORY || this == INSTAGRAM_STORY || this == INSTAGRAM_HIGHLIGHT
}

/** One post, Reel, video, photo, or story, named so the app can check whose it is. */
data class PostRef(
    val site: SiteKind,
    /**
     * A stable name for the post, like "fb:video:1299494788874394" or
     * "ig:DefPxYVO9YH". The app remembers your posts by this.
     */
    val key: String,
    /** The page to fetch to find out who made the post. */
    val lookupUrl: String,
    val kind: PostKind,
    /**
     * Set when the link itself names someone else as the owner. Such a post
     * opens only if you tapped it, or if the app already knows it is yours
     * (a collab, say).
     */
    val otherOwner: String? = null
)

/** What a link to a single post says about itself. */
data class PostLink(
    val ref: PostRef,
    /** The username or ID in the link, lowercased, if the link names one. */
    val owner: String?,
    /** Posts and photos inside a group. Everything in your groups opens. */
    val inGroup: Boolean = false
) {
    val kind: PostKind get() = ref.kind
}

/** What to do with a navigation. */
sealed class PolicyDecision {
    /** Load it. */
    object Allow : PolicyDecision()

    /** Do not load it. [reason] is short and user-facing ("Home feed"). */
    data class Block(val reason: String) : PolicyDecision()

    /** The link leaves Facebook and Instagram. The app decides what to do. */
    data class External(val url: String) : PolicyDecision()

    /**
     * A single post. It opens if it is yours, or if you tapped it. Anything
     * else (the next Reel, autoplay) is blocked.
     */
    data class Verify(val ref: PostRef) : PolicyDecision()
}

/**
 * The rules. Everything that is a feed is blocked; the rest is allowed only
 * when it is listed here.
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

    /**
     * When a link itself proves the post is yours (your username or ID is in
     * it), returns the post's key so later bare links to the same post open
     * right away.
     */
    fun ownedPostKey(rawUrl: String): String? {
        val link = postLink(rawUrl) ?: return null
        val owner = link.owner ?: return null
        return if (isOwn(owner, link.ref.site)) link.ref.key else null
    }

    /**
     * Pages that list only your own posts: your Reels and Videos tabs on
     * Facebook, and your grid on Instagram. Every post linked from one of
     * these is yours, even when the link does not say so.
     */
    fun isOwnPostGrid(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return false }
        val segments = segments(uri)
        return when (siteFor(rawUrl)) {
            SiteKind.FACEBOOK -> {
                if (segments.firstOrNull() == "profile.php") {
                    val id = queryValue(uri, "id") ?: return false
                    if (!isOwn(id, SiteKind.FACEBOOK)) return false
                    val tab = (queryValue(uri, "sk") ?: "").lowercase()
                    tab.startsWith("reels") || tab.startsWith("videos")
                } else {
                    segments.size >= 2 && isOwn(segments[0], SiteKind.FACEBOOK) &&
                        (segments[1] == "reels" || segments[1] == "videos")
                }
            }
            SiteKind.INSTAGRAM -> {
                val first = segments.firstOrNull() ?: return false
                isOwn(first, SiteKind.INSTAGRAM) && (segments.size == 1 || segments[1] == "reels")
            }
            SiteKind.OTHER -> false
        }
    }

    // MARK: - Your posts

    private fun isOwn(name: String, site: SiteKind): Boolean {
        val lowered = name.lowercase()
        return lowered.isNotEmpty() && lowered in options.ownIdentity.names(site)
    }

    /**
     * Whether the app knows you well enough to say that [owner] is not you.
     * A number can only be ruled out once your ID is known, and a name once
     * your username is known.
     */
    private fun canRuleOut(owner: String, site: SiteKind): Boolean {
        val names = options.ownIdentity.names(site)
        return if (isDigits(owner)) names.any { isDigits(it) } else names.any { !isDigits(it) }
    }

    private fun decidePost(link: PostLink): PolicyDecision {
        // Everything inside your groups opens.
        if (link.inGroup) return PolicyDecision.Allow
        val site = link.ref.site
        val owner = link.owner
        if (owner != null) {
            if (isOwn(owner, site)) return PolicyDecision.Allow
            if (canRuleOut(owner, site)) return PolicyDecision.Verify(link.ref.copy(otherOwner = owner))
        }
        return PolicyDecision.Verify(link.ref)
    }

    // MARK: - Facebook

    private fun decideFacebook(uri: Uri, inPage: Boolean): PolicyDecision {
        val segments = segments(uri)
        val first = segments.firstOrNull() ?: return PolicyDecision.Block("Home feed")

        if (first == "l.php") return shimDecision(uri)

        // One post, Reel, video, photo, or story. Checked before anything else
        // because some of these sit under segments that are otherwise always
        // allowed (photo, permalink.php) or always blocked (stories).
        facebookPostLink(uri)?.let { return decidePost(it) }

        if (first in FACEBOOK_ALLOWED_SEGMENTS) return PolicyDecision.Allow

        // Accounts without a vanity name live at /profile.php?id=<number>.
        if (first == "profile.php") {
            val id = queryValue(uri, "id")
            if (id != null && isOwn(id, SiteKind.FACEBOOK)) return PolicyDecision.Allow
        }

        // Groups: any one group is fine, with everything in it. The mixed
        // feed of all your groups and the suggestions are not.
        if (first == "groups") return decideGroups(uri, segments)

        // Publishing. These sit underneath segments that are otherwise
        // blocked, so they have to be checked before the blocklist.
        // Creating a Story is allowed; the Stories feed is not.
        // Going live is allowed; the Live directory of other people's
        // broadcasts is not.
        if (options.allowPosting && segments.size >= 2) {
            val pair = first to segments[1]
            if (pair in FACEBOOK_PUBLISH_PATHS) return PolicyDecision.Allow
        }

        // Your own story archive.
        if (first == "stories" && segments.getOrNull(1) == "archive") return PolicyDecision.Allow

        FACEBOOK_BLOCKED_SEGMENTS[first]?.let { return PolicyDecision.Block(it) }

        when (first) {
            "reel" -> return PolicyDecision.Block("Reels")
            // /watch/?v=123 is one video and was handled above. /watch/ alone
            // is the video feed.
            "watch" -> return PolicyDecision.Block("Watch")
            "video", "videos", "video.php" -> return PolicyDecision.Block("Video feed")
        }

        // Everything left looks like /<username>/... which is a profile.
        // Yours is open, with all its tabs (posts, Reels, videos, photos).
        if (isOwn(first, SiteKind.FACEBOOK)) return PolicyDecision.Allow

        return PolicyDecision.Block("Profile")
    }

    private fun decideGroups(uri: Uri, segments: List<String>): PolicyDecision =
        when (segments.getOrNull(1)) {
            // facebook.com/groups/ is the mixed feed of all your groups.
            null -> if (queryValue(uri, "category") == "create") PolicyDecision.Allow
            else PolicyDecision.Block("Groups feed")
            "feed" -> PolicyDecision.Block("Groups feed")
            "discover" -> PolicyDecision.Block("Group suggestions")
            // joins (your groups), create, and any one group.
            else -> PolicyDecision.Allow
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

        // One post, Reel, or story. This is where you answer comments.
        instagramPostLink(uri)?.let { return decidePost(it) }

        INSTAGRAM_BLOCKED_SEGMENTS[first]?.let { return PolicyDecision.Block(it) }

        when (first) {
            "reel" -> return PolicyDecision.Block("Reels")
            "p", "tv" -> return PolicyDecision.Block("Feed")
        }

        if (isOwn(first, SiteKind.INSTAGRAM)) {
            // Posts other people tagged you in are their posts. The grid opens
            // only when their posts open when you tap them.
            if (segments.getOrNull(1) == "tagged" && !options.openTappedPosts) {
                return PolicyDecision.Block("Tagged posts")
            }
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
            // The things this app exists for.
            "messages", "marketplace", "notifications",
            // Login and account plumbing.
            "login", "login.php", "checkpoint", "two_step_verification", "two_factor",
            "recover", "recover.php", "confirmemail.php", "device-based", "auth", "oauth",
            "dialog", "x", "security", "cookie", "consent", "privacy", "policies",
            "policy", "legal", "terms", "help", "settings", "logout", "logout.php",
            "accountscenter", "ajax", "api", "async", "rsrc.php", "si", "images",
            "language", "mobile",
            // Redirect helpers used by notification links. Single posts under
            // these (photo, permalink.php, story.php) are judged as posts first.
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
            "reels" to "create",
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

        /** First segments that are Facebook features, never usernames. */
        private val FACEBOOK_ROUTE_SEGMENTS = setOf(
            "reel", "watch", "video", "videos", "video.php", "groups", "profile.php",
            "l.php", "media", "sharer", "sharer.php", "plugins", "story", "stories"
        )

        /** Second segments under /stories/ that are pages, not someone's stories. */
        private val FACEBOOK_STORY_PAGES = setOf("create", "archive")

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

        /** Words that sit where a post code would, but are pages, not posts. */
        private val INSTAGRAM_NON_CODES = setOf("audio", "comments", "liked_by", "embed", "create", "explore")

        private val SHORTCODE = Regex("^[A-Za-z0-9_-]{5,80}$")
        private val DIGITS = Regex("^[0-9]+$")
        private val USERNAME = Regex("^[A-Za-z0-9._]{1,30}$")

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
            if (isFacebookRoute(name)) return null
            return name
        }

        /** Describes a link to one post, Reel, video, photo, or story. Null for anything else. */
        fun postLink(rawUrl: String): PostLink? {
            val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return null }
            return when (siteFor(rawUrl)) {
                SiteKind.FACEBOOK -> facebookPostLink(uri)
                SiteKind.INSTAGRAM -> instagramPostLink(uri)
                SiteKind.OTHER -> null
            }
        }

        fun isFacebookRoute(segment: String): Boolean {
            val s = segment.lowercase()
            return s in FACEBOOK_ROUTE_SEGMENTS || s in FACEBOOK_ALLOWED_SEGMENTS ||
                FACEBOOK_BLOCKED_SEGMENTS.containsKey(s)
        }

        fun isInstagramRoute(segment: String): Boolean {
            val s = segment.lowercase()
            return s in setOf("p", "reel", "tv", "linkshim", "create") || s in INSTAGRAM_ALLOWED_SEGMENTS ||
                INSTAGRAM_BLOCKED_SEGMENTS.containsKey(s)
        }

        private fun facebookPostLink(uri: Uri): PostLink? {
            val raw = rawSegments(uri)
            val first = raw.firstOrNull()?.lowercase() ?: return null
            val lookup = facebookLookupUrl(uri)

            fun link(key: String, owner: String?, kind: PostKind, inGroup: Boolean = false) = PostLink(
                ref = PostRef(SiteKind.FACEBOOK, key, lookup, kind),
                owner = owner?.takeIf { it.isNotEmpty() }?.lowercase(),
                inGroup = inGroup
            )

            when (first) {
                "reel" -> {
                    if (raw.size < 2 || !isDigits(raw[1])) return null
                    return link("fb:video:${raw[1]}", null, PostKind.FACEBOOK_REEL)
                }
                "watch", "video.php" -> {
                    val video = queryValue(uri, "v")
                    if (video == null || !isDigits(video)) return null
                    return link("fb:video:$video", null, PostKind.FACEBOOK_VIDEO)
                }
                "videos", "video" -> {
                    if (raw.size < 2 || !isDigits(raw[1])) return null
                    return link("fb:video:${raw[1]}", null, PostKind.FACEBOOK_VIDEO)
                }
                "photo", "photo.php" -> {
                    val photo = queryValue(uri, "fbid")
                    if (photo == null || !isDigits(photo)) return null
                    val set = queryValue(uri, "set") ?: ""
                    // Photos in a group carry the group (idorvanity) or a group set.
                    val inGroup = !queryValue(uri, "idorvanity").isNullOrEmpty() ||
                        set.startsWith("g.") || set.startsWith("gm.")
                    return link("fb:photo:$photo", ownerOfPhotoSet(set), PostKind.FACEBOOK_PHOTO, inGroup)
                }
                "permalink.php", "story.php" -> {
                    val story = queryValue(uri, "story_fbid")
                    if (story.isNullOrEmpty()) return null
                    return link("fb:post:$story", queryValue(uri, "id"), PostKind.FACEBOOK_POST)
                }
                "groups" -> {
                    if (raw.size < 4 || raw[2].lowercase() !in setOf("posts", "permalink")) return null
                    return link(
                        "fb:group:${raw[1].lowercase()}:${raw[3]}", null, PostKind.FACEBOOK_GROUP_POST,
                        inGroup = true
                    )
                }
                "stories" -> {
                    // /stories/<owner id>/<story>/ is one person's stories.
                    if (raw.size < 2 || raw[1].lowercase() in FACEBOOK_STORY_PAGES) return null
                    val bucket = raw[1]
                    return link("fb:story:${bucket.lowercase()}", bucket, PostKind.FACEBOOK_STORY)
                }
            }

            // /<username>/posts/<id>, /<username>/videos/<id>, /<username>/photos/.../<id>
            if (raw.size < 3 || isFacebookRoute(first)) return null
            return when (raw[1].lowercase()) {
                "posts", "permalink" -> link("fb:post:${raw[2]}", first, PostKind.FACEBOOK_POST)
                "videos" -> raw.drop(2).lastOrNull { isDigits(it) }
                    ?.let { link("fb:video:$it", first, PostKind.FACEBOOK_VIDEO) }
                "photos" -> raw.drop(2).lastOrNull { isDigits(it) }
                    ?.let { link("fb:photo:$it", first, PostKind.FACEBOOK_PHOTO) }
                else -> null
            }
        }

        /**
         * Photo links carry the album they came from. "pb.<owner id>.<...>" is
         * the owner's profile photos, which names the owner.
         */
        private fun ownerOfPhotoSet(set: String?): String? {
            val parts = set?.split(".") ?: return null
            if (parts.size < 2 || parts[0] != "pb") return null
            return parts[1].takeIf { isDigits(it) }
        }

        /**
         * The post's own page without tracking clutter, on the desktop host,
         * which is the version that names the owner in its header.
         */
        private fun facebookLookupUrl(uri: Uri): String {
            val keep = listOf("v", "fbid", "story_fbid", "id", "set")
            val query = keep.mapNotNull { name ->
                queryValue(uri, name)?.let { "$name=${Uri.encode(it)}" }
            }.joinToString("&")
            val host = (uri.host ?: "www.facebook.com").lowercase().let {
                if (it == "facebook.com" || it == "m.facebook.com" || it == "mbasic.facebook.com" ||
                    it == "web.facebook.com"
                ) "www.facebook.com" else it
            }
            val path = uri.encodedPath ?: "/"
            return "https://$host$path" + if (query.isEmpty()) "" else "?$query"
        }

        private fun instagramPostLink(uri: Uri): PostLink? {
            val raw = rawSegments(uri)
            val first = raw.firstOrNull()?.lowercase() ?: return null

            fun link(code: String, owner: String?, kind: PostKind) = PostLink(
                ref = PostRef(SiteKind.INSTAGRAM, "ig:$code", "https://www.instagram.com/p/$code/", kind),
                owner = owner?.lowercase()
            )

            if (first == "stories") {
                if (raw.size < 2) return null
                if (raw[1].lowercase() == "highlights") {
                    if (raw.size < 3 || !isDigits(raw[2])) return null
                    val id = raw[2]
                    return PostLink(
                        ref = PostRef(
                            SiteKind.INSTAGRAM, "ig:highlight:$id",
                            "https://www.instagram.com/stories/highlights/$id/", PostKind.INSTAGRAM_HIGHLIGHT
                        ),
                        owner = null
                    )
                }
                val user = raw[1]
                if (!USERNAME.matches(user)) return null
                return PostLink(
                    ref = PostRef(
                        SiteKind.INSTAGRAM, "ig:story:${user.lowercase()}",
                        "https://www.instagram.com/stories/${user.lowercase()}/", PostKind.INSTAGRAM_STORY
                    ),
                    owner = user.lowercase()
                )
            }

            if (first in setOf("p", "tv", "reel", "reels")) {
                if (raw.size < 2 || !isShortcode(raw[1])) return null
                val kind = when (first) {
                    "reel" -> PostKind.INSTAGRAM_REEL
                    "reels" -> PostKind.INSTAGRAM_REELS_PLAYER
                    else -> PostKind.INSTAGRAM_POST
                }
                return link(raw[1], null, kind)
            }

            // Newer links put the username first: /<user>/p/<code>/ and
            // /<user>/reel/<code>/.
            if (raw.size < 3 || !isShortcode(raw[2]) || isInstagramRoute(first)) return null
            return when (raw[1].lowercase()) {
                "p", "tv" -> link(raw[2], first, PostKind.INSTAGRAM_POST)
                "reel" -> link(raw[2], first, PostKind.INSTAGRAM_REEL)
                else -> null
            }
        }

        private fun isShortcode(text: String): Boolean =
            SHORTCODE.matches(text) && text.lowercase() !in INSTAGRAM_NON_CODES

        fun isDigits(text: String): Boolean = DIGITS.matches(text)

        private fun hostMatches(host: String, domains: List<String>): Boolean =
            domains.any { host == it || host.endsWith(".$it") }

        /** Lowercased, non-empty path segments. */
        fun segments(uri: Uri): List<String> = rawSegments(uri).map { it.lowercase() }

        /**
         * Path segments as written. Instagram post codes and some Facebook
         * post IDs are case-sensitive, so these are not lowercased.
         */
        fun rawSegments(uri: Uri): List<String> =
            (uri.path ?: "").split("/").filter { it.isNotEmpty() }

        fun queryValue(uri: Uri, name: String): String? = try {
            uri.getQueryParameter(name)
        } catch (e: Exception) {
            null
        }
    }
}
