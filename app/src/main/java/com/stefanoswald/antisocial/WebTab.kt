package com.stefanoswald.antisocial

/**
 * One tab in the app. Each tab owns a WebView that starts at [home] and is
 * bounced back to [home] whenever a blocked page tries to load.
 */
enum class WebTab(
    val id: String,
    val titleRes: Int,
    val iconRes: Int,
    val home: String
) {
    FACEBOOK_MESSAGES(
        "fb-messages", R.string.tab_messages, R.drawable.ic_tab_messages,
        "https://www.facebook.com/messages/"
    ),
    FACEBOOK_MARKETPLACE(
        "fb-marketplace", R.string.tab_marketplace, R.drawable.ic_tab_marketplace,
        "https://www.facebook.com/marketplace/"
    ),
    FACEBOOK_NOTIFICATIONS(
        "fb-notifications", R.string.tab_alerts, R.drawable.ic_tab_alerts,
        "https://www.facebook.com/notifications/"
    ),
    INSTAGRAM_INBOX(
        "ig-inbox", R.string.tab_ig_inbox, R.drawable.ic_tab_ig_inbox,
        "https://www.instagram.com/direct/inbox/"
    ),
    INSTAGRAM_NOTIFICATIONS(
        "ig-notifications", R.string.tab_ig_alerts, R.drawable.ic_tab_ig_alerts,
        "https://www.instagram.com/notifications/"
    );

    val site: SiteKind get() = AccessPolicy.siteFor(home)

    companion object {
        fun byId(id: String?): WebTab = entries.firstOrNull { it.id == id } ?: FACEBOOK_MESSAGES
    }
}

/** The things you can start from the compose button. */
enum class ComposeAction(val labelRes: Int) {
    POST(R.string.compose_post),
    STORY(R.string.compose_story),
    LIVE(R.string.compose_live),
    LISTING(R.string.compose_listing)
}
