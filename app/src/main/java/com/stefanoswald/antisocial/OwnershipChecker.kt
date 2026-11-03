package com.stefanoswald.antisocial

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Works out whether a post is yours, and remembers the answer.
 *
 * The fast path is memory. The app remembers every post it has seen with your
 * name in the link, every post on your own Reels grid, and every post it has
 * checked before. Anything new is looked up once: the app fetches the post's
 * page and reads who the page says made it.
 *
 * Everything here runs on the main thread except the page fetches.
 */
class OwnershipChecker(context: Context) {

    /** Fired on the main thread when new posts are added to the list. */
    var onChange: (() -> Unit)? = null

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("own_posts", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)

    private val mineInOrder = ArrayList<String>()
    private val mine = HashSet<String>()
    private val others = HashMap<String, String>()
    private val waiting = HashMap<String, MutableList<(Ownership) -> Unit>>()
    private var closed = false

    init {
        val saved = prefs.getString(KEY_POSTS, "").orEmpty()
            .split("\n").filter { it.isNotEmpty() }
        mineInOrder.addAll(saved)
        mine.addAll(saved)
    }

    val knownCount: Int get() = mineInOrder.size

    /** The answer if the app already has one. */
    fun verdict(ref: PostRef): Ownership? = when {
        ref.key in mine -> Ownership.Mine
        others.containsKey(ref.key) -> Ownership.SomeoneElse(others.getValue(ref.key))
        else -> null
    }

    /** Remembers posts as yours, saving once for the whole batch. Returns the ones that were new. */
    fun markMine(keys: List<String>): List<String> {
        val added = mutableListOf<String>()
        for (key in keys) {
            if (key.isEmpty() || key in mine) continue
            mine.add(key)
            mineInOrder.add(key)
            others.remove(key)
            added.add(key)
        }
        if (added.isEmpty()) return added
        while (mineInOrder.size > MAX_REMEMBERED) {
            mine.remove(mineInOrder.removeAt(0))
        }
        prefs.edit().putString(KEY_POSTS, mineInOrder.joinToString("\n")).apply()
        onChange?.invoke()
        return added
    }

    /** The most recently learned posts, newest last. */
    fun recentKeys(limit: Int): List<String> = mineInOrder.takeLast(limit)

    /** Stops the lookup threads. Answers still on the way are dropped. */
    fun shutdown() {
        closed = true
        executor.shutdownNow()
        waiting.clear()
    }

    fun forgetAll() {
        mine.clear()
        mineInOrder.clear()
        others.clear()
        prefs.edit().remove(KEY_POSTS).apply()
        onChange?.invoke()
    }

    /**
     * Answers whether the post is yours, looking it up if needed. [done] runs
     * on the main thread. Two requests for the same post share one lookup.
     */
    fun check(ref: PostRef, identity: OwnIdentity, done: (Ownership) -> Unit) {
        verdict(ref)?.let { done(it); return }

        val queue = waiting[ref.key]
        if (queue != null) {
            queue.add(done)
            return
        }
        waiting[ref.key] = mutableListOf(done)

        val names = identity.names(ref.site)
        val cookies = cookieHeader(ref.lookupUrl)
        val started = runInBackground {
            val answer = lookUp(ref, names, cookies)
            mainHandler.post {
                if (closed) return@post
                when (answer) {
                    is Ownership.Mine -> markMine(listOf(ref.key))
                    is Ownership.SomeoneElse -> others[ref.key] = answer.owner
                    is Ownership.Unknown -> Unit
                }
                waiting.remove(ref.key)?.forEach { it(answer) }
            }
        }
        if (!started) {
            waiting.remove(ref.key)?.forEach { it(Ownership.Unknown) }
        }
    }

    /** Runs [work] on a lookup thread. False if the checker was shut down. */
    private fun runInBackground(work: () -> Unit): Boolean =
        try {
            if (closed) false else {
                executor.execute { work() }
                true
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            false
        }

    /**
     * Finds your Facebook username from your account number by looking at
     * your own profile page. [done] runs on the main thread.
     */
    fun facebookUsername(id: String, done: (String?) -> Unit) {
        if (!AccessPolicy.isDigits(id)) {
            done(null)
            return
        }
        val url = "https://www.facebook.com/profile.php?id=$id"
        val cookies = cookieHeader(url)
        val started = runInBackground {
            val deadline = System.currentTimeMillis() + LOOKUP_BUDGET_MS
            var found: String? = null
            for (cookie in attemptsFor(cookies)) {
                val page = fetch(url, cookie, deadline) ?: continue
                val candidates = listOfNotNull(page.finalUrl) + OwnershipParser.pageUrls(page.html)
                found = candidates.asSequence()
                    .mapNotNull { AccessPolicy.ownProfileName(it) }
                    .firstOrNull { !AccessPolicy.isDigits(it) }
                if (found != null) break
            }
            val answer = found
            mainHandler.post { if (!closed) done(answer) }
        }
        if (!started) done(null)
    }

    /** The cookies the web views hold for the post's site. */
    private fun cookieHeader(url: String): String? =
        try {
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

    private class Page(val html: String, val finalUrl: String?)

    /** No cookie first (a visitor), then your cookies if there are any. */
    private fun attemptsFor(cookies: String?): List<String?> =
        if (cookies != null) listOf(null, cookies) else listOf(null)

    /** First as a visitor, which answers cleanly for public posts. Then, if that says nothing, as you. */
    private fun lookUp(ref: PostRef, ownNames: Set<String>, cookies: String?): Ownership {
        // One budget for the whole lookup, so a slow network never leaves a
        // post covered for long.
        val deadline = System.currentTimeMillis() + LOOKUP_BUDGET_MS
        for (cookie in attemptsFor(cookies)) {
            val page = fetch(ref.lookupUrl, cookie, deadline) ?: continue
            val owners = OwnershipParser.owners(page.html, page.finalUrl, ref.site)
            val answer = OwnershipParser.verdict(owners, ownNames)
            if (answer !is Ownership.Unknown) return answer
        }
        return Ownership.Unknown
    }

    private fun fetch(address: String, cookie: String?, deadline: Long): Page? {
        val origin = URL(address)
        var url = origin
        // Your cookies go only to the exact host they were first sent to.
        var sendCookie = cookie
        var connection: HttpURLConnection? = null
        try {
            // Follow redirects by hand, so the cookie goes along only to the
            // same host and the final address is known.
            repeat(5) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val c = url.openConnection() as HttpURLConnection
                connection = c
                c.instanceFollowRedirects = false
                c.connectTimeout = minOf(left, 6000L).toInt()
                c.readTimeout = minOf(left, 6000L).toInt()
                c.setRequestProperty("User-Agent", AppSettings.DESKTOP_UA)
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                sendCookie?.let { c.setRequestProperty("Cookie", it) }
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: return null
                    val target = URL(url, next)
                    if (target.protocol != "https") return null
                    if (AccessPolicy.siteFor(target.toString()) != AccessPolicy.siteFor(address)) return null
                    if (!target.host.equals(origin.host, ignoreCase = true)) sendCookie = null
                    c.disconnect()
                    url = target
                    return@repeat
                }
                if (code !in 200..299) return null
                val bytes = c.inputStream.use { stream -> readUpTo(stream, MAX_BYTES, deadline) }
                return Page(String(bytes, Charsets.UTF_8), url.toString())
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readUpTo(stream: java.io.InputStream, limit: Int, deadline: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit && System.currentTimeMillis() < deadline) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private const val KEY_POSTS = "ownPostKeys"
        private const val MAX_REMEMBERED = 3000
        private const val MAX_BYTES = 4_000_000
        private const val LOOKUP_BUDGET_MS = 12_000L
    }
}
