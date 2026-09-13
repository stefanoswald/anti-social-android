# Anti-Social (Android)

A locked-down browser for Facebook and Instagram. It loads only the parts you
need and refuses everything else before it loads, so there is nothing to scroll.

Five tabs:

| Tab | What it opens |
|---|---|
| Messages | facebook.com/messages (Messenger inbox and conversations) |
| Market | facebook.com/marketplace (browse, search, your listings, buyer chats) |
| Alerts | facebook.com/notifications (comments, mentions, tags, and the posts they point to) |
| IG Inbox | instagram.com/direct/inbox (Instagram DMs) |
| IG Alerts | instagram.com/notifications (comments and mentions on your posts) |

Tap the tab you are already on to jump back to its home page.

## Install it

Download **anti-social.apk** from the
[latest release](../../releases/latest) on the phone itself, then open it.

Android will ask whether to allow installing apps from this source, because the
app does not come from the Play Store. That prompt is normal for an app someone
built for you. Allow it for your browser or files app, and the install proceeds.

## What is blocked

Home feed, Reels, Watch, Video, Stories, Friends, Groups feed, Gaming, Events,
Memories, Saved, Search, Explore, hashtags, and every profile except your own.
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

The pencil button in the top bar opens the composer. On Facebook it also offers
**New story**, **Go live**, and **Sell something**. Creating is allowed;
watching other people's Reels and Stories is not, which is the whole idea.

Two details worth knowing, both discovered the hard way:

- The Facebook composer opens as a dialog and never changes the URL, so there is
  no risk of being bounced out mid-post. Story creation and Go Live are real
  pages (`/stories/create`, `/live/producer`) carved out of the Stories and Live
  blocks.
- `instagram.com/create` is a real person's account, not a composer. Allowing
  `/create/` blindly would open a stranger's profile. So Instagram's create flow
  is only allowed on its deeper step paths, and on a cold load only for step
  names the app knows.

## Your username

The app fills in your usernames by itself: Facebook comes from the
`facebook.com/me` redirect, Instagram from the profile link in the site's own
nav bar. They unlock your own profile, which is where the composer lives. You
can also type them into Settings if the automatic detection misses.

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
text. Send it along with what you saw and the rule can be tightened.

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
  TabController.kt      One WebView per tab, navigation and policy enforcement.
  Injected.kt           The in-page URL hook, hiding CSS, diagnostics script.
  AppSettings.kt        User settings, saved in shared preferences.
  WebTab.kt             The five tabs and their home URLs.
  SettingsActivity.kt   The settings screen.
```
