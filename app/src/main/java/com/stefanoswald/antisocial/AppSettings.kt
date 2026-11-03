package com.stefanoswald.antisocial

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** User-adjustable behavior, stored in shared preferences. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** Your own Facebook username. The app fills this in from the /me redirect. */
    var facebookUsername: String
        get() = prefs.getString(KEY_FB_USERNAME, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_FB_USERNAME, value.trim()).apply()

    /** Your own Instagram username, learned from the profile link in the nav. */
    var instagramUsername: String
        get() = prefs.getString(KEY_IG_USERNAME, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_IG_USERNAME, value.trim()).apply()

    /** Your Facebook account number, read from Facebook's own login cookie. */
    var facebookUserId: String
        get() = prefs.getString(KEY_FB_ID, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_FB_ID, value.trim()).apply()

    /** Your Instagram account number, read the same way. */
    var instagramUserId: String
        get() = prefs.getString(KEY_IG_ID, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_IG_ID, value.trim()).apply()

    /**
     * Someone else's post, Reel, video, or story opens when you tap it, so you
     * can read it and reply. Moving on to the next one by swiping or autoplay
     * stays blocked. Off means only your own posts open.
     */
    var openTappedPosts: Boolean
        get() = prefs.getBoolean(KEY_OPEN_TAPPED, true)
        set(value) = prefs.edit().putBoolean(KEY_OPEN_TAPPED, value).apply()

    /**
     * Facebook's mobile site refuses to show Messenger and bounces to the feed,
     * so Facebook gets the desktop site by default.
     */
    var desktopModeFacebook: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_FB, true)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_FB, value).apply()

    var desktopModeInstagram: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_IG, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_IG, value).apply()

    /** Turns on the compose button. */
    var allowPosting: Boolean
        get() = prefs.getBoolean(KEY_POSTING, true)
        set(value) = prefs.edit().putBoolean(KEY_POSTING, value).apply()

    /** Hide feed links, Reels tabs, and "open the app" nags inside the pages. */
    var hideDistractions: Boolean
        get() = prefs.getBoolean(KEY_HIDE, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE, value).apply()

    /** Show a short banner when something gets blocked. */
    var showBlockToasts: Boolean
        get() = prefs.getBoolean(KEY_TOASTS, true)
        set(value) = prefs.edit().putBoolean(KEY_TOASTS, value).apply()

    /** Open links that leave Facebook and Instagram in the real browser. */
    var externalLinksInBrowser: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL, true)
        set(value) = prefs.edit().putBoolean(KEY_EXTERNAL, value).apply()

    /**
     * How wide the desktop site lays itself out, in CSS pixels. The page is
     * scaled to fit the phone, so narrower means bigger text.
     */
    var desktopViewportWidth: Int
        get() = prefs.getString(KEY_WIDTH, "760")?.toIntOrNull() ?: 760
        set(value) = prefs.edit().putString(KEY_WIDTH, value.toString()).apply()

    /** The viewport width to force for a site, or 0 to leave the page alone. */
    fun viewportWidth(site: SiteKind): Int = when (site) {
        SiteKind.FACEBOOK -> if (desktopModeFacebook) desktopViewportWidth else 0
        SiteKind.INSTAGRAM -> if (desktopModeInstagram) desktopViewportWidth else 0
        SiteKind.OTHER -> 0
    }

    fun userAgent(site: SiteKind): String = when (site) {
        SiteKind.FACEBOOK -> if (desktopModeFacebook) DESKTOP_UA else MOBILE_UA
        SiteKind.INSTAGRAM -> if (desktopModeInstagram) DESKTOP_UA else MOBILE_UA
        SiteKind.OTHER -> MOBILE_UA
    }

    /** Snapshot used by the policy engine so it never touches UI state. */
    fun policyOptions(): PolicyOptions = PolicyOptions(
        facebookUsername = facebookUsername,
        instagramUsername = instagramUsername,
        facebookUserId = facebookUserId,
        instagramUserId = instagramUserId,
        allowPosting = allowPosting,
        openTappedPosts = openTappedPosts
    )

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val KEY_FB_USERNAME = "facebookUsername"
        const val KEY_IG_USERNAME = "instagramUsername"
        const val KEY_FB_ID = "facebookUserId"
        const val KEY_IG_ID = "instagramUserId"
        const val KEY_OPEN_TAPPED = "openTappedPosts"
        const val KEY_DESKTOP_FB = "desktopModeFacebook"
        const val KEY_DESKTOP_IG = "desktopModeInstagram"
        const val KEY_POSTING = "allowPosting"
        const val KEY_HIDE = "hideDistractions"
        const val KEY_TOASTS = "showBlockToasts"
        const val KEY_EXTERNAL = "externalLinksInBrowser"
        const val KEY_WIDTH = "desktopViewportWidth"

        /** Chrome on a modern Android phone. */
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"

        /** Chrome on a desktop, which is what makes Messenger show up. */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Safari/537.36"
    }
}
