# AutoVLESS Android MVP v0.9

Android-приложение для ручной загрузки VLESS-конфигов из `zieng2/wl`, фильтрации невалидных узлов, проверки скорости и запуска VPN через sing-box/libbox.

## Что есть в v0.9

- Только Android.
- Ручная кнопка `Обновить список`.
- Загрузка:
  - `https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt`
  - `https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt`
- Парсинг строк `vless://`.
- Нормализованный `nodeKey` через SHA-256.
- Локальные списки:
  - `active_nodes`
  - `invalid_nodes`
- При новом скачивании конфиги из `invalid_nodes` не попадают в `active_nodes`.
- Кнопка `Проверить лучший конфиг`.
- Быстрый TCP-check перед speed-check.
- Speed-check через временный sing-box `mixed` proxy на `127.0.0.1`.
- Порог скорости: `>= 500 КБ/с`.
- Если конфиг мертв или скорость ниже 500 КБ/с:
  - он удаляется из `active_nodes`
  - записывается в `invalid_nodes`
- Генерация VPN-конфига sing-box с `tun` inbound.
- `VpnService` + foreground notification.
- Runtime-обертка `LibboxRuntime`, которая вызывает `libbox.Libbox` через reflection.


## Изменения v0.9

- Добавлена поддержка VLESS REALITY из URI-параметров `pbk`, `sid`, `spx`.
- Улучшена генерация sing-box config для `security=reality`.
- Speed-check теперь проверяет несколько test URL и берет лучший результат.
- Проверка больше не ограничена первыми 80 конфигами - идет по всему active-списку до нахождения подходящего.
- Финальный статус показывает лучший найденный результат и статистику DEAD/SLOW/FAIL.

Если в старой версии хорошие конфиги уже попали в `invalid`, нажми `Очистить invalid`, потом `Обновить список`, потом `Проверить лучший конфиг`.

## Важное ограничение

В архив не включен настоящий `libbox.aar`.

Причина: `libbox.aar` нужно собирать из sing-box под Android отдельно. Проект уже подготовлен так, что если положить файл сюда:

```text
app/libs/libbox.aar
```

Gradle автоматически подключит его в APK.

Без `app/libs/libbox.aar` приложение собирается, но:

- speed-check через VLESS не запустится
- VPN не запустится
- UI покажет `libbox: не найден`

## Как собрать libbox.aar

На компьютере с Go + Android SDK + Android NDK:

```bash
cd AutoVLESS
./scripts/build_libbox_android.sh
```

Потом найти файл:

```bash
find sing-box -name 'libbox.aar' -type f
```

И скопировать:

```bash
cp /path/to/libbox.aar app/libs/libbox.aar
```

## Как собрать APK

Вариант через Android Studio:

```text
File -> Open -> AutoVLESS
Build -> Build Bundle(s) / APK(s) -> Build APK(s)
```

Вариант через установленный Gradle/Android SDK:

```bash
gradle assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Текущие настройки

```text
applicationId: com.autovless.app
minSdk: 23
targetSdk: 35
versionName: 0.2.0
```

## Основной цикл приложения

```text
[Обновить список]
  -> скачать VLESS
  -> удалить дубли
  -> отфильтровать invalid
  -> заменить active_nodes

[Проверить лучший конфиг]
  -> взять active_nodes
  -> TCP-check
  -> временный sing-box mixed proxy
  -> download-test 1 МБ
  -> если скорость < 500 КБ/с: active -> invalid
  -> если скорость >= 500 КБ/с: запуск VpnService + sing-box tun
```

## Следующий этап

- Проверить API конкретного `libbox.aar`, который будет собран у тебя.
- При необходимости подогнать `LibboxRuntime` под точные имена методов в этой версии libbox.
- Добавить health-check текущего VPN и автопереключение при падении.

## Сборка APK через GitHub Actions

В проект добавлен workflow:

```text
.github/workflows/build-android.yml
```

Он автоматически:

1. ставит Java, Go, Android SDK/NDK и Gradle;
2. клонирует `SagerNet/sing-box`;
3. собирает `libbox.aar`;
4. кладет его в `app/libs/libbox.aar`;
5. собирает `app-debug.apk`;
6. публикует APK как artifact `AutoVLESS-debug-apk`.

Как запустить:

```text
GitHub -> твой репозиторий -> Actions -> Build AutoVLESS APK -> Run workflow
```

После окончания сборки APK будет в разделе artifacts.

## Локальная сборка libbox.aar

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
./scripts/build_libbox_android.sh
```

Результат:

```text
app/libs/libbox.aar
```


## v1.0.0

- Gradle теперь падает, если `app/libs/libbox.aar` не попал перед сборкой APK.
- GitHub Actions проверяет содержимое `libbox.aar` и наличие `libgojni.so` в APK.
- Runtime ищет оба известных package name: `libbox.*` и `io.nekohasekai.libbox.*`.


## v1.4.5 changes

- Fixed safe tester + sing-box 1.10 libbox generation: removed legacy inbound `sniff` fields from mixed/tun inbounds.
- `CommandServer` startup remains enabled; config decode should now pass the previous `legacy inbound fields` failure.
- App log now uses `BuildConfig.VERSION_NAME` instead of a hardcoded version.

## v1.4.2 changes

- Runtime переключен с отсутствующего `NewService` на актуальный `CommandServer.startOrReloadService`.
- Оставлен fallback на старый `NewService`, если будет собран старый libbox.
- Диагностика теперь пишет `CommandServer created/start/startOrReloadService`.

## v1.4 changes

- Fixed VLESS -> sing-box mapping for zieng2/wl subscriptions.
- REALITY: pbk/sid/spx are mapped to tls.reality.public_key/short_id/spider_x.
- TCP VLESS no longer creates fake transport type tcp.
- WS, gRPC and HTTPUpgrade are mapped separately.
- XHTTP configs are filtered out because this libbox/sing-box build does not support that transport path.
- Removed separate raw TCP pre-check; testing now starts sing-box and performs URL test + download speed test through the actual VLESS outbound.
- After installing this version, clear invalid and reload the list.


## Диагностический лог v1.4

После проверки нажмите кнопку `Скопировать все из консоли` в приложении и отправьте текст лога разработчику. Лог хранится локально в файле `autovless_debug.log` и содержит запуск libbox, проверку локального proxy, URL-test и download-test.

### v1.4.7

- Fixed Android process crash during the first speed-check HTTP request.
- `PlatformInterface.FindConnectionOwner()` now returns a safe unknown UID instead of `null`, because gomobile/libbox can segfault when a primitive UID method returns null through a dynamic proxy.
- Updated UI note to match the current safe speed-check build.

### v1.4.9

- Added an in-app live console under the buttons.
- Added button `Скопировать все из консоли` to copy the full diagnostic log plus `libbox-stderr.log`.
- Added button `Остановить проверку` to request stopping the current check loop.
- Duplicate `Проверить лучший конфиг` taps are now ignored while a check is already running.
- Speed-check now logs `URL_TEST_TRY` and `DOWNLOAD_TRY`, so slow checks are visible in the console.
