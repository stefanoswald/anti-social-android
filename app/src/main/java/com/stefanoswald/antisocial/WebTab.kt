package com.stefanoswald.antisocial

/**
 * One page in the app. Each page owns a WebView that starts at [home] and is
 * bounced back to [home] whenever a blocked page tries to load.
 *
 * [shortTitleRes] is the label in the switch at the top of a tab that holds
 * two pages.
 */
enum class WebTab(
    val id: String,
    val titleRes: Int,
    val shortTitleRes: Int,
    val home: String
) {
    FACEBOOK_MESSAGES(
        "fb-messages", R.string.tab_messages, R.string.tab_messages,
        "https://www.facebook.com/messages/"
    ),
    FACEBOOK_MARKETPLACE(
        "fb-marketplace", R.string.tab_marketplace, R.string.tab_marketplace,
        "https://www.facebook.com/marketplace/"
    ),
    FACEBOOK_NOTIFICATIONS(
        "fb-notifications", R.string.tab_alerts, R.string.page_alerts,
        "https://www.facebook.com/notifications/"
    ),

    /** Your own Facebook profile. Facebook answers /me with a redirect to it. */
    FACEBOOK_MY_POSTS(
        "fb-my-posts", R.string.page_my_posts, R.string.page_my_posts,
        "https://www.facebook.com/me"
    ),

    /** The groups you are in. Each group opens in full. The mixed Groups feed stays blocked. */
    FACEBOOK_GROUPS(
        "fb-groups", R.string.page_groups, R.string.page_groups,
        "https://www.facebook.com/groups/joins/"
    ),
    INSTAGRAM_INBOX(
        "ig-inbox", R.string.tab_ig_inbox, R.string.tab_ig_inbox,
        "https://www.instagram.com/direct/inbox/"
    ),
    INSTAGRAM_NOTIFICATIONS(
        "ig-notifications", R.string.tab_ig_alerts, R.string.page_alerts,
        "https://www.instagram.com/notifications/"
    ),

    /**
     * Your own Instagram grid. The real address needs your username, so the
     * tab controller swaps it in when it loads (see TabController.homeUrl).
     */
    INSTAGRAM_MY_POSTS(
        "ig-my-posts", R.string.page_my_posts, R.string.page_my_posts,
        "https://www.instagram.com/"
    );

    val site: SiteKind get() = AccessPolicy.siteFor(home)

    companion object {
        fun byId(id: String?): WebTab = entries.firstOrNull { it.id == id } ?: FACEBOOK_MESSAGES
    }
}

/**
 * One button in the bottom bar. Most hold a single page. The two Alerts tabs
 * also hold your own posts, and the Facebook one holds your groups. You pick
 * the page with a switch at the top.
 */
enum class TabGroup(
    val id: String,
    val titleRes: Int,
    val menuId: Int,
    val pages: List<WebTab>
) {
    MESSAGES("messages", R.string.tab_messages, R.id.nav_messages, listOf(WebTab.FACEBOOK_MESSAGES)),
    MARKETPLACE("marketplace", R.string.tab_marketplace, R.id.nav_marketplace, listOf(WebTab.FACEBOOK_MARKETPLACE)),
    FACEBOOK(
        "facebook", R.string.tab_alerts, R.id.nav_alerts,
        listOf(WebTab.FACEBOOK_NOTIFICATIONS, WebTab.FACEBOOK_MY_POSTS, WebTab.FACEBOOK_GROUPS)
    ),
    INSTAGRAM_INBOX("ig-inbox", R.string.tab_ig_inbox, R.id.nav_ig_inbox, listOf(WebTab.INSTAGRAM_INBOX)),
    INSTAGRAM(
        "instagram", R.string.tab_ig_alerts, R.id.nav_ig_alerts,
        listOf(WebTab.INSTAGRAM_NOTIFICATIONS, WebTab.INSTAGRAM_MY_POSTS)
    );

    companion object {
        fun byId(id: String?): TabGroup = entries.firstOrNull { it.id == id } ?: MESSAGES
        fun byMenu(menuId: Int): TabGroup = entries.firstOrNull { it.menuId == menuId } ?: MESSAGES
        fun containing(page: WebTab): TabGroup = entries.firstOrNull { page in it.pages } ?: MESSAGES
    }
}

/** The things you can start from the compose button. */
enum class ComposeAction(val labelRes: Int) {
    POST(R.string.compose_post),
    STORY(R.string.compose_story),
    LIVE(R.string.compose_live),
    LISTING(R.string.compose_listing)
}
