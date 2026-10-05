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
8. **applicationId** = `com.github.libretube.fork` (debug: `...fork.debug`), so the fork installs
   next to the original app (different signature, never uninstall the original).

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
- The report "after picking another video it loads forever" could not be reproduced: three videos in
  a row switched fine. The likely cause is the local version dialog (the owner confirmed they had
  downloaded the audio first and the dialog appeared).
- Scrolling related videos during playback gives about 5-6% janky frames on a debug build; release is
  better, but the cause was not fully explained.
- Brightness swipe (left half of the screen in fullscreen; the right half is volume, the indicator is
  drawn on the opposite side): checked on the phone, the window brightness changed
  (`dumpsys window windows`, `sbrt=` of the app window). The owner reported it as not working, which
  could not be reproduced; it works only in fullscreen and only when the "swipe controls" setting is on.
- The release build (R8) was checked by installing and launching it, without running every scenario.

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
