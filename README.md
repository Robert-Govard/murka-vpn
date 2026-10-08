# Мурка VPN

Клиент «Мурка VPN»: обычные подписки Remnawave (Xray: VLESS Reality, Trojan) и аварийный режим olcRTC через WB Stream в одном приложении. Android, Windows, macOS.

| Папка | Что это |
|---|---|
| `olcbox/` | приложение (форк [alananisimov/olcbox](https://github.com/alananisimov/olcbox), MIT), ветка `murka/main` |
| `olcrtc/` | ядро olcRTC (форк openlibrecommunity/olcrtc): ключ на пользователя, `status.file` |
| `murka-core/` | общая Go-библиотека для Android/iOS: olcRTC + Xray-core в одном gomobile-биндинге |

Папки — `git subtree` из рабочих репозиториев; обновление: `git subtree pull --prefix=<папка> <путь-к-репозиторию> <ветка>`.

## Сборка

GitHub Actions (`.github/workflows/build.yml`) на каждый push в `main`: тесты, Android APK (универсальный и arm64), Windows EXE/MSI/ZIP, macOS DMG — в артефактах запуска. Тег `vX.Y.Z` создаёт релиз с этими файлами.

Секреты репозитория для подписи Android: `ANDROID_RELEASE_KEYSTORE_BASE64`, `ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, `ANDROID_RELEASE_KEY_PASSWORD`. Оригинал ключа хранится у владельца; без него обновления Android-приложения невозможны.

Локально (macOS): JDK 21, Go 1.27, Android SDK 37 + NDK 28.2, `gomobile`; базы Xray — `olcbox/scripts/fetch-xray-assets.sh`. `ndk-build` не работает, если в пути к проекту есть пробелы.
