package com.stefanoswald.antisocial

/**
 * JavaScript and CSS pushed into every page.
 *
 * Four jobs:
 * 1. Tell the app about in-page navigations. Facebook and Instagram are
 *    single-page apps, so tapping a link often changes the URL without a real
 *    page load. [android.webkit.WebViewClient.shouldOverrideUrlLoading] never
 *    sees those. The hook below reports every URL change so the policy can
 *    still bounce it. A post the app has not cleared yet is hidden on the
 *    spot, so someone else's Reel never shows while the app checks whose it is.
 * 2. Point out your own posts as they appear (links with your name in them,
 *    and everything on your own Reels grid), so they open instantly later.
 * 3. Work out which account is signed in, so the app knows which profile is
 *    yours without you typing it into Settings.
 * 4. Hide feed links, the Reels tab, and "open the app" nags so the pages
 *    stop baiting you. This is cosmetic. The URL policy is the real wall.
 */
object Injected {

    /** Name of the Java object the page posts to. */
    const val BRIDGE = "AntiSocialBridge"

    /**
     * Tells the hook whether posts you tap open, every name the sites use for
     * you, and the posts already known to be yours.
     */
    fun configScript(
        tapOpensPosts: Boolean,
        facebookNames: List<String>,
        instagramNames: List<String>,
        knownPosts: List<String>
    ): String {
        val settings = org.json.JSONObject()
            .put("tapOpens", tapOpensPosts)
            .put("fb", org.json.JSONArray(facebookNames))
            .put("ig", org.json.JSONArray(instagramNames))
        val known = org.json.JSONArray(knownPosts)
        return "window.__as_cfg = " + settings + ";\n" +
            "if (!window.__as_known) { window.__as_known = {}; }\n" +
            "(function (keys) { for (var i = 0; i < keys.length; i++) { window.__as_known[keys[i]] = 1; } })(" +
            known + ");"
    }

    /** A call that adds newly learned posts to the page that is already open. */
    fun addKnownScript(keys: List<String>): String =
        "window.__as_setKnown && window.__as_setKnown(" + org.json.JSONArray(keys) + ");"

    /** Tells the open page that the app opened these posts, so they stay visible. */
    fun openedScript(keys: List<String>): String =
        "window.__as_opened && window.__as_opened(" + org.json.JSONArray(keys) + ");"

    val navigationHook: String = """
    (function () {
        // Frames inside the page are not where you are. Only the page itself
        // reports its address.
        if (window.top !== window) { return; }
        if (window.__antiSocialHooked) { return; }
        window.__antiSocialHooked = true;

        function post(payload) {
            try { $BRIDGE.post(JSON.stringify(payload)); } catch (e) {}
        }

        // Set by the app: whether only your own posts may open, and every name
        // the sites might use for you. Read on every use, so updates count.
        function config() { return window.__as_cfg || {}; }
        if (!window.__as_known) { window.__as_known = {}; }
        window.__as_setKnown = function (keys) {
            for (var i = 0; i < keys.length; i++) { window.__as_known[keys[i]] = 1; }
        };

        // Hide the page and stop any video while the app decides about it.
        window.__as_hidden = false;
        function pauseMedia() {
            var media = document.querySelectorAll("video, audio");
            for (var i = 0; i < media.length; i++) {
                try { media[i].pause(); } catch (e) {}
            }
        }
        document.addEventListener("play", function (event) {
            var target = event.target;
            if (window.__as_hidden && target && typeof target.pause === "function") {
                try { target.pause(); } catch (e) {}
            }
        }, true);
        window.__as_hide = function () {
            window.__as_hidden = true;
            if (document.documentElement) {
                document.documentElement.style.setProperty("visibility", "hidden", "important");
            }
            pauseMedia();
        };
        window.__as_show = function () {
            window.__as_hidden = false;
            if (document.documentElement) {
                document.documentElement.style.removeProperty("visibility");
            }
        };

        function lower(text) { return (text || "").toLowerCase(); }
        function isDigits(text) { return /^\d+${'$'}/.test(text || ""); }
        function isCode(text) {
            return /^[A-Za-z0-9_-]{5,80}${'$'}/.test(text || "") &&
                !/^(audio|comments|liked_by|embed|create|explore)${'$'}/i.test(text);
        }
        function onFacebook(host) {
            host = lower(host);
            return host === "facebook.com" || /\.facebook\.com${'$'}/.test(host);
        }
        function onInstagram(host) {
            host = lower(host);
            return host === "instagram.com" || /\.instagram\.com${'$'}/.test(host);
        }
        function pathParts(url) {
            return url.pathname.split("/").filter(function (part) { return part.length > 0; });
        }
        var facebookRoutes = /^(reel|reels|watch|video|videos|video\.php|groups|profile\.php|photo|photo\.php|permalink\.php|story\.php|stories|story|messages|marketplace|notifications|me|share|n|nd|l\.php|media|plugins|sharer|sharer\.php|hashtag|search|events|pages|gaming|live|friends|saved|bookmarks|menu|professional_dashboard|content|settings|help|login|checkpoint)${'$'}/;
        var instagramRoutes = /^(p|reel|reels|tv|stories|explore|direct|accounts|create|notifications|api|graphql|web|about|legal|static|challenge|session|emails|linkshim|your_activity|settings|help|search|shopping|shop|locations|topics|guides|live|lite|threads)${'$'}/;

        // One post, Reel, video, or photo: its key and the owner the link names,
        // if any. Mirrors the app's own rules. Null for anything else.
        function postInfo(href) {
            var url;
            try { url = new URL(String(href), location.href); } catch (e) { return null; }
            var parts = pathParts(url);
            if (!parts.length) { return null; }
            var first = lower(parts[0]);
            var query = url.searchParams;
            if (onFacebook(url.hostname)) {
                if (first === "reel" && isDigits(parts[1])) {
                    return { key: "fb:video:" + parts[1], owner: null };
                }
                if ((first === "watch" || first === "video.php") && isDigits(query.get("v"))) {
                    return { key: "fb:video:" + query.get("v"), owner: null };
                }
                if ((first === "videos" || first === "video") && isDigits(parts[1])) {
                    return { key: "fb:video:" + parts[1], owner: null };
                }
                if ((first === "photo" || first === "photo.php") && isDigits(query.get("fbid"))) {
                    var setValue = query.get("set") || "";
                    var set = setValue.split(".");
                    var inGroup = !!query.get("idorvanity") || /^gm?\./.test(setValue);
                    return { key: "fb:photo:" + query.get("fbid"), owner: set[0] === "pb" && isDigits(set[1]) ? set[1] : null, inGroup: inGroup };
                }
                if ((first === "permalink.php" || first === "story.php") && query.get("story_fbid")) {
                    return { key: "fb:post:" + query.get("story_fbid"), owner: query.get("id") || null };
                }
                if (first === "groups" && parts.length >= 4 && /^(posts|permalink)${'$'}/i.test(parts[2])) {
                    return { key: "fb:group:" + lower(parts[1]) + ":" + parts[3], owner: null, inGroup: true };
                }
                if (first === "stories" && parts.length >= 2 && !/^(create|archive)${'$'}/i.test(parts[1])) {
                    return { key: "fb:story:" + lower(parts[1]), owner: lower(parts[1]), story: true };
                }
                if (parts.length >= 3 && !facebookRoutes.test(first)) {
                    var kind = lower(parts[1]);
                    if (kind === "posts" || kind === "permalink") {
                        return { key: "fb:post:" + parts[2], owner: first };
                    }
                    if (kind === "videos" || kind === "photos") {
                        for (var i = parts.length - 1; i >= 2; i--) {
                            if (isDigits(parts[i])) {
                                return { key: (kind === "videos" ? "fb:video:" : "fb:photo:") + parts[i], owner: first };
                            }
                        }
                    }
                }
                return null;
            }
            if (onInstagram(url.hostname)) {
                if (first === "stories" && parts.length >= 2) {
                    if (lower(parts[1]) === "highlights") {
                        return isDigits(parts[2]) ? { key: "ig:highlight:" + parts[2], owner: null, story: true } : null;
                    }
                    if (/^[A-Za-z0-9._]{1,30}${'$'}/.test(parts[1])) {
                        return { key: "ig:story:" + lower(parts[1]), owner: lower(parts[1]), story: true };
                    }
                    return null;
                }
                if (/^(p|reel|reels|tv)${'$'}/.test(first) && isCode(parts[1])) {
                    return { key: "ig:" + parts[1], owner: null };
                }
                if (parts.length >= 3 && /^(p|reel|tv)${'$'}/i.test(parts[1]) && isCode(parts[2]) && !instagramRoutes.test(first)) {
                    return { key: "ig:" + parts[2], owner: first };
                }
            }
            return null;
        }

        function ownNames() {
            var c = config();
            return (onInstagram(location.hostname) ? c.ig : c.fb) || [];
        }
        function isOwn(name) {
            if (!name) { return false; }
            name = lower(name);
            var names = ownNames();
            for (var i = 0; i < names.length; i++) {
                if (lower(names[i]) === name) { return true; }
            }
            return false;
        }

        // Posts you tapped. A post you tap opens; the next one does not open by
        // itself. Kept for a few seconds per tap, and for good once opened.
        var tapped = {};
        var opened = {};
        function wasTapped(key) {
            return !!(tapped[key] && Date.now() - tapped[key] < 10000);
        }
        // The app tells the page about posts it opened, so coming back to one
        // never covers the page again.
        window.__as_opened = function (keys) {
            for (var i = 0; i < keys.length; i++) { opened[keys[i]] = 1; }
        };

        // Every real tap is reported, so the app knows what you chose to open,
        // as opposed to what a player moved on to by itself. A tap on a link
        // also names the link. This listens on the window, ahead of the page's
        // own handlers, and ignores clicks the page makes up itself.
        window.addEventListener("click", function (event) {
            if (!event.isTrusted) { return; }
            var target = event.target;
            var anchor = target && target.closest ? target.closest("a[href]") : null;
            var href = anchor ? anchor.href : "";
            if (typeof href !== "string") { href = ""; }
            if (href) {
                var info = postInfo(href);
                if (info) { tapped[info.key] = Date.now(); }
            }
            post({ type: "tap", href: href });
        }, true);

        // A post the app has not cleared yet. It stays hidden until the app has
        // checked whose it is.
        function needsCheck(href) {
            var c = window.__as_cfg;
            if (!c) { return false; }
            var info = postInfo(href);
            if (!info || info.inGroup) { return false; }
            if (info.owner && isOwn(info.owner)) { return false; }
            if (window.__as_known[info.key] || (c.tapOpens && opened[info.key])) { return false; }
            if (c.tapOpens && wasTapped(info.key)) {
                opened[info.key] = 1;
                return false;
            }
            return true;
        }

        function report(why) {
            if (needsCheck(location.href)) { window.__as_hide(); }
            post({ type: "url", url: location.href, why: why });
        }

        function wrap(name) {
            var original = history[name];
            if (typeof original !== "function") { return; }
            history[name] = function () {
                var result = original.apply(this, arguments);
                try {
                    if (needsCheck(location.href)) { window.__as_hide(); }
                } catch (e) {}
                setTimeout(function () { report(name); }, 0);
                return result;
            };
        }

        wrap("pushState");
        wrap("replaceState");
        window.addEventListener("popstate", function () { report("popstate"); });
        window.addEventListener("hashchange", function () { report("hashchange"); });

        var last = location.href;
        setInterval(function () {
            if (location.href !== last) {
                last = location.href;
                report("poll");
            }
        }, 400);

        // The page's own first address counts too. Back and Reload can land on
        // a post without any link being tapped. Hide it right away if it needs
        // checking, then let the app decide.
        try {
            if (needsCheck(location.href)) { window.__as_hide(); }
        } catch (e) {}
        setTimeout(function () { report("load"); }, 0);
        window.addEventListener("pageshow", function (event) {
            if (event.persisted) { report("pageshow"); }
        });

        // Pages that list only your own posts: your Reels and Videos tabs on
        // Facebook, your grid on Instagram.
        function onOwnGrid() {
            var parts = pathParts(location);
            if (!parts.length) { return false; }
            if (onInstagram(location.hostname)) {
                return isOwn(parts[0]) && (parts.length === 1 || lower(parts[1]) === "reels");
            }
            if (lower(parts[0]) === "profile.php") {
                var query = new URLSearchParams(location.search);
                return isOwn(query.get("id")) && /^(reels|videos)/.test(lower(query.get("sk")));
            }
            return parts.length >= 2 && isOwn(parts[0]) && /^(reels|videos)${'$'}/.test(lower(parts[1]));
        }

        // Menus, pop-ups, and side panels can show other people's posts even on
        // your own grid page, so links in them never count as yours by location.
        var chrome = '[role="dialog"], [role="banner"], [role="navigation"], [role="complementary"], header, nav';

        // Tell the app about your own posts as they show up, so they open at
        // once later. The app double-checks every one.
        var sentKeys = {};
        function harvest() {
            if (!window.__as_cfg || !ownNames().length) { return; }
            var grid = onOwnGrid();
            var batch = [];
            var anchors = document.querySelectorAll("a[href]");
            for (var i = 0; i < anchors.length && batch.length < 150; i++) {
                var anchor = anchors[i];
                var href = anchor.href;
                if (typeof href !== "string") { continue; }
                var info = postInfo(href);
                if (!info || sentKeys[info.key] || window.__as_known[info.key]) { continue; }
                var namesYou = !!(info.owner && isOwn(info.owner));
                var onGrid = grid && !(anchor.closest && anchor.closest(chrome));
                if (namesYou || onGrid) {
                    sentKeys[info.key] = 1;
                    batch.push(href);
                }
            }
            if (batch.length) {
                post({ type: "links", page: location.href, hrefs: batch });
            }
        }
        setInterval(function () {
            try { harvest(); } catch (e) {}
        }, 2500);

        // Work out whose account is signed in, so the app knows which profile
        // is "yours" without you typing it into Settings. Reported once.
        function ownUsername() {
            var host = lower(location.hostname);
            if (host.indexOf("instagram") === -1) { return null; }
            var candidates = document.querySelectorAll('a[href^="/"]');
            for (var i = 0; i < candidates.length; i++) {
                var a = candidates[i];
                var label = (a.getAttribute("aria-label") || "").trim();
                var text = (a.innerText || "").trim();
                var isProfile = label === "Profile" || text === "Profile";
                if (!isProfile) {
                    // The nav avatar's alt text names its own owner.
                    var img = a.querySelector("img[alt]");
                    if (img && /'s profile picture${'$'}/i.test(img.getAttribute("alt") || "")) {
                        isProfile = a.closest("nav") !== null || a.getAttribute("role") === "link";
                    }
                }
                if (!isProfile) { continue; }
                var href = a.getAttribute("href") || "";
                var m = href.match(/^\/([A-Za-z0-9._]{1,30})\/?${'$'}/);
                if (m) { return m[1]; }
            }
            return null;
        }

        var reportedName = null;
        function reportOwner() {
            if (reportedName) { return; }
            var name = ownUsername();
            if (name) {
                reportedName = name;
                post({ type: "owner", site: "instagram", username: name });
            }
        }
        setTimeout(reportOwner, 1500);
        setTimeout(reportOwner, 5000);
        setInterval(reportOwner, 15000);
    })();
    """.trimIndent()

    /**
     * Links into feeds and the Reels player. Links to one Reel or one video
     * are left alone: those are judged by who made them, and comment alerts
     * on your own Reels point at exactly such links.
     */
    private val facebookCSS = """
    a[href="/"], a[href^="/?"], a[href="/home.php"], a[href^="/home.php?"],
    a[href="https://www.facebook.com/"], a[href^="https://www.facebook.com/?"],
    a[href^="https://www.facebook.com/home.php"], a[href="https://m.facebook.com/"],
    a[href^="https://m.facebook.com/?"], a[href^="https://m.facebook.com/home.php"],
    a[href="/reel"], a[href="/reel/"], a[href^="/reel/?"], a[href^="/reels"],
    a[href="/watch"], a[href="/watch/"], a[href^="/watch/?"]:not([href*="v="]),
    a[href^="/watch/live"], a[href^="/watch/topic"], a[href^="/watch/saved"],
    a[href="/video/"], a[href="/videos/"],
    a[href="/stories"], a[href="/stories/"], a[href^="/stories/?"],
    a[href^="/gaming"], a[href^="/games"], a[href^="/memories"], a[href^="/feeds"],
    a[href^="/saved"], a[href^="/bookmarks"], a[href^="/menu"], a[href^="/dating"],
    a[href="/groups/"], a[href="/groups"], a[href^="/groups/?"]:not([href*="category=create"]), a[href^="/groups/feed"],
    a[href^="/groups/discover"], a[href="/friends/"], a[href="/friends"], a[href^="/friends/?"],
    a[href="https://www.facebook.com/reel/"], a[href^="https://www.facebook.com/reel/?"],
    a[href^="https://www.facebook.com/reels"],
    a[href="https://www.facebook.com/watch/"], a[href^="https://www.facebook.com/watch/?"]:not([href*="v="]),
    a[href^="https://www.facebook.com/watch/live"],
    a[href="https://www.facebook.com/stories/"], a[href^="https://www.facebook.com/stories/?"],
    a[href^="https://www.facebook.com/gaming"], a[href^="https://www.facebook.com/memories"],
    a[href^="https://www.facebook.com/feeds"], a[href^="https://www.facebook.com/saved"],
    a[href^="https://www.facebook.com/bookmarks"], a[href^="https://www.facebook.com/menu"],
    a[href="https://www.facebook.com/groups/"], a[href^="https://www.facebook.com/groups/?"]:not([href*="category=create"]),
    a[href^="https://www.facebook.com/groups/feed"], a[href^="https://www.facebook.com/groups/discover"],
    a[href="https://www.facebook.com/friends/"], a[href^="https://www.facebook.com/friends/?"],
    a[href="https://m.facebook.com/reel/"], a[href^="https://m.facebook.com/reels"],
    a[href="https://m.facebook.com/watch/"], a[href^="https://m.facebook.com/watch/?"]:not([href*="v="]),
    a[href="https://m.facebook.com/stories/"],
    a[href^="https://m.facebook.com/menu"], a[href^="https://m.facebook.com/bookmarks"],
    a[aria-label="Home"], a[aria-label="Video"], a[aria-label="Watch"],
    a[aria-label="Stories"], a[aria-label="Gaming"], a[aria-label="Feeds"],
    [role="banner"] [aria-label="Home"], [role="banner"] [aria-label="Video"],
    [role="banner"] [aria-label="Reels"], [role="banner"] [aria-label="Watch"],
    [role="banner"] [aria-label="Gaming"], [role="navigation"] [aria-label="Reels"],
    [role="navigation"] [aria-label="Video"], [role="navigation"] [aria-label="Watch"],
    [role="navigation"] [aria-label="Feeds"], [role="navigation"] [aria-label="Gaming"],
    [data-pagelet^="FeedUnit"], [data-pagelet="Stories"], [data-pagelet="VideoChatHomeRoot"],
    [data-pagelet="MegaphoneUnit"],
    a[href^="fb://"], a[href^="intent://"], a[href*="play.google.com"] {
        display: none !important;
    }
    """.trimIndent()

    private val instagramCSS = """
    a[href="/"], a[href^="/?"], a[href="/explore/"], a[href^="/explore"],
    a[href="/reels/"], a[href^="/reels"], a[href="/stories/"], a[href="/stories"],
    a[href="https://www.instagram.com/"], a[href^="https://www.instagram.com/?"],
    a[href^="https://www.instagram.com/explore"], a[href^="https://www.instagram.com/reels"],
    a[href="https://www.instagram.com/stories/"],
    a[aria-label="Home"], a[aria-label="Explore"],
    [role="navigation"] [aria-label="Home"], [role="navigation"] [aria-label="Explore"],
    [role="navigation"] [aria-label="Reels"],
    a[href^="instagram://"], a[href^="intent://"], a[href*="play.google.com"] {
        display: none !important;
    }
    """.trimIndent()

    /**
     * Adds the CSS as early as possible and keeps it there. Also hides
     * "open the app" buttons, which have no stable selector, by their text.
     * One web view can move between facebook.com and instagram.com, so the
     * script picks the CSS by hostname at run time.
     */
    fun hidingScript(): String {
        val fb = escapeForTemplate(facebookCSS)
        val ig = escapeForTemplate(instagramCSS)
        return """
        (function () {
            if (window.__asHiding) { return; }
            window.__asHiding = true;
            var host = (location.hostname || "").toLowerCase();
            var css = "";
            if (host.indexOf("instagram") !== -1) {
                css = `$ig`;
            } else if (host.indexOf("facebook") !== -1 || host.indexOf("messenger") !== -1) {
                css = `$fb`;
            }
            if (!css) { return; }

            function ensureStyle() {
                if (document.getElementById("__as_style")) { return; }
                var style = document.createElement("style");
                style.id = "__as_style";
                style.textContent = css;
                (document.head || document.documentElement).appendChild(style);
            }

            var nagPattern = /^(use the app|open app|open in app|open the app|get app|get the app|switch to the app|open messenger|get messenger|open facebook|open instagram|download messenger|download the app|continue in the app|view in app)${'$'}/i;

            function hideNags() {
                var nodes = document.querySelectorAll("button, a, [role='button']");
                for (var i = 0; i < nodes.length; i++) {
                    var node = nodes[i];
                    if (node.__asChecked) { continue; }
                    node.__asChecked = true;
                    var text = (node.innerText || node.textContent || "").trim();
                    if (text.length > 0 && text.length < 30 && nagPattern.test(text)) {
                        node.style.setProperty("display", "none", "important");
                    }
                }
            }

            ensureStyle();
            hideNags();

            var pending = false;
            var observer = new MutationObserver(function () {
                if (pending) { return; }
                pending = true;
                setTimeout(function () {
                    pending = false;
                    ensureStyle();
                    hideNags();
                }, 250);
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });
        })();
        """.trimIndent()
    }

    /**
     * The desktop site has no viewport meta tag, so the WebView lays it out
     * wide and shrinks it to fit, which is unreadable on a phone. This pins the
     * layout width to something narrower so the page scales up. Facebook keeps
     * a workable layout down to roughly 600 px.
     */
    fun viewportScript(width: Int): String = """
        (function () {
            var width = $width;
            if (!width) { return; }
            if (window.__asViewport) { return; }
            window.__asViewport = true;

            function apply() {
                var metas = document.querySelectorAll('meta[name="viewport"]');
                for (var i = 0; i < metas.length; i++) {
                    if (metas[i].id !== "__as_viewport") {
                        metas[i].parentNode.removeChild(metas[i]);
                    }
                }
                if (!document.getElementById("__as_viewport")) {
                    var meta = document.createElement("meta");
                    meta.id = "__as_viewport";
                    meta.name = "viewport";
                    meta.content = "width=" + width + ", minimum-scale=0.25, maximum-scale=5";
                    (document.head || document.documentElement).appendChild(meta);
                }
            }

            apply();
            var observer = new MutationObserver(function (mutations) {
                for (var m = 0; m < mutations.length; m++) {
                    var added = mutations[m].addedNodes;
                    for (var n = 0; n < added.length; n++) {
                        var node = added[n];
                        if (node.nodeType === 1 && node.tagName === "META" &&
                            node.getAttribute("name") === "viewport" && node.id !== "__as_viewport") {
                            apply();
                            return;
                        }
                    }
                }
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });
        })();
    """.trimIndent()

    /**
     * Describes the links and labels on the page. The Diagnostics screen shows
     * this so you can send it over when something slips through.
     */
    val pageMap: String = """
    (function () {
        var out = { url: location.href, title: document.title, links: [], labels: [] };
        var seen = {};
        var anchors = document.querySelectorAll("a[href]");
        for (var i = 0; i < anchors.length; i++) {
            var a = anchors[i];
            var href = (a.getAttribute("href") || "").split("?")[0].slice(0, 80);
            var label = a.getAttribute("aria-label") || "";
            var key = href + (label ? " [" + label + "]" : "");
            if (seen[key]) { continue; }
            seen[key] = true;
            out.links.push(key);
        }
        var sel = "[role='navigation'] [aria-label], [role='tablist'] [aria-label], nav [aria-label], [role='banner'] [aria-label]";
        var labelled = document.querySelectorAll(sel);
        for (var j = 0; j < labelled.length; j++) {
            var l = labelled[j].getAttribute("aria-label");
            if (l && out.labels.indexOf(l) === -1) { out.labels.push(l); }
        }
        out.links = out.links.slice(0, 200);
        out.labels = out.labels.slice(0, 100);
        return JSON.stringify(out, null, 2);
    })();
    """.trimIndent()

    private fun escapeForTemplate(text: String): String =
        text.replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("${'$'}", "\\${'$'}")
}
