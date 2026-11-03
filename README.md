# Anti-Social (Android)

A locked-down browser for Facebook and Instagram, made for creating instead of
consuming. Messages, Marketplace, alerts, groups, your own posts, and posting
all work. Feeds do not load at all, and nothing ever moves on to the next post
by itself, so there is nothing to scroll.

Five tabs:

| Tab | What it opens |
|---|---|
| Messages | facebook.com/messages (Messenger inbox and conversations) |
| Market | facebook.com/marketplace (browse, search, your listings, buyer chats) |
| Alerts | Three pages, picked with the switch at the top. **Alerts** is facebook.com/notifications. **My Posts** is your own Facebook profile. **Groups** is the list of groups you are in. |
| IG Inbox | instagram.com/direct/inbox (Instagram DMs) |
| IG Alerts | Two pages again. **Alerts** is instagram.com/notifications. **My Posts** is your own Instagram grid. |

Tap the tab you are already on to jump back to its home page.

## Your posts, and posts you tap

Your own posts, Reels, videos, photos, and stories always open. That is how you
read and answer comments: tap a comment alert, or open the post from My Posts,
and reply right on the post.

With **Open other people's posts I tap** on (the default), a post by someone
else opens too, as long as you tapped a link to it. What never happens is the
next one. Swiping to the next Reel, a story moving on to the next person, or
autoplay into another video is blocked, and you stay on the post you opened.
Turn the switch off and only your own posts open.

How the app tells the two apart:

1. A tap. A link you tap opens that one post. A tap that does not point at a
   post (a shared-post card in a chat, a share link that redirects) opens the
   first post it leads to, but only when you start from a page that is not a
   post, like a chat or your alerts. From inside a post, only a link to another
   post counts, so the player can never move you on.
2. The link names you: `facebook.com/<you>/videos/<id>`,
   `instagram.com/<you>/reel/<code>`, `permalink.php?...&id=<your number>`, and
   so on. Your username comes from facebook.com/me and from Instagram's own
   nav. Your account number comes from each site's login cookie.
3. The app has seen it before. Every post on your own Reels grid (Facebook) or
   your grid (Instagram) is remembered as yours, along with every post it has
   checked.
4. Anything else is looked up once. Bare links like `facebook.com/reel/<id>` or
   `instagram.com/p/<code>` do not say who made them, so the app fetches that
   post's page and reads the owner from its header (`og:url` and the canonical
   link, which carry the owner's username). It tries as a visitor first, then
   as you, for posts only your friends can see. The page stays covered while
   this happens, so a post you did not ask for never shows.

## Groups

The **Groups** page lists the groups you are in. Each group opens in full: its
posts, comments, members, and the box to write a post. Posts inside a group
always open. The mixed Groups feed (posts from all your groups at once) and
group suggestions stay blocked.

## Install it

Download **anti-social.apk** from the
[latest release](../../releases/latest) on the phone itself, then open it.

Android will ask whether to allow installing apps from this source, because the
app does not come from the Play Store. That prompt is normal for an app someone
built for you. Allow it for your browser or files app, and the install proceeds.

**Updating from a build older than 1.2:** remove the old Anti-Social first
(long-press its icon, then Uninstall), once. Older builds were each signed with
a different throwaway key, so Android refuses to install a newer one over them.
From 1.2 on every build uses the same key (`app/debug.keystore`, kept in the
repo on purpose), and updates install over each other.

## What is blocked

The home feed, the Reels feed, Watch, the Stories feed, Friends, the Groups
feed and suggestions, Gaming, Events, Memories, Saved, Search, Explore,
hashtags, every profile except your own, and any post by someone else that
you did not tap.
Links that try to open the real Facebook or Instagram apps are blocked too.
Video never autoplays.

Blocking happens in two places:

1. Every real page load runs through the allowlist in `AccessPolicy.kt`. If the
   URL is not on the list, the navigation is cancelled.
2. Facebook and Instagram change the URL without a real page load most of the
   time, and `shouldOverrideUrlLoading` never sees those. A small script
   (`Injected.kt`) reports every in-page URL change to the app, which checks the
   same list and steps back if the page is not allowed.

On top of that, feed links, Reels tabs, and "open the app" nags are hidden
inside the pages. That part is cosmetic. The URL rules are the wall.

## Posting

Tap the pencil in the top bar for the posting menu.

- **Facebook:** New post, New reel, New story, Go live, Sell something. New post
  and New reel open your profile, scroll down to its post box or Reel button,
  and open it for you. Pick your photo or video in the window that opens.
- **Instagram:** New post or reel, and New story. Both open your profile and make
  Instagram's own + button glow. Tap it, then pick a photo or video (a video
  becomes a reel), or choose Story. Instagram only opens the photo picker from a
  real tap, so the app points at the button instead of pressing it.

Two details worth knowing, both discovered the hard way:

- The Facebook composer opens as a dialog and never changes the URL, so there is
  no risk of being bounced out mid-post. Story creation and Go Live are real
  pages (`/stories/create`, `/live/producer`) carved out of the Stories feed and
  Live blocks.
- `instagram.com/create` is a real person's account, not a composer. Allowing
  `/create/` blindly would open a stranger's profile. So Instagram's create flow
  is only allowed on its deeper step paths, and on a cold load only for step
  names the app knows.

## Marketplace ads

Ads in the Marketplace results are hidden. They made the results jump back to
the top while scrolling. Each listing sits in a grid cell, and an ad's cell links
out through `l.facebook.com` and holds no listing, which is how the app tells
them apart (see `hidingScript` in `Injected.kt`).

## Your username

The app fills in your usernames by itself: Facebook comes from the
`facebook.com/me` redirect, Instagram from the profile link in the site's own
nav bar. They unlock your own profile (where the composer lives) and your own
posts. Opening **My Posts** once in each Alerts tab is the quickest way to teach
the app. You can also type them into Settings if the automatic detection misses.

## Why Facebook uses the desktop site

Facebook's mobile site refuses to serve Messenger. Loading
`facebook.com/messages` with a phone user agent immediately redirects to the
home feed, which this app then blocks. The desktop site has a working Messenger,
so Facebook gets a desktop user agent plus an injected viewport tag that lays the
page out narrow and scales it to fit the phone. Change **Desktop page width** in
Settings if the text is too small or the layout feels cramped.

## When something slips through

The circle menu has **Diagnostics**: it captures the links and labels on the
current page plus a log of everything that was blocked, and lets you share that
text, along with which accounts the app thinks are yours and how many of your
posts it knows. Send it along with what you saw and the rule can be tightened.

## Building it yourself

Open the project in Android Studio and press Run, or from a terminal with a JDK
17 installed:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Files

```
app/src/main/java/com/stefanoswald/antisocial/
  MainActivity.kt       Tabs, top bar, compose menu, diagnostics.
  AccessPolicy.kt       The allowlist. Change rules here.
  OwnershipParser.kt    Reads who made a post off the post's page.
  OwnershipChecker.kt   Remembers your posts and looks up new ones.
  TabController.kt      One WebView per tab, navigation and policy enforcement.
  Injected.kt           The in-page URL hook, hiding CSS, diagnostics script.
  AppSettings.kt        User settings, saved in shared preferences.
  WebTab.kt             The pages, their home URLs, and the five tabs that hold them.
  SettingsActivity.kt   The settings screen.
```
