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

    /**
     * Opens Facebook's or Instagram's own composer. Each step is one button
     * for the script to find: "label" (an aria-label to click), "startsWith"
     * (button text to click), or "svg" (an icon's label) with "glow" set to
     * make that button glow instead of clicking it. "name" comes back in the
     * "compose" message when a step is not found.
     */
    fun composeScript(steps: org.json.JSONArray): String = composeTemplate.replace("__STEPS__", steps.toString())

    private val composeTemplate: String = """
    (function (steps) {
        // Opens Facebook's or Instagram's own composer for you. Each step finds
        // one button on the page: it either clicks it (Facebook's post box and
        // Reel button open a dialog), or makes it glow so you can tap it yourself
        // (Instagram's + button opens your photo picker, which needs a real tap).
        if (window.__asComposing) { return; }
        window.__asComposing = true;

        function post(payload) {
            try { $BRIDGE.post(JSON.stringify(payload)); } catch (e) {}
        }

        function textOf(el) {
            return (el.innerText || el.textContent || "").replace(/’/g, "'").replace(/\s+/g, " ").trim();
        }

        function shown(el) {
            if (!el || (el.closest && el.closest('[aria-hidden="true"]'))) { return false; }
            var box = el.getBoundingClientRect();
            return box.width > 0 && box.height > 0;
        }

        function find(step) {
            if (step.svg) {
                var icons = document.querySelectorAll('svg[aria-label="' + step.svg + '"]');
                for (var i = 0; i < icons.length; i++) {
                    var host = icons[i].closest('a, button, [role="button"], [role="link"]');
                    if (host && shown(host)) { return host; }
                }
                return null;
            }
            var nodes = document.querySelectorAll('[role="button"], button, a, [role="link"], [role="menuitem"]');
            for (var n = 0; n < nodes.length; n++) {
                var el = nodes[n];
                var matched = false;
                if (step.label) {
                    matched = el.getAttribute("aria-label") === step.label;
                } else if (step.startsWith) {
                    var text = textOf(el);
                    matched = text.indexOf(step.startsWith) === 0 && text.length < 80;
                }
                if (matched && shown(el)) { return el; }
            }
            return null;
        }

        function glow(el) {
            if (!document.getElementById("__as_glow_style")) {
                var style = document.createElement("style");
                style.id = "__as_glow_style";
                style.textContent =
                    "@keyframes __asPulse { 0%, 100% { box-shadow: 0 0 0 3px rgba(255, 122, 0, 0.95); }" +
                    " 50% { box-shadow: 0 0 0 10px rgba(255, 122, 0, 0.3); } }" +
                    ".__as_glow { animation: __asPulse 1s ease-in-out infinite !important; border-radius: 12px !important; }";
                (document.head || document.documentElement).appendChild(style);
            }
            el.classList.add("__as_glow");
            var stop = function () { el.classList.remove("__as_glow"); };
            el.addEventListener("click", stop, { once: true, capture: true });
            setTimeout(stop, 15000);
        }

        function dialogs() { return document.querySelectorAll('[role="dialog"]').length; }

        var startPath = location.pathname;
        var index = 0;
        var started = Date.now();

        function finish(ok, missing) {
            post({ type: "compose", ok: ok, missing: missing || "" });
        }

        function tick() {
            if (index >= steps.length) { finish(true); return; }
            var step = steps[index];
            var el = find(step);
            if (!el) {
                if (Date.now() - started > (step.wait || 15000)) { finish(false, step.name); return; }
                setTimeout(tick, 300);
                return;
            }
            try { el.scrollIntoView({ block: "center" }); } catch (e) {}
            if (step.glow) {
                glow(el);
                index++;
                started = Date.now();
                setTimeout(tick, 0);
                return;
            }
            var before = dialogs();
            el.click();
            var clickedAt = Date.now();
            var lastClick = clickedAt;
            (function waitForComposer() {
                if (dialogs() > before || location.pathname !== startPath) {
                    index++;
                    started = Date.now();
                    setTimeout(tick, 400);
                    return;
                }
                var now = Date.now();
                if (now - clickedAt > (step.dialogWait || 8000)) { finish(false, step.name); return; }
                // The page may not have been ready for the first click. Try again.
                if (now - lastClick > (step.retry || 2500)) {
                    var again = find(step);
                    if (again) { again.click(); }
                    lastClick = now;
                }
                setTimeout(waitForComposer, 200);
            })();
        }

        tick();
    })(__STEPS__);
    """.trimIndent()

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

        // Set by the app: whether posts you tap may open, and every name the
        // sites might use for you. Read on every use, so updates count.
        function config() { return window.__as_cfg || {}; }
        if (!window.__as_known) { window.__as_known = {}; }
        window.__as_setKnown = function (keys) {
            for (var i = 0; i < keys.length; i++) { window.__as_known[keys[i]] = 1; }
            try { updateFence(); } catch (e) {}
        };

        // A short record of what happened on this page. The Diagnostics page map
        // shows it, so a glitch can be traced after the fact.
        var events = [];
        window.__as_events = events;
        function two(n) { return (n < 10 ? "0" : "") + n; }
        function note(kind, detail) {
            var d = new Date();
            events.push(two(d.getHours()) + ":" + two(d.getMinutes()) + ":" + two(d.getSeconds()) + " " + kind + (detail ? " " + detail : ""));
            if (events.length > 40) { events.shift(); }
        }
        window.__as_note = note;

        // Only real taps and touches count. Clicks the page makes up itself do not.
        function isReal(event) {
            if (!event.isTrusted) { return; }
            return true;
        }

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
        function has(map, key) { return Object.prototype.hasOwnProperty.call(map, key); }

        // First path segments that are parts of the site, never a person. Built
        // from the app's own rules, so the page and the app always agree.
        var facebookRoutes = {"a": 1, "accountscenter": 1, "ajax": 1, "api": 1, "async": 1, "auth": 1, "bookmarks": 1, "checkpoint": 1, "comment": 1, "confirmemail.php": 1, "consent": 1, "content": 1, "cookie": 1, "dating": 1, "device-based": 1, "dialog": 1, "events": 1, "explore": 1, "feeds": 1, "friends": 1, "fundraisers": 1, "games": 1, "gaming": 1, "groups": 1, "hashtag": 1, "help": 1, "home.php": 1, "images": 1, "jobs": 1, "l.php": 1, "language": 1, "legal": 1, "live": 1, "login": 1, "login.php": 1, "logout": 1, "logout.php": 1, "marketplace": 1, "me": 1, "media": 1, "memories": 1, "menu": 1, "messages": 1, "messages.php": 1, "mobile": 1, "n": 1, "nd": 1, "news": 1, "notifications": 1, "notifications.php": 1, "oauth": 1, "offers": 1, "pages": 1, "people": 1, "permalink.php": 1, "photo": 1, "photo.php": 1, "places": 1, "plugins": 1, "policies": 1, "policy": 1, "privacy": 1, "professional_dashboard": 1, "profile.php": 1, "public": 1, "recover": 1, "recover.php": 1, "reel": 1, "reels": 1, "rsrc.php": 1, "saved": 1, "search": 1, "security": 1, "settings": 1, "share": 1, "sharer": 1, "sharer.php": 1, "si": 1, "stories": 1, "story": 1, "story.php": 1, "terms": 1, "topic": 1, "two_factor": 1, "two_step_verification": 1, "ufi": 1, "video": 1, "video.php": 1, "videos": 1, "watch": 1, "weather": 1, "x": 1};
        var instagramRoutes = {"about": 1, "accounts": 1, "ajax": 1, "api": 1, "auth_platform": 1, "challenge": 1, "coming_soon": 1, "consent": 1, "create": 1, "data": 1, "direct": 1, "emails": 1, "explore": 1, "graphql": 1, "guides": 1, "help": 1, "legal": 1, "linkshim": 1, "lite": 1, "live": 1, "locations": 1, "logging": 1, "login": 1, "logout": 1, "notifications": 1, "oauth": 1, "p": 1, "privacy": 1, "push": 1, "qp": 1, "reel": 1, "reels": 1, "search": 1, "session": 1, "settings": 1, "shop": 1, "shopping": 1, "static": 1, "stories": 1, "terms": 1, "threads": 1, "topics": 1, "tv": 1, "two_factor": 1, "web": 1, "your_activity": 1};

        // One post, Reel, video, photo, story, or someone's profile: its key and
        // the owner the link names, if any. Mirrors the app's own rules. Null for
        // anything else.
        function postInfo(href) {
            var url;
            try { url = new URL(String(href), location.href); } catch (e) { return null; }
            var parts = pathParts(url);
            if (!parts.length) { return null; }
            var first = lower(parts[0]);
            var query = url.searchParams;
            if (onFacebook(url.hostname)) {
                if (first === "reel" && isDigits(parts[1])) {
                    return { key: "fb:video:" + parts[1], owner: null, video: true };
                }
                if ((first === "watch" || first === "video.php") && isDigits(query.get("v"))) {
                    return { key: "fb:video:" + query.get("v"), owner: null, video: true };
                }
                if ((first === "videos" || first === "video") && isDigits(parts[1])) {
                    return { key: "fb:video:" + parts[1], owner: null, video: true };
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
                if (first === "profile.php") {
                    var id = query.get("id");
                    return isDigits(id) ? { key: "fb:profile:" + id, owner: id, profile: true } : null;
                }
                if (has(facebookRoutes, first)) { return null; }
                if (parts.length >= 3) {
                    var kind = lower(parts[1]);
                    if (kind === "posts" || kind === "permalink") {
                        return { key: "fb:post:" + parts[2], owner: first };
                    }
                    if (kind === "videos" || kind === "photos") {
                        for (var i = parts.length - 1; i >= 2; i--) {
                            if (isDigits(parts[i])) {
                                return kind === "videos"
                                    ? { key: "fb:video:" + parts[i], owner: first, video: true }
                                    : { key: "fb:photo:" + parts[i], owner: first };
                            }
                        }
                    }
                }
                if (/^[a-z0-9.-]{1,100}${'$'}/.test(first)) {
                    return { key: "fb:profile:" + first, owner: first, profile: true };
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
                    return { key: "ig:" + parts[1], owner: null, video: first === "reel" || first === "reels" };
                }
                if (parts.length >= 3 && /^(p|reel|tv)${'$'}/i.test(parts[1]) && isCode(parts[2]) && !has(instagramRoutes, first)) {
                    return { key: "ig:" + parts[2], owner: first, video: lower(parts[1]) === "reel" };
                }
                // instagram.com/create/ alone is a person. Deeper paths are the
                // post composer.
                if (first === "create" ? parts.length >= 2 : has(instagramRoutes, first)) { return null; }
                if (/^[a-z0-9._]{1,30}${'$'}/.test(first)) {
                    return { key: "ig:profile:" + first, owner: first, profile: true };
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
        // Yours: a link that names you, or a post the app knows is yours.
        function isMine(info) {
            return !!(info && ((info.owner && isOwn(info.owner)) || window.__as_known[info.key]));
        }

        // The page you are on, judged like a link.
        var hereHref = null;
        var hereInfo = null;
        function here() {
            if (location.href !== hereHref) {
                hereHref = location.href;
                hereInfo = postInfo(hereHref);
            }
            return hereInfo;
        }
        function isOtherVideo(from, key) {
            var before = from ? postInfo(from) : null;
            return !!(before && before.video && before.key !== key);
        }

        // Posts you tapped. A post you tap opens; the next one does not open by
        // itself. Kept for a few seconds per tap, and for good once opened.
        var tapped = {};
        var opened = {};
        function wasTapped(key) {
            return !!(tapped[key] && Date.now() - tapped[key] < 10000);
        }
        // Facebook can change a profile's address right after it opens: the
        // person's number becomes their name. That is still the profile you
        // tapped, so the name opens too, for a few seconds.
        var numberProfile = { key: "", at: 0 };
        function openedProfile(key) {
            if (/^fb:profile:\d+${'$'}/.test(key)) { numberProfile = { key: key, at: Date.now() }; }
        }
        function renamedProfile(key) {
            return /^fb:profile:/.test(key) && !/^fb:profile:\d+${'$'}/.test(key) &&
                numberProfile.key !== "" && Date.now() - numberProfile.at < 5000;
        }
        // The app tells the page about posts it opened, so coming back to one
        // never covers the page again.
        window.__as_opened = function (keys) {
            for (var i = 0; i < keys.length; i++) {
                opened[keys[i]] = 1;
                openedProfile(keys[i]);
            }
        };

        // Every real tap is reported, so the app knows what you chose to open,
        // as opposed to what a player moved on to by itself. A tap on a link
        // also names the link. This listens on the window, ahead of the page's
        // own handlers, and ignores clicks the page makes up itself.
        var lastTapAt = 0;
        var lastTapPoint = null;
        window.addEventListener("click", function (event) {
            if (!isReal(event)) { return; }
            lastTapAt = Date.now();
            lastTapPoint = { x: event.clientX, y: event.clientY, at: lastTapAt };
            var target = event.target;
            var anchor = target && target.closest ? target.closest("a[href]") : null;
            var href = anchor ? anchor.href : "";
            if (typeof href !== "string") { href = ""; }
            if (href) {
                var info = postInfo(href);
                if (info) { tapped[info.key] = Date.now(); }
            }
            tapForVideo(target);
            post({ type: "tap", href: href });
        }, true);

        // A post the app has not cleared yet. It stays hidden until the app has
        // checked whose it is. One video at a time: a tap on another video from
        // a video page does not count, so that one waits for the app too.
        function needsCheck(href, from, why) {
            var c = window.__as_cfg;
            if (!c) { return false; }
            var info = postInfo(href);
            if (!info || info.inGroup) { return false; }
            if (info.owner && isOwn(info.owner)) { return false; }
            if (window.__as_known[info.key] || (c.tapOpens && opened[info.key])) { return false; }
            if (c.tapOpens && wasTapped(info.key)) {
                if (info.video && why !== "popstate" && isOtherVideo(from, info.key)) { return true; }
                opened[info.key] = 1;
                openedProfile(info.key);
                return false;
            }
            if (c.tapOpens && renamedProfile(info.key)) {
                opened[info.key] = 1;
                return false;
            }
            return true;
        }

        var lastReported = "";
        var lastUrlAt = Date.now();
        function samePage(a, b) {
            if (!a || !b) { return false; }
            var x = postInfo(a);
            var y = postInfo(b);
            if (x || y) { return !!(x && y && x.key === y.key); }
            try { return new URL(a).pathname === new URL(b).pathname; } catch (e) { return false; }
        }
        function samePath(a, b) {
            try { return new URL(a).pathname === new URL(b).pathname; } catch (e) { return false; }
        }
        function sameSearch(a, b) {
            try { return new URL(a).search === new URL(b).search; } catch (e) { return false; }
        }
        // When the address last changed in a way that means new results: a new
        // page, a new search, or a new filter.
        var lastNewResultsAt = 0;
        var startedAt = Date.now();
        function pageChanged(from, href, why) {
            lastUrlAt = Date.now();
            if (from && (!samePath(from, href) || !sameSearch(from, href))) { lastNewResultsAt = lastUrlAt; }
            // A new page starts over.
            if (!samePath(from, href)) {
                scrollState = { y: 0, at: 0 };
                upRun = null;
            }
            var parts = pathParts(location);
            note("page", why + " /" + parts.slice(0, 3).join("/"));
            if (!samePage(from, href)) { resetVideo("new page"); }
            updateFence();
        }

        function report(why) {
            var from = lastReported;
            var href = location.href;
            lastReported = href;
            last = href;
            if (needsCheck(href, from, why)) { window.__as_hide(); }
            post({ type: "url", url: href, why: why });
            if (href !== from) { pageChanged(from, href, why); }
        }

        function wrap(name) {
            var original = history[name];
            if (typeof original !== "function") { return; }
            history[name] = function () {
                var before = location.href;
                var result = original.apply(this, arguments);
                try {
                    if (location.href !== before) { lastUrlAt = Date.now(); }
                    if (needsCheck(location.href, before, name)) { window.__as_hide(); }
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
            try { updateFence(); } catch (e) {}
        }, 400);

        // The page's own first address counts too. Back and Reload can land on
        // a post without any link being tapped. Hide it right away if it needs
        // checking, then let the app decide.
        try {
            if (needsCheck(location.href, "", "load")) { window.__as_hide(); }
        } catch (e) {}
        setTimeout(function () { report("load"); }, 0);
        window.addEventListener("pageshow", function (event) {
            if (event.persisted) { report("pageshow"); }
        });

        // ---------------------------------------------------------------- fence

        // Someone else's profile or video shows only its top part: about a
        // screen and a half. That is enough to see who they are, or to watch the
        // one video, and the page cannot scroll on into an endless list.
        var fenceText = "Anti-Social stops here. Tap Back to leave this page.";
        var fenceOn = false;
        function fenceLimit() {
            return Math.max(1800, Math.round((window.innerHeight || 900) * 1.6));
        }
        function fenceWanted() {
            if (!window.__as_cfg) { return false; }
            var info = here();
            if (!info || info.inGroup || isMine(info)) { return false; }
            return !!(info.profile || info.video);
        }
        function updateFence() {
            var want = fenceWanted();
            var style = document.getElementById("__as_fence");
            if (!want) {
                if (style && style.parentNode) { style.parentNode.removeChild(style); }
                if (fenceOn) { note("fence", "off"); }
                fenceOn = false;
                return;
            }
            var css = "html { overflow-y: auto !important; }" +
                " body { max-height: " + fenceLimit() + "px !important; overflow: hidden !important; overflow: clip !important; }" +
                " html::after { content: \"" + fenceText + "\"; display: block; box-sizing: border-box; width: 100%;" +
                " padding: 28px 20px 160px; text-align: center; color: #8a8d91; font-weight: 600; line-height: 1.4;" +
                " font-family: -apple-system, system-ui, sans-serif; font-size: 16px; font-size: max(16px, 3vw); }";
            if (!style) {
                style = document.createElement("style");
                style.id = "__as_fence";
                (document.head || document.documentElement).appendChild(style);
            }
            if (style.textContent !== css) { style.textContent = css; }
            if (!fenceOn) { note("fence", "on"); }
            fenceOn = true;
        }
        updateFence();

        // A backup for the fence, for pages that scroll a box of their own.
        function fenceBox(box) {
            if (!fenceOn || !box || typeof box.scrollTop !== "number") { return; }
            // A pop-up with no video in it (comments, a chat) scrolls freely.
            var popup = box.closest ? box.closest('[role="dialog"]') : null;
            if (popup && !popup.querySelector("video")) { return; }
            var limit = fenceLimit();
            if (box.scrollTop > limit) { box.scrollTop = limit; }
        }

        // ---------------------------------------------------------------- video

        // One video at a time. The first video you play on a page plays. If
        // another one starts by itself (the next one in a viewer, or the player
        // moving on after the first one ended), it is stopped and the app tells
        // you. Opening another page, or tapping outside a viewer on a page that
        // is not a video, lets the next video you pick play.
        var gate = { video: null, src: "", ended: false, length: 0 };
        var lastVideoNotice = 0;
        function resetVideo(why) {
            if (gate.video) { note("video", "reset, " + why); }
            gate.video = null;
            gate.src = "";
            gate.ended = false;
            gate.length = 0;
        }
        function sourceOf(video) { return String(video.currentSrc || video.src || ""); }
        function lengthOf(video) {
            var d = video.duration;
            return (typeof d === "number" && isFinite(d) && d > 0) ? d : 0;
        }
        // Animated stickers and GIFs are short silent loops, and tiny previews
        // are not watched. Neither counts as a video.
        function notAVideo(video) {
            if (video.loop && video.muted) { return true; }
            var box = video.getBoundingClientRect();
            return box.width < 120 && box.height < 120;
        }
        // A tap that landed on this video in the last moment or two: you picked it.
        function tappedOn(video) {
            if (!lastTapPoint || Date.now() - lastTapPoint.at > 1500) { return false; }
            var box = video.getBoundingClientRect();
            return lastTapPoint.x >= box.left - 8 && lastTapPoint.x <= box.left + box.width + 8 &&
                lastTapPoint.y >= box.top - 8 && lastTapPoint.y <= box.top + box.height + 8;
        }
        function tapForVideo(target) {
            var info = here();
            if (info && info.video) { return; }
            var inViewer = target && target.closest ? target.closest('[role="dialog"]') : null;
            if (!inViewer) { resetVideo("tap"); }
        }
        function videoAllowed(video) {
            if (!gate.video || tappedOn(video)) { return true; }
            if (video === gate.video) {
                var src = sourceOf(video);
                if (!gate.src || !src || src === gate.src) { return true; }
                // The same player with a new video in it. Fine while the first
                // one plays (a sharper copy, say). After it ended, the player is
                // moving on to the next one.
                return !gate.ended;
            }
            // Another player with the same video in it, like a bigger player.
            var length = lengthOf(video);
            return !gate.ended && length > 0 && gate.length > 0 && Math.abs(length - gate.length) < 0.75;
        }
        function onVideoPlay(event) {
            var video = event.target;
            if (!video || video.tagName !== "VIDEO" || window.__as_hidden) { return; }
            if (!window.__as_cfg || isMine(here()) || notAVideo(video)) { return; }
            if (videoAllowed(video)) {
                if (gate.video !== video) { note("video", "plays"); }
                if (gate.video !== video || sourceOf(video) !== gate.src) { gate.ended = false; }
                gate.video = video;
                var src = sourceOf(video);
                if (src) { gate.src = src; }
                var length = lengthOf(video);
                if (length) { gate.length = length; }
                return;
            }
            try { video.pause(); } catch (e) {}
            note("video", "stopped a second one");
            var now = Date.now();
            if (now - lastVideoNotice > 4000) {
                lastVideoNotice = now;
                post({ type: "video-blocked", url: location.href });
            }
        }
        document.addEventListener("play", onVideoPlay, true);
        document.addEventListener("playing", onVideoPlay, true);
        document.addEventListener("ended", function (event) {
            if (event.target === gate.video) {
                gate.ended = true;
                note("video", "ended");
            }
        }, true);
        document.addEventListener("durationchange", function (event) {
            if (event.target === gate.video) {
                var length = lengthOf(gate.video);
                if (length) { gate.length = length; }
            }
        }, true);

        // ---------------------------------------------------------- Marketplace

        // Marketplace jumped back to the top of the results while you scrolled.
        // When the page moves up by itself, it is put back where you were, and
        // the app writes the jump into Diagnostics with what led up to it.
        function onMarketplace() {
            return onFacebook(location.hostname) && /^\/marketplace(\/|${'$'})/i.test(location.pathname);
        }
        function pageY() {
            var root = document.scrollingElement || document.documentElement;
            return Math.max(window.pageYOffset || 0, root ? root.scrollTop : 0);
        }
        function pageHeight() {
            var root = document.scrollingElement || document.documentElement;
            return root ? root.scrollHeight : 0;
        }
        function describe(el) {
            if (!el || !el.tagName || el === document.body || el === document.documentElement) { return "none"; }
            var text = lower(el.tagName);
            var type = el.getAttribute("type");
            var label = el.getAttribute("aria-label") || el.getAttribute("placeholder") || el.getAttribute("name");
            if (type) { text += "[" + type + "]"; }
            if (label) { text += " '" + String(label).slice(0, 40) + "'"; }
            return text;
        }
        function isSearchBox(el) {
            if (!el || !el.tagName) { return false; }
            var role = lower(el.getAttribute("role"));
            var label = (el.getAttribute("aria-label") || "") + " " + (el.getAttribute("placeholder") || "");
            return lower(el.type) === "search" || role === "searchbox" || role === "combobox" || /search/i.test(label);
        }
        function isTyping(el) {
            if (!el || !el.tagName) { return false; }
            if (el.tagName === "TEXTAREA" || el.isContentEditable === true) { return true; }
            return el.tagName === "INPUT" && !/^(checkbox|radio|button|submit|reset|range|file|color|image|hidden)${'$'}/i.test(el.type || "");
        }

        // Where your finger is, and which way it last moved. Pulling the page
        // down with your finger is you scrolling up.
        var touching = false;
        var touchY = 0;
        var touchStartY = 0;
        var touchStartAt = 0;
        var lastTouchAt = 0;
        var lastTouchEnd = 0;
        var fingerDir = "";
        var blurredThisTouch = false;
        function youScrollUp(now) {
            return fingerDir === "down" && (touching || now - lastTouchEnd < 3000);
        }

        var lastScrollCall = null;
        var restoring = false;
        // Notes which code moved the page last, so a jump can be traced.
        function watchCall(owner, name) {
            var original = owner && owner[name];
            if (typeof original !== "function") { return; }
            owner[name] = function () {
                if (!restoring && onMarketplace()) {
                    lastScrollCall = { what: name + " on " + (this === window || !this ? "page" : describe(this)), at: Date.now() };
                }
                return original.apply(this, arguments);
            };
        }
        watchCall(window, "scrollTo");
        watchCall(window, "scroll");
        watchCall(window, "scrollBy");
        if (window.Element) {
            watchCall(Element.prototype, "scrollIntoView");
            watchCall(Element.prototype, "scrollTo");
            watchCall(Element.prototype, "scroll");
        }
        // A box the page focuses by itself on Marketplace never scrolls the page.
        if (window.HTMLElement && typeof HTMLElement.prototype.focus === "function") {
            var originalFocus = HTMLElement.prototype.focus;
            HTMLElement.prototype.focus = function (options) {
                if (onMarketplace() && Date.now() - Math.max(lastTapAt, lastTouchAt) > 700) {
                    lastScrollCall = { what: "focus on " + describe(this), at: Date.now() };
                    var settings = {};
                    if (options && typeof options === "object") {
                        for (var k in options) { settings[k] = options[k]; }
                    }
                    settings.preventScroll = true;
                    return originalFocus.call(this, settings);
                }
                return originalFocus.apply(this, arguments);
            };
        }

        // Scrolling with a search box still focused takes the focus away, which
        // also puts the keyboard away. Phones scroll back up to a box that has
        // the focus.
        window.addEventListener("touchstart", function (event) {
            if (!isReal(event)) { return; }
            var y = event.touches && event.touches[0] ? event.touches[0].clientY : 0;
            touching = true;
            touchY = y;
            touchStartY = y;
            touchStartAt = Date.now();
            lastTouchAt = touchStartAt;
            blurredThisTouch = false;
        }, { capture: true, passive: true });
        window.addEventListener("touchmove", function (event) {
            if (!isReal(event)) { return; }
            lastTouchAt = Date.now();
            var y = event.touches && event.touches[0] ? event.touches[0].clientY : touchY;
            if (Math.abs(y - touchY) >= 2) {
                fingerDir = y > touchY ? "down" : "up";
                touchY = y;
            }
            if (blurredThisTouch || !onMarketplace() || Math.abs(y - touchStartY) < 12) { return; }
            if (/^\/marketplace\/create/i.test(location.pathname)) { return; }
            var el = document.activeElement;
            if (!isTyping(el) || !isSearchBox(el)) { return; }
            var target = event.target;
            if (target && target.closest && (target.closest('[role="listbox"], [role="dialog"]') || el.contains(target))) { return; }
            blurredThisTouch = true;
            try { el.blur(); } catch (e) {}
            note("blur", describe(el));
        }, { capture: true, passive: true });
        var lastKeyAt = 0;
        function keyDone(event) {
            if (!isReal(event)) { return; }
            lastKeyAt = Date.now();
        }
        window.addEventListener("keydown", keyDone, true);
        window.addEventListener("input", keyDone, true);
        window.addEventListener("submit", keyDone, true);
        function touchDone(event) {
            if (!isReal(event)) { return; }
            touching = false;
            lastTouchEnd = Date.now();
            lastTouchAt = lastTouchEnd;
        }
        window.addEventListener("touchend", touchDone, { capture: true, passive: true });
        window.addEventListener("touchcancel", touchDone, { capture: true, passive: true });

        var scrollState = { y: 0, at: 0 };
        var pageTall = 0;
        var pageTallAt = 0;
        var upRun = null;
        // The first few listings on the page. The same listings after a trip
        // to the top means the page jumped. New ones mean new results.
        var listings = "";
        function firstListings() {
            var links = document.querySelectorAll('[role="main"] a[href*="/marketplace/item/"]');
            var ids = [];
            for (var i = 0; i < links.length && ids.length < 3; i++) {
                var m = String(links[i].getAttribute("href") || "").match(/\/marketplace\/item\/(\d+)/);
                if (m) { ids.push(m[1]); }
            }
            return ids.join(",");
        }
        var lastJumpPost = 0;
        function restore(target) {
            restoring = true;
            var started = Date.now();
            var urlAt = lastUrlAt;
            var shown = firstListings();
            function finish(how) {
                restoring = false;
                scrollState = { y: pageY(), at: Date.now() };
                note("jump", how);
            }
            (function again() {
                // New results arrived, or you moved: leave the page alone.
                if (shown !== "" && firstListings() !== shown) { finish("new listings, left alone"); return; }
                if (Date.now() - started > 3000 || touchStartAt > started || lastUrlAt !== urlAt) { finish("could not put back"); return; }
                if (pageHeight() - (window.innerHeight || 0) >= target - 20) {
                    window.scrollTo(0, target);
                }
                if (Math.abs(pageY() - target) < 40) { finish("put back"); return; }
                setTimeout(again, 100);
            })();
        }
        function onPageScroll() {
            var now = Date.now();
            var y = pageY();
            var before = scrollState;
            scrollState = { y: y, at: now };
            if (fenceOn) {
                var limit = fenceLimit() + 400;
                if (y > limit) { window.scrollTo(0, limit); }
            }
            if (restoring || !onMarketplace()) {
                upRun = null;
                return;
            }
            if (now - pageTallAt > 500 && !upRun) {
                pageTall = pageHeight();
                pageTallAt = now;
                listings = firstListings();
            }
            if (y > before.y + 1) {
                upRun = null;
                return;
            }
            if (y >= before.y - 1) { return; }
            if (!upRun) {
                upRun = { from: before.y, at: before.at || now, tall: pageTall, listings: listings, steps: 0, you: false };
            }
            upRun.steps++;
            if (youScrollUp(now)) { upRun.you = true; }
            if (!(y <= 150 && upRun.from >= 400 && upRun.from - y >= 300)) { return; }
            var run = upRun;
            upRun = null;
            // A move you made: your finger, a tap, typing, or new results (a new
            // search or filter, or different listings at the top).
            var newListings = run.listings !== "" && firstListings() !== run.listings;
            if (run.you || now - startedAt < 1500 || now - lastTapAt < 1500 || now - lastKeyAt < 5000 ||
                now - lastNewResultsAt < 5000 || newListings) {
                note("scroll-top", "from " + Math.round(run.from));
                return;
            }
            var call = lastScrollCall && lastScrollCall.at >= run.at - 300 ? lastScrollCall.what : "";
            // A jump all at once, or one the page's own code made, is put back.
            // A smooth trip to the top with neither may be a tap on the phone's
            // status bar, so that one is left alone.
            var putBack = run.steps === 1 || !!call;
            var detail = "from " + Math.round(run.from) + " to " + Math.round(y) +
                (run.steps === 1 ? " at once" : " in " + run.steps + " steps") +
                ", page " + Math.round(run.tall) + " tall, now " + Math.round(pageHeight()) +
                ", focus " + describe(document.activeElement) +
                (call ? ", after " + call : "") +
                (now - lastUrlAt < 5000 ? ", address changed" : "") +
                (touching ? ", finger on screen" : "") +
                (putBack ? ", put back" : ", left alone");
            note("jump", detail);
            if (now - lastJumpPost > 3000) {
                lastJumpPost = now;
                post({ type: "jump", url: location.href, detail: detail });
            }
            if (putBack) { restore(run.from); }
        }
        window.addEventListener("scroll", function (event) {
            if (!fenceOn && !onMarketplace()) { return; }
            var target = event.target;
            if (target === document || target === window || target === document.documentElement || target === document.scrollingElement) {
                onPageScroll();
            } else {
                fenceBox(target);
            }
        }, { capture: true, passive: true });

        // ---------------------------------------------------------- your posts

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
        // once later. The app double-checks every one. Profiles are not posts.
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
                if (!info || info.profile || sentKeys[info.key] || window.__as_known[info.key]) { continue; }
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
            var facebook = false;
            if (host.indexOf("instagram") !== -1) {
                css = `$ig`;
            } else if (host.indexOf("facebook") !== -1 || host.indexOf("messenger") !== -1) {
                css = `$fb`;
                facebook = host.indexOf("facebook") !== -1;
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

            // textContent, not innerText. Reading innerText makes the browser lay the
            // page out again, and this runs while you scroll.
            function hideNags() {
                var nodes = document.querySelectorAll("button, a, [role='button']");
                for (var i = 0; i < nodes.length; i++) {
                    var node = nodes[i];
                    if (node.__asChecked) { continue; }
                    node.__asChecked = true;
                    var text = (node.textContent || "").trim();
                    if (text.length > 0 && text.length < 30 && nagPattern.test(text)) {
                        node.style.setProperty("display", "none", "important");
                    }
                }
            }

            // Marketplace ads. Every listing sits in a grid cell, and each row of the
            // grid keeps its own <style> tags next to the cells. An ad's cell links out
            // through l.facebook.com and holds no listing. Ads made Marketplace jump
            // back to the top while scrolling, so they are taken out.
            // Returns the cell to hide, false when the link is not an ad cell, or null
            // when it cannot tell yet (the page has not laid it out).
            function adCell(link) {
                var el = link;
                for (var depth = 0; depth < 24 && el && el.parentElement; depth++) {
                    var parent = el.parentElement;
                    for (var c = parent.firstElementChild; c; c = c.nextElementSibling) {
                        if (c.tagName !== "STYLE") { continue; }
                        if (el.querySelector('a[href*="/marketplace/item/"]')) { return false; }
                        var width = el.getBoundingClientRect().width;
                        var rowWidth = parent.getBoundingClientRect().width;
                        if (!width || !rowWidth) { return null; }
                        return width > rowWidth * 0.6 ? false : el;
                    }
                    el = parent;
                }
                return false;
            }

            // A hidden ad's video never plays or loads in the background.
            function quiet(cell) {
                var videos = cell.querySelectorAll("video");
                for (var v = 0; v < videos.length; v++) {
                    try {
                        videos[v].pause();
                        videos[v].removeAttribute("autoplay");
                        videos[v].preload = "none";
                    } catch (e) {}
                }
            }

            var hiddenCells = [];
            function hideMarketplaceAds() {
                // Keep the ones already found hidden, in case the page rewrites them.
                for (var h = hiddenCells.length - 1; h >= 0; h--) {
                    var old = hiddenCells[h];
                    if (!old.isConnected) { hiddenCells.splice(h, 1); continue; }
                    if (old.style.getPropertyValue("display") !== "none") {
                        old.style.setProperty("display", "none", "important");
                    }
                }
                if (!facebook || location.pathname.indexOf("/marketplace") !== 0) { return; }
                var links = document.querySelectorAll('[role="main"] a[href*="l.facebook.com/l.php"]');
                for (var i = 0; i < links.length; i++) {
                    var link = links[i];
                    if (link.__asAdChecked) { continue; }
                    var cell = adCell(link);
                    if (cell === null) { continue; }
                    link.__asAdChecked = true;
                    // An ad has more than one outside link. Hide its cell once.
                    if (cell && !cell.__asAdHidden) {
                        cell.__asAdHidden = true;
                        cell.style.setProperty("display", "none", "important");
                        quiet(cell);
                        hiddenCells.push(cell);
                        window.__as_adsHidden = (window.__as_adsHidden || 0) + 1;
                        if (window.__as_note) { window.__as_note("ad", "hidden"); }
                    }
                }
            }

            function sweep() {
                ensureStyle();
                hideNags();
                hideMarketplaceAds();
            }

            sweep();

            // An ad is taken out the moment it is added, before it is ever drawn.
            // Everything else waits for the next sweep.
            var adLink = 'a[href*="l.facebook.com/l.php"]';
            function addsAd(mutations) {
                for (var m = 0; m < mutations.length; m++) {
                    var added = mutations[m].addedNodes;
                    for (var n = 0; n < added.length; n++) {
                        var node = added[n];
                        if (node.nodeType !== 1) { continue; }
                        if ((node.matches && node.matches(adLink)) || (node.querySelector && node.querySelector(adLink))) { return true; }
                    }
                }
                return false;
            }

            var pending = false;
            var observer = new MutationObserver(function (mutations) {
                if (facebook && location.pathname.indexOf("/marketplace") === 0 && addsAd(mutations)) {
                    hideMarketplaceAds();
                }
                if (pending) { return; }
                pending = true;
                setTimeout(function () {
                    pending = false;
                    sweep();
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
            // Once the page is parsed, watch only <head>, where a viewport tag
            // lives. Watching every change on a busy page like Marketplace
            // costs time on every scroll.
            function narrow() {
                observer.disconnect();
                apply();
                if (document.head) { observer.observe(document.head, { childList: true }); }
                observer.observe(document.documentElement, { childList: true });
            }
            if (document.readyState === "loading") {
                document.addEventListener("DOMContentLoaded", narrow, { once: true });
            } else {
                narrow();
            }
        })();
    """.trimIndent()

    /**
     * Describes the links and labels on the page. The Diagnostics screen shows
     * this so you can send it over when something slips through.
     */
    val pageMap: String = """
    (function () {
        // What the page looks like right now, for the Diagnostics screen: where
        // it is scrolled to, what has the focus, what the app did on this page
        // lately, and every link on it.
        var root = document.scrollingElement || document.documentElement;
        var active = document.activeElement;
        var focus = "none";
        if (active && active.tagName && active !== document.body && active !== document.documentElement) {
            focus = active.tagName.toLowerCase() + (active.getAttribute("type") ? "[" + active.getAttribute("type") + "]" : "");
            var name = active.getAttribute("aria-label") || active.getAttribute("placeholder") || "";
            if (name) { focus += " '" + name.slice(0, 40) + "'"; }
        }
        var out = {
            url: location.href,
            title: document.title,
            scrolledTo: Math.round(window.pageYOffset || 0),
            pageHeight: root ? root.scrollHeight : 0,
            screenHeight: window.innerHeight,
            focus: focus,
            adsHidden: window.__as_adsHidden || 0,
            stopsShort: !!document.getElementById("__as_fence"),
            events: (window.__as_events || []).slice(-40),
            links: [],
            labels: []
        };
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
