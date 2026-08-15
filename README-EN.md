# NullcoreGram

NullcoreGram is a hardened, privacy-focused Telegram client based on [NekoX](https://github.com/NekoX-Dev/NekoX) / Nagram, designed for maximum security and anonymity.

- **GitHub Repository**: <https://github.com/NullCoreDeveloper/NullcoreGram>
- **Releases**: <https://github.com/NullCoreDeveloper/NullcoreGram/releases>

## 🛡️ Privacy & Security Features (NullcoreGram Exclusives)

1. **Absolute Zero Telemetry**: Sentry, Firebase Crashlytics, and all other background analytics SDKs have been completely eradicated from the source code. Your data stays on your device.
2. **Per-Account Ghost Mode**: Advanced Ghost Mode (Hide Read Status, Hide Typing Status) can now be configured individually for each logged-in account, rather than a global switch.
3. **Secure Automated CI/CD**: The compilation pipeline uses GitHub Actions with in-memory keystore reconstruction, ensuring release signatures are never exposed in the repository.
4. **Independent Identity**: Full surface rebranding and independent configuration paths ensure NullcoreGram runs in complete isolation from official Telegram or other forks.

----

## 🚀 Inherited Features (from Nagram / NekoX)

- **Proxy Support**: Built-in VMess, Shadowsocks, SSR, Trojan-GFW, IPv6 MTProxy.
- **Unlimited Accounts**: Login with as many accounts as you need.
- **Advanced Chat Management**: Combine messages, editable text style, forced copy, invert reply, undo/redo, scrollable chat preview.
- **Bypass Restrictions**: Option to ignore Android-only content restrictions (NSFW filter bypass).
- **Customization**: Custom emoji packs, custom cache directories, Telegram X style menus, and Material Design themes.
- **Translation**: Full InstantView translation, OpenCC Chinese Convert, Google/Yandex Translate support.

----

## 🛠️ Compilation Guide

**NOTE: For Windows users, please consider using a Linux VM (such as WSL2) or dual booting.**

### Environment

- Linux distribution based on Debian or Arch Linux, or macOS
- Native tools: `gcc` `go` `make` `cmake` `ninja` `yasm`
- Android SDK: `build-tools;33.0.0` `platforms;android-33` `ndk;27.2.12479018` `cmake;3.18.1` `cmake;3.22.1` 

### Build Steps

1. Checkout submodules:
   ```shell
   git submodule update --init --recursive
   ```
2. Build native dependencies:
   ```shell
   ./run init libs
   ```
3. Build external libraries and native code: 
   ```shell
   ./run libs native
   ```
4. Configure `local.properties`:
   Fill out `TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH` (from [Telegram Developer](https://my.telegram.org/auth)), along with your `KEYSTORE_PASS`, `ALIAS_NAME`, and `ALIAS_PASS`.

5. Build with Gradle:
   ```shell
   ./gradlew assembleRelease
   ```

----

## ☁️ Compilation with GitHub Actions (CI/CD)

NullcoreGram is configured for secure cloud builds using GitHub Actions. To set this up in your fork:

1. **Generate a Keystore**:
   Create a release keystore, but **do not** commit it to the repository.

2. **Encode Secrets**:
   Encode your keystore and `local.properties` to base64:
   ```bash
   base64 -w 0 TMessagesProj/release.keystore
   base64 -w 0 local.properties
   ```

3. **Add Repository Secrets**:
   Go to your GitHub Repository Settings -> Secrets and variables -> Actions, and add:
   - `KEYSTORE_BASE64`: The output of the keystore base64 command (without the trailing `%`).
   - `LOCAL_PROPERTIES`: The output of the properties base64 command.
   - *(Optional)* `HELPER_BOT_TOKEN` & `HELPER_BOT_TARGET`: For automated Telegram delivery of the compiled APK.

4. **Push to Main**:
   Any push to the `main` branch will automatically trigger a clean, signed production build.

----

## 🤝 Localization & Thanks

NullcoreGram is built upon the incredible work of the open-source community. Special thanks to:

- [NekoX](https://github.com/NekoX-Dev/NekoX)
- [Nagram](https://github.com/NextAlone/Nagram)
- [Nekogram](https://gitlab.com/Nekogram/Nekogram)
- [Nullgram](https://github.com/qwq233/Nullgram)
- [TeleTux](https://github.com/TeleTux/TeleTux)
