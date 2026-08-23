# NullcoreGram

![Views](https://visitor-badge.laobi.icu/badge?page_id=NullCoreDeveloper.Nullcoregram)

[English version](README-EN.md)

NullcoreGram — это усиленный, ориентированный на приватность неофициальный клиент Telegram, основанный на [NekoX](https://github.com/NekoX-Dev/NekoX) / Nagram. Он разработан с прицелом на максимальную безопасность и анонимность.

- **Репозиторий на GitHub**: <https://github.com/NullCoreDeveloper/NullcoreGram>
- **Релизы**: <https://github.com/NullCoreDeveloper/NullcoreGram/releases>

## 🛡️ Приватность и безопасность (Эксклюзивы NullcoreGram)

1. **Абсолютный ноль телеметрии**: Sentry, Firebase Crashlytics и все остальные фоновые SDK для сбора аналитики были полностью вырезаны из исходного кода. Ваши данные остаются только на вашем устройстве.
2. **Ghost Mode для каждого аккаунта**: Продвинутый Режим Невидимки (Скрытие статуса прочтения, Скрытие статуса набора текста) теперь можно настраивать индивидуально для каждого авторизованного аккаунта, а не глобальным переключателем.
3. **Безопасный автоматизированный CI/CD**: Пайплайн сборки использует GitHub Actions с восстановлением ключа подписи прямо в оперативной памяти (in-memory), что гарантирует, что релизные ключи никогда не попадут в репозиторий.
4. **Независимая идентичность**: Полный визуальный ребрендинг и независимые пути конфигурации гарантируют, что NullcoreGram работает в полной изоляции от официального Telegram и других форков.

----

## 🚀 Унаследованные функции (от Nagram / NekoX)

- **Поддержка Proxy**: Встроенные VMess, Shadowsocks, SSR, Trojan-GFW, IPv6 MTProxy.
- **Безлимитные аккаунты**: Авторизуйтесь в стольких аккаунтах, в скольких вам нужно.
- **Продвинутое управление чатами**: Объединение сообщений, редактируемый стиль текста, принудительное копирование, инверсия ответа, отмена/повтор действий (undo/redo), предпросмотр чата со скроллом.
- **Обход ограничений**: Опция игнорирования ограничений контента, существующих только для Android (обход NSFW-фильтра).
- **Кастомизация**: Кастомные наборы эмодзи, настраиваемые пути к кэшу, меню в стиле Telegram X и темы Material Design.
- **Переводчик**: Полный перевод внутри InstantView, конвертер OpenCC Chinese, поддержка переводчиков Google/Yandex.

----

## 🛠️ Инструкция по сборке

**ВНИМАНИЕ: Пользователям Windows рекомендуется использовать виртуальную машину с Linux (например, WSL2) или установить Linux второй системой.**

### Окружение

- Дистрибутив Linux, основанный на Debian или Arch Linux, либо macOS
- Нативные инструменты: `gcc` `go` `make` `cmake` `ninja` `yasm`
- Android SDK: `build-tools;33.0.0` `platforms;android-33` `ndk;27.2.12479018` `cmake;3.18.1` `cmake;3.22.1` 

### Шаги для сборки

1. Клонируйте подмодули:
   ```shell
   git submodule update --init --recursive
   ```
2. Соберите нативные зависимости:
   ```shell
   ./run init libs
   ```
3. Соберите сторонние библиотеки и нативный код: 
   ```shell
   ./run libs native
   ```
4. Настройте `local.properties`:
   Заполните поля `TELEGRAM_APP_ID` и `TELEGRAM_APP_HASH` (их можно получить на [Telegram Developer](https://my.telegram.org/auth)), а также впишите свои `KEYSTORE_PASS`, `ALIAS_NAME` и `ALIAS_PASS`.

5. Скомпилируйте приложение с помощью Gradle:
   ```shell
   ./gradlew assembleRelease
   ```

----

## ☁️ Сборка через GitHub Actions (CI/CD)

NullcoreGram настроен для безопасной облачной сборки с помощью GitHub Actions. Чтобы запустить её в своем форке:

1. **Сгенерируйте Keystore**:
   Создайте релизный ключ (release keystore), но **не** коммитьте его в репозиторий.

2. **Закодируйте секреты**:
   Преобразуйте ваш keystore и файл `local.properties` в base64:
   ```bash
   base64 -w 0 TMessagesProj/release.keystore
   base64 -w 0 local.properties
   ```

3. **Добавьте секреты в репозиторий**:
   Перейдите в настройки репозитория на GitHub (Settings -> Secrets and variables -> Actions) и добавьте:
   - `KEYSTORE_BASE64`: Вывод команды base64 для ключа (без знака `%` на конце).
   - `LOCAL_PROPERTIES`: Вывод команды base64 для файла конфигурации.
   - *(Опционально)* `HELPER_BOT_TOKEN` и `HELPER_BOT_TARGET`: Для автоматической отправки скомпилированного APK вам в Telegram.

4. **Запушьте в Main**:
   Любой push в ветку `main` автоматически запустит чистую, подписанную продакшен-сборку.

----

## 🤝 Локализация и Благодарности

NullcoreGram построен на базе невероятной работы open-source сообщества. Особая благодарность проектам:

- [NekoX](https://github.com/NekoX-Dev/NekoX)
- [Nagram](https://github.com/NextAlone/Nagram)
- [Nekogram](https://gitlab.com/Nekogram/Nekogram)
- [Nullgram](https://github.com/qwq233/Nullgram)
- [TeleTux](https://github.com/TeleTux/TeleTux)
- [OwlGram](https://github.com/OwlGramDev/OwlGram)
