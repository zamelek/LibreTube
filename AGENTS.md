# LibreTube (форк: починка воспроизведения)

Android-клиент YouTube на базе [LibreTube](https://github.com/libre-tube/LibreTube) (апстрим 32.1) с
исправленным воспроизведением видео и записей стримов. Форк: `zamelek/LibreTube`, ветка `master`.
Публикация в Google Play не нужна, только APK и релизы на GitHub.

## Что было сломано и что сделано

Симптомы владельца: обычные видео не открывались (бесконечная крутилка), записи стримов на WiFi
часто падали с ошибкой, на мобильной сети (5G) всё работало. Само приложение (дизайн, экстрактор,
PoToken) при этом рабочее, проблема была в выборе источника воспроизведения и в отрисовке.

1. **Источник воспроизведения** (`services/OnlinePlayerService.kt`). Раньше всегда использовался
   SABR (`player/Sabr*`, `player/parser/SabrClient.kt`): отдельный блокирующий запрос на каждый
   сегмент, скорость закачки около 1x от реального времени, буфер не копится, плеер вечно
   буферизуется. Теперь порядок такой: **DASH (прямые ссылки) → SABR → HLS**
   (`getStreamSources()`). Если источник падает с ошибкой, `onPlaybackError()` переключает плеер на
   следующий с той же позиции.
2. **Гонка с `STATE_IDLE`.** После ошибки ExoPlayer переходит в IDLE, а сервис в этом состоянии
   звал `onDestroy()`. При переключении источника приходит ещё и устаревший IDLE уже после
   `prepare()`, когда `playerError == null`. Поэтому есть флаг `isSwitchingSource`, он снимается
   только по `STATE_READY` (не по BUFFERING: `prepare()` синхронно ставит BUFFERING раньше, чем
   приходит устаревший IDLE).
3. **Крошечный кадр.** `SurfaceView` рисовал видео размером с марку в левом верхнем углу (Pixel 8a и
   эмулятор, Android 16/17, внутри `MotionLayout`). Обходное решение: `app:surface_type="texture_view"`
   в `layout/fragment_player.xml` и `layout-land/fragment_player.xml`. Скриншот кадра в
   `PlayerFragment` поддерживает оба типа (`TextureView.bitmap` / `PixelCopy`). Корневую причину в
   `SurfaceView` не искали.
4. **Рекомендации** под плеером: один вертикальный список во всех ориентациях
   (`PlayerFragment`), карточки подгружаются по 6 штук при прокрутке
   (`RELATED_STREAMS_PAGE_SIZE`), потому что список лежит внутри `ScrollView` и не переиспользует
   view.
5. **Повторы.** `getStreamsWithRetry()` делает до 3 попыток получить данные видео, иначе одна
   сетевая ошибка оставляла интерфейс с вечным спиннером.
6. **applicationId** = `com.github.libretube.fork` (debug: `...fork.debug`), чтобы ставиться рядом с
   оригиналом (другая подпись, оригинал не удалять).

## Сборка и запуск

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # неподписанный, R8 включён; подпись делает CI
```

- В CI используется JDK 17, локально работает JDK 21 из brew (`openjdk@21`).
- Gradle сам докачал Android SDK Platform 36 в `~/Library/Android/sdk` (в SDK была только 37).
- Первый запуск debug-сборки показывает экран приветствия: выбрать «None», нажать OK.
- Тестовое устройство: Pixel 8a по USB (serial `49261JEKB11306`), есть эмулятор `a17`
  (`~/Library/Android/sdk/emulator/emulator -avd a17`). При двух устройствах всегда `adb -s <serial>`.
  USB у телефона часто отваливается: длинные записи логов запускать на самом устройстве
  (`adb shell "nohup logcat --pid=<PID> -v time -f /sdcard/x.log &"`), потом `adb pull`.
- Открыть видео для теста: `adb shell am start -a android.intent.action.VIEW -d
  "https://www.youtube.com/watch?v=<id>" com.github.libretube.fork.debug`.
- Замер рывков: `adb shell dumpsys gfxinfo <пакет> reset`, прокрутка через `input swipe`, затем
  `dumpsys gfxinfo <пакет>`. Debug-сборка заметно медленнее release (99-й перцентиль кадра
  ~120-150 мс против ~50 мс), сравнивать нужно на release.
- Как проверить фолбэк: временно подменить DASH-источник в `setStreamSource()` на заведомо
  недоступный URL и убедиться, что в логе появилось `source DASH failed, trying the next one`, а
  SABR начал грузить сегменты (`SabrStream: getNextSegment`). Временный код потом убрать.
- Диалог «Локальная версия доступна» (`PlayOfflineDialog`) появляется у видео, которые уже
  скачаны. Пока на него не ответить, плеер стоит с крутилкой, а «Yes» для видео, скачанного только
  как аудио, даёт чёрное видеополе (так и задумано апстримом).

## CI/CD и релизы

- `.github/workflows/ci.yml`: на каждый push/PR собирает debug-APK; на `master` обновляет
  pre-release `nightly`.
- `.github/workflows/build-release.yml`: запускается тегом `v*` (или вручную с входом `tag`),
  собирает `assembleRelease`, подписывает, публикует GitHub Release с автоматическими заметками.
  Файл называется `LibreTube-<тег>.apk`.
- Секреты репозитория (значения не читаются обратно): `ANDROID_RELEASE_SIGNING_KEY` (keystore в
  base64), `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEYSTORE_PASSWORD`,
  `ANDROID_RELEASE_KEY_PASSWORD`. CI без них не падает: debug-APK подписывается debug-ключом.
- **Keystore лежит вне репозитория**: `~/.android-keys/libretube-release.jks` и
  `libretube-release.properties` (alias `libretube`, пароли). Нужна резервная копия: без ключа
  обновления поверх установленных APK не встанут.
- **Репозиторий публичный, ключ нельзя класть в переменные (Variables), артефакты, коммиты или
  логи**: кто его получит, сможет подписывать APK, которые обновят установленное приложение.
  Для восстановления ключа пользоваться резервной копией файла, не CI.
- Чтобы выпустить релиз: поднять `versionCode`/`versionName` в `app/build.gradle.kts`, добавить
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, закоммитить, запушить и
  `git tag vX.Y.Z && git push origin vX.Y.Z`.
- Actions в форке пришлось включить вручную владельцу, пока это не сделано, push не запускает CI.

## Карта кода (то, что важно для воспроизведения)

- `api/NewPipeMediaServiceRepository.kt`: `getStreams()`, все данные берутся из NewPipeExtractor
  (форк libre-tube на JitPack, версия в `gradle/libs.versions.toml`), PoToken через
  `api/poToken/` (WebView).
- `services/OnlinePlayerService.kt`: выбор источника, фолбэк, повторы. Базовый класс
  `services/AbstractPlayerService.kt` (хук `onPlaybackError()`, тост об ошибке, `onDestroy()`).
- `helpers/PlayerHelper.kt` / `helpers/DashHelper.kt`: сборка DASH-манифеста из прямых ссылок.
- `player/Sabr*`, `player/parser/*`, `player/manifest/*`: собственная реализация SABR.
- `ui/fragments/PlayerFragment.kt`: экран плеера, диалог локальной версии, рекомендации.
- `ui/views/CustomExoPlayerView.kt`, `layout/custom_exo_player_view_template.xml`: обёртка над
  Media3 `PlayerView`.

## Что не проверено и известные вопросы

- Ошибка, которая была именно на WiFi владельца, у меня не воспроизвелась как сетевая (на
  телефоне WiFi двойной стек IPv4+IPv6). Причиной в итоге были медленный SABR и падение сервиса.
  Если на WiFi снова будет ошибка воспроизведения, снять логи и смотреть, какая из цепочки
  DASH/SABR/HLS отваливается.
- Записи стримов (livestream VOD) целенаправленно не тестировались.
- Жалоба «после выбора другого видео вечная крутилка» не воспроизвелась: три видео подряд
  переключались нормально; вероятная причина - диалог локальной версии (владелец подтвердил, что
  перед этим скачивал аудио и диалог появлялся).
- Прокрутка рекомендаций во время воспроизведения на debug-сборке даёт около 5-6% рваных кадров,
  на release лучше, но до конца причина не выяснена.
- Релизная сборка (R8) проверялась установкой и запуском без полного прогона сценариев.

## Подводные камни окружения

- macOS: `sed -i` требует другой синтаксис (BSD), для правок использовать Python или инструмент
  редактирования. В zsh `grep --include=*.kt` надо брать в кавычки.
