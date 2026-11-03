package com.stefanoswald.antisocial

import android.util.Base64

/** The answer to "is this post yours?" */
sealed class Ownership {
    object Mine : Ownership()
    data class SomeoneElse(val owner: String) : Ownership()
    /** The page did not say, or the app does not know your account yet. */
    object Unknown : Ownership()
}

/**
 * Reads a fetched post page and works out who made the post.
 *
 * Facebook and Instagram put the post's own address in the page header
 * (`og:url` and the canonical link), and that address carries the owner's
 * username: facebook.com/<owner>/videos/<id>/ or
 * instagram.com/<owner>/reel/<code>/. Facebook also tucks the owner's ID into
 * its app link. This is plain text work with no web view involved, so it is
 * easy to test.
 */
object OwnershipParser {

    private val META_TAG = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val OWNER_JSON = Regex("\"(?:owner|video_owner)\":\\{\"__typename\":\"(?:User|Page)\",\"id\":\"(\\d+)\"")
    private val STORY_OWNER = Regex("_I(\\d+):")
    private val DESCRIPTION_OWNER = Regex(" - ([A-Za-z0-9._]{1,30}) on ")

    /**
     * Who made the post on [html], as lowercased usernames or numeric IDs,
     * best guess first. Empty when the page does not say.
     */
    fun owners(html: String, finalUrl: String?, site: SiteKind): List<String> {
        val found = mutableListOf<String>()
        fun add(value: String?) {
            val v = value?.lowercase() ?: return
            if (v.isNotEmpty() && v !in found) found.add(v)
        }

        val addresses = mutableListOf<String>()
        if (finalUrl != null) addresses.add(finalUrl)
        addresses.addAll(pageUrls(html))
        for (address in addresses) {
            if (AccessPolicy.siteFor(address) == site) add(AccessPolicy.postLink(address)?.owner)
        }

        val head = headSection(html)
        when (site) {
            SiteKind.FACEBOOK -> {
                val appLinks = metaContents(head, "property", "al:ios:url") +
                    metaContents(head, "property", "al:android:url")
                for (appLink in appLinks) add(facebookOwnerInAppLink(decodeEntities(appLink)))
                if (found.isEmpty()) add(OWNER_JSON.find(html)?.groupValues?.get(1))
            }
            SiteKind.INSTAGRAM -> {
                if (found.isEmpty()) {
                    // "3 likes, 0 comments - magictrickguy on October 12, 2026: ..."
                    for (description in metaContents(head, "name", "description")) {
                        add(DESCRIPTION_OWNER.find(decodeEntities(description))?.groupValues?.get(1))
                    }
                }
            }
            SiteKind.OTHER -> Unit
        }
        return found
    }

    /**
     * Turns the owners found on a page into an answer. Says "someone else"
     * only when it could have recognised you in the same form: a number is
     * compared with your ID, and a name with your username.
     */
    fun verdict(owners: List<String>, ownNames: Set<String>): Ownership {
        if (owners.isEmpty()) return Ownership.Unknown
        if (owners.any { it in ownNames }) return Ownership.Mine
        val knowsId = ownNames.any { AccessPolicy.isDigits(it) }
        val knowsName = ownNames.any { !AccessPolicy.isDigits(it) }
        for (owner in owners) {
            val isId = AccessPolicy.isDigits(owner)
            if ((isId && knowsId) || (!isId && knowsName)) return Ownership.SomeoneElse(owner)
        }
        return Ownership.Unknown
    }

    /** The page's own address as the page states it: og:url first, then the canonical link. */
    fun pageUrls(html: String): List<String> {
        val head = headSection(html)
        return (metaContents(head, "property", "og:url") + linkHrefs(head, "canonical"))
            .map { decodeEntities(it) }
    }

    /**
     * Facebook's app link for a post looks like
     * fb://watch/discover/story?id=<base64 of "S:_I<owner id>:VK:<video id>">.
     */
    fun facebookOwnerInAppLink(link: String): String? {
        val query = link.substringAfter('?', "")
        val encoded = query.split("&")
            .firstOrNull { it.startsWith("id=") }
            ?.removePrefix("id=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: return null
        val decoded = decodeBase64(encoded) ?: return null
        return STORY_OWNER.find(decoded)?.groupValues?.get(1)
    }

    // MARK: - Text helpers

    /** The page header, where the meta tags live. */
    fun headSection(html: String): String {
        val end = html.indexOf("</head>", ignoreCase = true)
        return if (end >= 0) html.substring(0, end) else html.take(400_000)
    }

    fun metaContents(html: String, attribute: String, value: String): List<String> =
        META_TAG.findAll(html).mapNotNull { match ->
            val tag = match.value
            if (attributeValue(attribute, tag)?.lowercase() != value.lowercase()) null
            else attributeValue("content", tag)
        }.toList()

    fun linkHrefs(html: String, rel: String): List<String> =
        LINK_TAG.findAll(html).mapNotNull { match ->
            val tag = match.value
            if (attributeValue("rel", tag)?.lowercase() != rel.lowercase()) null
            else attributeValue("href", tag)
        }.toList()

    fun attributeValue(name: String, tag: String): String? {
        val doubleQuoted = Regex("\\b${Regex.escape(name)}\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        val singleQuoted = Regex("\\b${Regex.escape(name)}\\s*=\\s*'([^']*)'", RegexOption.IGNORE_CASE)
        return doubleQuoted.find(tag)?.groupValues?.get(1) ?: singleQuoted.find(tag)?.groupValues?.get(1)
    }

    fun decodeEntities(text: String): String {
        var result = text
        val entities = listOf(
            "&quot;" to "\"", "&#039;" to "'", "&#39;" to "'", "&#x27;" to "'",
            "&lt;" to "<", "&gt;" to ">", "&#x2F;" to "/", "&#47;" to "/"
        )
        for ((entity, character) in entities) result = result.replace(entity, character)
        // Last, so "&amp;quot;" becomes "&quot;" and not a quote mark.
        return result.replace("&amp;", "&")
    }

    fun decodeBase64(text: String): String? = try {
        var normalized = text.replace('-', '+').replace('_', '/')
        while (normalized.length % 4 != 0) normalized += "="
        String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
