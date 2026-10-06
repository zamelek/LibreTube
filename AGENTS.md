# LibreTube (fork: playback fixes)

Android YouTube client based on [LibreTube](https://github.com/libre-tube/LibreTube) (upstream 32.1)
with fixed playback of videos and livestream recordings. Fork: `zamelek/LibreTube`, branch `master`.
There is no need to publish to Google Play, only APKs and GitHub releases.

## Working rules for the agent

The agent may work with this repository on its own: edit code, commit, push to `master` and publish
releases, without asking for each step. After every finished bug fix or new feature:

1. Build and check it (`assembleDebug`, on the phone when the change touches playback or the UI).
2. Note the change in this file (a numbered item above and a row in the release history).
3. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
4. Commit (one commit per logical change, conventional style like `fix(player): ...`), push to `master`,
   then `git tag vX.Y.Z && git push origin vX.Y.Z` so that `build-release.yml` builds and publishes the
   signed APK. Check that the workflow run succeeded (`gh run list`, `gh release view vX.Y.Z`).

**Keep the diff to upstream small.** The owner wants to send the fixes to upstream as pull requests, and a
large diff would be rejected. So: fix the bug with the smallest possible change, keep upstream behavior,
layout and UI design, no refactoring, no new settings or screens, no behavior changes that are not
needed for the fix. If a fix would change what the user sees or does, ask first. Fork-only things
(`applicationId`, update URL, workflows, `AGENTS.md`, probes) stay out of pull requests.

Limits that stay: never read or print the keystore, `~/Projects/Personal/WireGuard` or repository
secrets; never force-push or rewrite published history and tags; do not commit unrelated local files
(`skills-lock.json`, `.autopilot/`); keep driving the owner's phone careful, it is their daily device.

## What was broken and what was done

Symptoms reported by the owner: regular videos did not open at all (endless loading spinner),
livestream recordings often failed with a playback error on WiFi, while everything worked on the
mobile network (5G). The app itself (design, extractor, PoToken) works, the problem was the choice of
the playback source and how the video was rendered.

1. **Playback source** (`services/OnlinePlayerService.kt`). Previously SABR was always used
   (`player/Sabr*`, `player/parser/SabrClient.kt`): one blocking request per segment, download speed
   of about 1x real time, so the buffer never fills and the player keeps buffering. The order is now
   **DASH (direct URLs) → SABR → HLS** (`getStreamSources()`). If a source fails with an error,
   `onPlaybackError()` switches the player to the next one at the same position.
2. **`STATE_IDLE` race.** After an error ExoPlayer goes to IDLE, and the service called
   `onDestroy()` in that state. When switching sources, a stale IDLE event also arrives after
   `prepare()`, when `playerError == null`. Hence the `isSwitchingSource` flag, which is cleared only
   on `STATE_READY` (not on BUFFERING: `prepare()` sets BUFFERING synchronously, before the stale
   IDLE arrives).
3. **Tiny video frame.** `SurfaceView` drew the video as a tiny frame in the top-left corner
   (Pixel 8a and the emulator, Android 16/17, inside the `MotionLayout`). Workaround:
   `app:surface_type="texture_view"` in `layout/fragment_player.xml` and
   `layout-land/fragment_player.xml`. The screenshot button in `PlayerFragment` supports both view
   types (`TextureView.bitmap` / `PixelCopy`). The root cause in `SurfaceView` was not investigated.
4. **Related videos** below the player: in 32.1.1 - 32.1.11 a single vertical list that revealed cards 6
   at a time. **Reverted in 32.1.12** to the upstream behavior (horizontal list in portrait, vertical in
   landscape, everything at once), because the fork should stay close to upstream for a pull request.
   `PlayerFragment` and `layout/fragment_player.xml` no longer differ from upstream in this place.
5. **Retries.** `getStreamsWithRetry()` makes up to 3 attempts to fetch the video data, otherwise a
   single network error left the UI with a spinner forever.
6. **Livestreams.** YouTube often returns an empty DASH manifest URL (an empty string, not `null`)
   for livestreams. Upstream tried to open it as a file and failed instantly with "Source error".
   Livestreams now use DASH only if the manifest is not blank, otherwise HLS directly
   (`getStreamSources()`). Tested on three live streams, and a 150 second run on HLS was stable.
   Permanent errors (`ContentNotAvailableException`, e.g. "This live stream recording is not
   available") are not retried.
7. **Update check.** `api/ExternalApi.kt` (`GITHUB_API_URL`) points at `zamelek/LibreTube`. Upstream
   pointed at `libre-tube/LibreTube`, so the fork offered to install the original app at every start
   (the check shows the dialog whenever the digits of the latest release name differ from
   `versionName`; release names equal the tag, e.g. `v32.1.3`).
8. **Brightness swipe.** Fullscreen is shown in a separate `Dialog` (`PlayerFragment.fullscreenDialog`)
   with its own window. `BrightnessHelper` used to set the brightness on the activity window, which
   is covered by the dialog, so the system ignored it: the on-screen slider moved but the screen
   brightness did not change. `BrightnessHelper` now takes a window provider
   (`CustomExoPlayerView.getWindow()`), and the `currentWindow` setter moves the override to the new
   window. The PiP window also kept a stale override and, being always on top, blocked every other
   window's brightness, so it is reset in `CustomExoPlayerView.onPictureInPictureModeChanged()`.
   How to verify: in fullscreen swipe on the left half, then
   `adb shell dumpsys display | grep -m1 mBrightnessReason` must show `override(<package>/...)`
   instead of `automatic`, and `dumpsys window windows` shows `sbrt=` on the fullscreen window.
9. **SponsorBlock and seeking.** `AbstractPlayerService.checkForSegments()` used to skip every
   automatic segment the player position is in, also after the user seeked into it. For a segment
   that runs to the end of the video (the `sponsor` segments of the "World of Tanks ... (Gingertail
   Cover)" videos in the owner's Music playlist) this jumped to the end, showed the "Skipped segment"
   toast and, with the owner's repeat mode `ALL`, restarted the video. A position jump larger than
   `SEEK_DETECTION_THRESHOLD_MS` between two 100 ms checks is now treated as a user seek and the
   segment is remembered in `segmentSeekedInto`; it is not skipped until the position has left it.
   Playback that enters a segment on its own is still skipped. Checked with `KEYCODE_MEDIA_FAST_FORWARD`
   (15 s steps): landing inside a segment keeps playing, playing into it skips and starts the next video.
10. **Seek bar segments.** `ui/views/MarkableTimeBar.kt` draws the SponsorBlock segments in the color of
   their category (the preference colors if "custom colors" is on, otherwise the defaults from
   `sponsorblock_settings.xml`, which are the SponsorBlock standard colors). They are drawn after the
   normal bar, 2 dp high like `app:bar_height`, and only right of the scrubber (plus a clearance of 8 dp),
   so the played part keeps the progress color, the scrubber keeps its own color and is never covered,
   and a segment continues the line. The scrubbing position comes from an `OnScrubListener`.
   Categories set to "Manual" in Settings → SponsorBlock are shown on the bar without being skipped
   automatically; "Off" categories are not requested and therefore not shown.
11. **Dead pooled connections (the "floating" loading wheel).** After a video ended, fetching the
   streams of the next one timed out three times in a row (`SocketTimeoutException` in
   `YoutubeStreamHelper.getVisionOsPlayerResponse`, 10 s each) and the player showed a wheel forever.
   The extractor's `OkHttpClient` (`util/NewPipeDownloaderImpl.kt`) kept an HTTP/2 connection in its
   pool that the router had silently dropped while the video played, and all retries reused it.
   This is also why the owner saw errors on WiFi but not on mobile data. The client now closes idle
   connections after 20 s, pings HTTP/2 connections every 15 s, and on an `IOException` drops the
   whole pool and repeats the request once on a new connection.
   The log line to look for: `failed to fetch streams (attempt N): java.net.SocketTimeoutException`.
12. **Failed fetch skips to the next queue item.** If the streams of a video cannot be loaded after the
   retries, `OnlinePlayerService.skipToNextVideoAfterFailure()` plays the next video of the queue (at
   most 3 failures in a row) instead of leaving the player loading forever.
13. **YouTube answers "Sign in to confirm you're not a bot" to the app (temporary, cause unknown).**
   The log shows `SignInConfirmNotBotException: YouTube probably temporarily blocked anonymous watch
   access with this IP` (thrown by `fetchVisionOsClient` → `checkPlayabilityStatus`, so the
   response to the *visionOS* player request itself had `LOGIN_REQUIRED`). The extractor only uses
   this one client for streaming data and has no fallback client.
   What was measured on 2026-10-05 (same home network, Mac and phone share the router):
   - From about 16:59 to 18:05 the phone app was challenged: first attempts failed for 24 of 24
     videos in one window, before that intermittently. The owner's browser (a clean anonymous
     session: only the `PREF` and `SOCS` cookies, not logged in) played videos the whole time.
   - At about 18:15-18:45 everything passed again: 110+ visionOS requests from the Mac (IPv4, IPv6
     and both, including a burst of 80 requests in 63 s) and 45 videos on the phone, without a single
     challenge. So the IP itself is **not** blocked, and the request is not blocked as such: headers,
     HTTP/2 and TLS 1.3 of the visionOS request are the same on the phone and on the Mac, the PoToken
     provider is not used for it.
   - The earlier conclusion "YouTube blocks the Wi-Fi IP" was therefore too strong. What is known:
     the challenge comes and goes for a period of about an hour, it affected the app's client while a
     browser on the same IP worked. What triggers it (volume of requests from one device, the TLS
     fingerprint of the Android stack with a visionOS user agent, the state of the visitor data, ...)
     is **not** known. It started after several hours of heavy test traffic from the phone, which
     may be the trigger.
   Mitigation: none in the app. The owner turns Wi-Fi off by hand when it happens; the app then uses
   the mobile connection, which was not challenged. `getStreamsWithRetry()` retries every failed fetch 3
   times (0.7 and 1.4 s pauses) and `skipToNextVideoAfterFailure()` plays the next queued video.
   **An automatic switch to mobile data was built and removed again** (added in 32.1.8, removed in
   32.1.9 at the owner's request, the owner prefers to decide about mobile data themselves). It is in
   git history: `git show cd188245d`. It asked for the cellular network with
   `ConnectivityManager.requestNetwork` after 2 bot checks and bound the whole process to it with
   `bindProcessToNetwork` (the whole process, because stream URLs only work for the IP that requested
   them), closed the pooled connections, ended when the player service stopped or after 20 minutes,
   had a setting and needed `CHANGE_NETWORK_STATE`. It worked against a real challenge (two bot checks,
   then `all traffic is sent through the mobile network now`, video played). It also retried this
   error 5 times with 2, 4, 6, 8 s pauses, which was removed with it.
   **Two different situations.**
   1. *Home Wi-Fi, temporary, app only.* Described above: the owner's browser worked, the Mac passed,
      the cause is unknown.
   2. *Datacenter / VPN IP, permanent, everyone.* The owner has a WireGuard server on Azure (their
      `~/Projects/Personal/WireGuard` folder, contains keys, do not read or print it). With the tunnel
      on, YouTube shows "sign in to confirm you're not a bot" to **every** anonymous client, also to
      the browser (normal and private tab). This is a deterministic reproduction rig: a second phone
      (Samsung SM-M215F, Android 12, serial `R58N401YBYP`, WireGuard installed) is used for it. There
      the app fails every fetch with `SignInConfirmNotBotException`, and a probe that was built into
      the app showed the same for the **iOS and the visionOS client** (both `LOGIN_REQUIRED`; the web
      metadata request returned an almost empty answer). So a different client would not help on such
      an IP, and neither would a WebView, because the browser is blocked too. ReVanced on that phone
      works because the owner is **signed in**: signed-in sessions pass this check, anonymous ones do
      not. The extractor has no login support (the iOS/visionOS clients do not take cookies), so a
      signed-in session would be a big feature, with privacy and account risks.
      Cheap workaround without code: exclude LibreTube from the tunnel in the WireGuard app (tunnel
      settings, excluded applications), then the app uses the normal connection.
   **Login through the Google account of ReVanced GmsCore does not work for this app.** Tested on the
   Samsung: the account (type `app.revanced`, authenticator `app.revanced.android.gms`) can be chosen
   with `AccountManager.newChooseAccountIntent` (that makes it visible to the app), but
   `getAuthToken` for `oauth2:https://www.googleapis.com/auth/youtube`, `.../youtube.force-ssl` and
   `.../youtube.readonly` ended with `OperationCanceledException` for every scope, i.e. GmsCore refuses
   to issue tokens to the package `com.github.libretube.fork`. ReVanced works because its patched
   YouTube app is special-cased by its own GmsCore. Other ways to get a signed-in session, none of them
   tried yet: a Google login in a WebView and cookies (needs the web client with PoToken and SABR
   instead of the visionOS client, a big change) and the OAuth device flow of the YouTube TV client
   (`google.com/device`, uncertain whether Google still accepts tokens of a third-party client).
   **The official Google Play Services refuses too, with a different reason.** Tested on the same Samsung
   after removing ReVanced GmsCore and adding the owner's account to the real GMS (account type
   `com.google`, authenticator `com.google.android.gms`): the account chooser works and makes the
   account visible, but `getAuthToken` for the same three YouTube scopes fails for every scope with
   `AuthenticatorException: UnregisteredOnApiConsole`. GMS only issues OAuth tokens to an app whose
   package name and signing certificate SHA-1 are registered as an **Android OAuth client** in a Google
   Cloud project (with the YouTube Data API enabled). Not tried: creating such a client (needs the
   owner's Google Cloud project; the YouTube scopes are sensitive, so the consent screen would stay in
   "Testing" mode with the owner as a test user, and the client needs the SHA-1 of the release key for
   `com.github.libretube.fork`, or of the debug key for `...fork.debug`). Even then it is **unverified**
   whether InnerTube (TV/ANDROID/IOS player requests) accepts such a Bearer token: the YouTube Data API
   does, InnerTube is a different, internal API.
   How to investigate the next time it happens (do it *while* it happens, not afterwards):
   1. On the phone open a video and look for `failed to fetch streams (attempt 1): ...SignInConfirmNotBotException` in the log.
   2. On the development machine run the opt-in probe:
      `BOT_CHECK_PROBE=1 ./gradlew testDebugUnitTest --tests '*BotCheckProbeTest*' -i | grep PROBE`
      (`BotCheckProbeTest` compares the ios and visionOS clients and IPv4/IPv6/both, and can send a
      burst). If the Mac passes while the phone is challenged, the difference is in the phone, not in
      the IP.
   3. Open the same video in a clean browser session (Playwright or a private window) and read
      `ytInitialPlayerResponse.playabilityStatus`.
   Tried and did not help:
   - A newer extractor: `libre-tube/NewPipeExtractor` has no commit newer than `3e863d7`.
   - **Keeping YouTube cookies between requests** (an interceptor that stored the cookies of the
     extractor requests and deleted them after N days): YouTube only sets `YSC`, `__Secure-BUCKET` and
     `__Secure-YENID` for them (no visitor id), and with the cookies kept the first attempt was
     challenged for 12 of 12 videos, like without cookies (12 of 12, measured inside the challenged
     window). Removed again, it would only add tracking.
   - Rotating cookies or other identity values to look like a new client: not tried, and a new
     anonymous client is more likely to be challenged than an established one.
   - Every failing fetch is retried 5 times, so measuring many videos during a challenge sends a lot
     of requests. Keep experiments short.
   Not tried yet: a WebView based player request (real browser engine), a fallback to the iOS client
   when the visionOS client is challenged (the iOS client passed in all probes, but the extractor only
   fetches it after the visionOS client succeeded, so it would need a patched extractor), a signed-in
   session.
14. **applicationId** = `com.github.libretube.fork` (debug: `...fork.debug`), so the fork installs
   next to the original app (different signature, so the fork can never update the original). The
   owner has since removed the original from the phone and uses only the fork.
15. **Brightness swipe: the lowest step (0) and auto.** Swiping down jumped from 0 to auto on the very
   next event (the value 0 was visible for less than 200 ms), and after auto `BrightnessHelper` still
   remembered the old manual value, so re-entering fullscreen restored it instead of auto. Now 0 is held
   until the swipe goes on for `AUTO_BRIGHTNESS_SWIPE_DISTANCE` (150 px) more, only then
   `BrightnessHelper.switchToAutomatic()` hands the brightness to the system, and `isAutomatic` makes
   `restoreSavedBrightness()` keep auto. Checked with `dumpsys display | grep mBrightnessReason`
   (`override(...)` at 0, `automatic` after the extra swipe and after leaving/entering fullscreen).
16. **End of video with autoplay off.** `AbstractPlayerService.onVideoEnded()` (called from the online and
   offline service when the video ends) plays nothing if "Autoplay" is off, unless the repeat mode is
   "Current". Before, the queue (`PlayingQueue.getNext()`) was used regardless of autoplay, and with
   repeat mode "Repeat all" and one video in the queue the video started again. The autoplay countdown in
   `PlayerFragment` is shown only if autoplay is on. The "next" button still works. Checked on a 19 s
   video (`jNQXAC9IVRw`), prefs `autoplay=false`, `repeat_mode=2`: it stops at 00:19; with `autoplay=true`
   it starts again.
17. **Brightness swipe starts at the slider of the phone.** In 32.1.10 the brightness bar started at 0
   whenever the brightness was automatic, although the slider of the phone stood higher.
   `BrightnessHelper.systemBrightness` reads the slider (`screen_auto_brightness_adj`, mapped from
   [-1, 1], with adaptive brightness; otherwise `screen_brightness` through the brightness curve), and
   `CustomExoPlayerView.updateBrightness()` takes it as the start of every swipe while the brightness is
   automatic. The swipe changes only the brightness of the player window, the slider of the phone does not
   move (that would need the "modify system settings" permission; the owner chose the version without it).
   Checked on the phone: a short swipe up from auto gives `sbrt=0.12` instead of `0.007`.

## Release history of the fork

All releases are signed with the same key and published at
https://github.com/zamelek/LibreTube/releases. Each version has a one line changelog in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

| Version | versionCode | What changed |
|---------|-------------|--------------|
| 32.1.1  | 73 | DASH → SABR → HLS source chain with automatic fallback, `TextureView` instead of `SurfaceView`, vertical related list, retries for stream fetching, idle race fix, new `applicationId`, new release workflow |
| 32.1.2  | 74 | Livestreams: skip the empty DASH manifest and use HLS, do not retry permanent errors |
| 32.1.3  | 75 | Update check looks at this fork's releases instead of upstream |
| 32.1.4  | 76 | Brightness swipe applies to the fullscreen dialog window, PiP window no longer keeps a stale brightness |
| 32.1.5  | 77 | SponsorBlock segments are not auto-skipped after the user seeks into them, segments are shown in color on the seek bar |
| 32.1.6  | 78 | Seek bar segments: same thickness as the progress line, never cover the scrubber |
| 32.1.7  | 79 | Dead pooled connections no longer cause endless loading, failed fetch plays the next queued video |
| 32.1.8  | 80 | Bot check of YouTube: longer retries, then the video is loaded over mobile data (setting). Removed again in 32.1.9 |
| 32.1.9  | 81 | The mobile data switch is removed, the app behaves like 32.1.7 |
| 32.1.10 | 82 | Brightness swipe holds 0 before auto and remembers auto, nothing plays after a video ends when autoplay is off |
| 32.1.11 | 83 | Brightness swipe starts at the position of the phone's brightness slider |
| 32.1.12 | 84 | Related videos list is back to the upstream behavior (horizontal in portrait) |

## Files changed compared to upstream

Base is upstream commit `b265e2d02`. Everything else in the tree is unchanged upstream code.

- `services/OnlinePlayerService.kt`, `services/AbstractPlayerService.kt`: source selection and
  fallback, `isSwitchingSource`, `getStreamsWithRetry()`, the `onPlaybackError()` hook.
- `ui/fragments/PlayerFragment.kt`: screenshot for both view types, forwards PiP changes to the player
  view, autoplay countdown only if autoplay is on.
- `ui/views/CustomExoPlayerView.kt`, `helpers/BrightnessHelper.kt`: brightness follows the shown
  window, PiP reset.
- `services/AbstractPlayerService.kt` (`checkForSegments()`), `ui/views/MarkableTimeBar.kt`:
  SponsorBlock seek handling and the colored segments on the seek bar.
- `res/layout/fragment_player.xml`, `res/layout-land/fragment_player.xml`: `texture_view` surface.
- `api/ExternalApi.kt`: update check URL.
- `util/NewPipeDownloaderImpl.kt`: connection pool, HTTP/2 ping and retry on a fresh connection.
- `app/src/test/java/com/github/libretube/BotCheckProbeTest.kt`: opt-in diagnostic for the YouTube bot
  check (`BOT_CHECK_PROBE=1`), not part of the normal test run.

The SABR classes, the extractor, the DASH manifest builder and the UI design are untouched.

## Build and run

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # unsigned, R8 enabled; CI does the signing
```

- CI uses JDK 17, locally JDK 21 from brew (`openjdk@21`) works.
- Gradle downloaded Android SDK Platform 36 into `~/Library/Android/sdk` by itself (only 37 was
  installed).
- The first launch of a debug build shows a welcome screen: keep "None" selected and tap OK.
- Test device: Pixel 8a over USB (serial `49261JEKB11306`), plus the emulator `a17`
  (`~/Library/Android/sdk/emulator/emulator -avd a17`). With two devices always use
  `adb -s <serial>`. The phone's USB connection drops often: run long log captures on the device
  itself (`adb shell "nohup logcat --pid=<PID> -v time -f /sdcard/x.log &"`) and `adb pull` them
  afterwards.
- Open a video for testing: `adb shell am start -a android.intent.action.VIEW -d
  "https://www.youtube.com/watch?v=<id>" com.github.libretube.fork.debug`.
- Measuring jank: `adb shell dumpsys gfxinfo <package> reset`, scroll with `input swipe`, then
  `dumpsys gfxinfo <package>`. Debug builds are noticeably slower than release (99th percentile
  frame time ~120-150 ms vs ~50 ms), so compare on release builds.
- How to test the fallback: temporarily replace the DASH source in `setStreamSource()` with an
  unreachable URL and check that the log shows `source DASH failed, trying the next one` and that
  SABR starts loading segments (`SabrStream: getNextSegment`). Remove the temporary code afterwards.
- The "local version available" dialog (`PlayOfflineDialog`) appears for videos that are already
  downloaded. Until it is answered the player shows a spinner, and "Yes" for a video that was
  downloaded as audio only gives a black video area (this is upstream behaviour).

## Tests

- Unit tests (JVM only): `./gradlew testDebugUnitTest`. There are 9: `TextParserTest`,
  `CompositeBufferTest`, `ParserTest` (UMP parser for SABR). All pass. CI does not run them.
- There are no instrumented or UI tests. The `baselineprofile` module only generates the baseline
  profile and startup benchmarks. Playback is tested manually on the phone (see above).

## CI/CD and releases

- `.github/workflows/ci.yml`: builds a debug APK on every push/PR; on `master` it also refreshes the
  `nightly` pre-release.
- `.github/workflows/build-release.yml`: triggered by a `v*` tag (or manually with the `tag` input),
  runs `assembleRelease`, signs the APK and publishes a GitHub Release with generated notes. The
  asset is named `LibreTube-<tag>.apk`.
- Repository secrets (values cannot be read back): `ANDROID_RELEASE_SIGNING_KEY` (keystore as
  base64), `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`,
  `ANDROID_RELEASE_KEY_PASSWORD`. CI does not fail without them: the debug APK is then signed with
  the debug key.
- **The keystore lives outside the repository**: `~/.android-keys/libretube-release.jks` and
  `libretube-release.properties` (alias `libretube`, passwords). Keep a backup: without the key,
  updates over installed APKs will not install.
- **The repository is public. Never put the key into Variables, artifacts, commits or logs**:
  whoever gets it can sign APKs that update the installed app. Recover the key from the backup of
  the file, not from CI.
- To make a release: bump `versionCode`/`versionName` in `app/build.gradle.kts`, add
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, commit, push, then
  `git tag vX.Y.Z && git push origin vX.Y.Z`.
- The owner had to enable Actions in the fork manually; until that is done, pushes do not start CI.

## Code map (what matters for playback)

- `api/NewPipeMediaServiceRepository.kt`: `getStreams()`, all data comes from NewPipeExtractor (the
  libre-tube fork on JitPack, version in `gradle/libs.versions.toml`), PoToken via `api/poToken/`
  (WebView).
- `services/OnlinePlayerService.kt`: source selection, fallback, retries. Base class
  `services/AbstractPlayerService.kt` (the `onPlaybackError()` hook, the error toast, `onDestroy()`).
- `helpers/PlayerHelper.kt` / `helpers/DashHelper.kt`: builds the DASH manifest from direct URLs.
- `player/Sabr*`, `player/parser/*`, `player/manifest/*`: the custom SABR implementation.
- `ui/fragments/PlayerFragment.kt`: the player screen, the local version dialog, related videos.
- `ui/views/CustomExoPlayerView.kt`, `layout/custom_exo_player_view_template.xml`: wrapper around the
  Media3 `PlayerView`.

## Not verified and open questions

- The error the owner saw specifically on WiFi could not be reproduced as a network problem (the
  phone's WiFi is dual stack IPv4+IPv6). The causes turned out to be slow SABR and the service being
  destroyed. If playback errors on WiFi come back, capture logs and check which link of the
  DASH/SABR/HLS chain fails.
- Recordings of finished livestreams (VODs) were not tested specifically; they go through the normal
  DASH → SABR → HLS chain. Live streams are covered (see above).
- The report "after picking another video it loads forever" could not be reproduced on demand. Two
  causes are known: the local version dialog (see above) and dead pooled connections (item 11). The
  second one was caught in the device log while it happened.
- Scrolling related videos: after the revert to the upstream list (all cards at once inside a
  `ScrollView`) it may be slower again; the paged variant was removed to keep the diff small.
- The release build (R8) was checked by installing it and running the livestream, the fullscreen
  brightness swipe and a normal video; not every screen was exercised.
- A one-off job for the owner: the `Music` playlist of a LibreTube backup was sorted by song and then
  by upload date (hand made mapping from video titles to songs). That script is not part of the repo.

## Environment pitfalls

- The test phone runs GrapheneOS with several profiles (Owner = user 0, plus "Spyware" and "Work").
  `adb install` and `pm list packages` must be given `--user 0`, otherwise the app may land in
  another profile or look missing. The owner also reinstalls the original `com.github.libretube`
  (upstream 32.1) sometimes: when they report "Source error", first check with
  `adb shell pm list packages --user 0 | grep libretube` and `dumpsys package <pkg>` which app
  they are actually running.
- The phone is the owner's daily device. A picture-in-picture window of another app can cover the
  bottom-right "OK" button of the welcome screen; tap the left edge of the button instead
  (about x=816, y=2300 on the 1080x2400 screen) and avoid driving the phone while it is in use.
- A currently running livestream id for tests can be found with
  `curl -s "https://www.youtube.com/results?search_query=live+news&sp=EgJAAQ%253D%253D"`
  (grep `"videoId"`). Fixed ids of 24/7 streams go stale quickly.
- macOS: `sed -i` needs different syntax (BSD), so use Python or the editing tool for file changes.
  In zsh, quote globs such as `grep --include='*.kt'`.
