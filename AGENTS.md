# LibreTube (fork: playback fixes)

Android YouTube client based on [LibreTube](https://github.com/libre-tube/LibreTube) (upstream 32.1)
with fixed playback of videos and livestream recordings. Fork: `zamelek/LibreTube`, branch `master`.
There is no need to publish to Google Play, only APKs and GitHub releases.

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
4. **Related videos** below the player: a single vertical list in every orientation
   (`PlayerFragment`), cards are revealed 6 at a time while scrolling
   (`RELATED_STREAMS_PAGE_SIZE`), because the list lives inside a `ScrollView` and does not recycle
   its views.
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
13. **YouTube blocks the IP of the Wi-Fi ("Sign in to confirm you're not a bot").** The log shows
   `SignInConfirmNotBotException: YouTube probably temporarily blocked anonymous watch access with
   this IP` for the player request of the extractor (only the visionOS client, no fallback client).
   It is a per-IP decision of YouTube: the owner's home Wi-Fi IP gets it after a number of requests
   (also from the many test runs), mobile data does not. It is intermittent, some requests pass.
   This is the real reason for the original symptom "errors on Wi-Fi, works on 5G".
   - `getStreamsWithRetry()` retries this error 5 times with growing pauses (2, 4, 6, 8 s).
   - After 2 failed attempts `helpers/MobileDataFallback.kt` requests the cellular network
     (`ConnectivityManager.requestNetwork`) and binds the whole process to it
     (`bindProcessToNetwork`). It has to be the whole process, because the stream URLs only work for
     the IP that requested them. Pooled connections of the extractor are closed on the switch. The
     binding ends when the player service is destroyed or 20 minutes after it started, at the next
     video. Setting: "Use mobile data if Wi-Fi is blocked" (`use_mobile_data_when_blocked`, default on),
     needs the `CHANGE_NETWORK_STATE` permission. It costs mobile data, which is why it is a setting.
   - Test without a block: temporarily throw `SignInConfirmNotBotException` in
     `getStreamsWithRetry()` while `MobileDataFallback.isActive` is false; the log must show
     `all traffic is sent through the mobile network now`, and the sockets in `/proc/net/tcp6` of the
     app's uid must have the local address of `rmnet*` instead of `wlan0`.
   - What does not help: the extractor fork (`libre-tube/NewPipeExtractor`) had no newer commit than
     `3e863d7`; a WebView based player request is the next thing to try if mobile data is not enough.
14. **applicationId** = `com.github.libretube.fork` (debug: `...fork.debug`), so the fork installs
   next to the original app (different signature, so the fork can never update the original). The
   owner has since removed the original from the phone and uses only the fork.

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
| 32.1.8  | 80 | Bot check of YouTube on the Wi-Fi IP: longer retries, then the video is loaded over mobile data (setting) |

## Files changed compared to upstream

Base is upstream commit `b265e2d02`. Everything else in the tree is unchanged upstream code.

- `services/OnlinePlayerService.kt`, `services/AbstractPlayerService.kt`: source selection and
  fallback, `isSwitchingSource`, `getStreamsWithRetry()`, the `onPlaybackError()` hook.
- `ui/fragments/PlayerFragment.kt`: vertical paged related list, screenshot for both view types,
  forwards PiP changes to the player view.
- `ui/views/CustomExoPlayerView.kt`, `helpers/BrightnessHelper.kt`: brightness follows the shown
  window, PiP reset.
- `services/AbstractPlayerService.kt` (`checkForSegments()`), `ui/views/MarkableTimeBar.kt`:
  SponsorBlock seek handling and the colored segments on the seek bar.
- `res/layout/fragment_player.xml`, `res/layout-land/fragment_player.xml`: `texture_view` surface and
  the related list width.
- `api/ExternalApi.kt`: update check URL.
- `util/NewPipeDownloaderImpl.kt`: connection pool, HTTP/2 ping and retry on a fresh connection.
- `helpers/MobileDataFallback.kt` (new), `res/xml/general_settings.xml`, `AndroidManifest.xml`,
  `PlayerHelper.kt`, `PreferenceKeys.kt`, `strings.xml`: the mobile data fallback and its setting.
- `app/build.gradle.kts`: `applicationId`, version.
- `.github/workflows/ci.yml`, `.github/workflows/build-release.yml`: signing with the fork's secrets,
  nightly and tag releases.
- `fastlane/metadata/android/en-US/changelogs/73.txt` to `76.txt`, `AGENTS.md`, `CLAUDE.md`.

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
- Scrolling related videos during playback gives about 5-6% janky frames on a debug build; release is
  better, but the cause was not fully explained.
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
