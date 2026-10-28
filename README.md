# TweakStore

The tweak store for [derealization](https://github.com/novaca1n/derealization). derealization installs it while
patching; it lets you browse, install, update and remove tweaks from the official source and from any source you add.

- **Sources** — the official one ([novaca1n/tweaks](https://github.com/novaca1n/tweaks)) plus any you add with
  **Add source**: a link to a `repo.json`, or just `owner/repo` for a GitHub repository with `repo.json` in `main`.
  Links like `tweakstore://add?url=owner/repo` open the dialog pre-filled.
- **Install** — downloads the APK, checks its SHA-256 and package name against the source, installs it with root.
  Modules (`"type": "xposed"`) are also enabled in Vector with their scope, and the affected apps are restarted.
- **Updates** — the Installed tab shows what has a newer version.

Material 3 Expressive, follows your wallpaper colors. English and Russian.

## Make your own source

A source is a static `repo.json` anywhere on HTTPS — a GitHub repo is enough. Put APKs in GitHub Releases (or next to
`repo.json`); relative paths are resolved against the `repo.json` address.

```json
{
  "format": 1,
  "name": "My tweaks",
  "description": {"en": "Things I made", "ru": "То, что я сделал"},
  "maintainer": "me",
  "tweaks": [
    {
      "id": "com.example.mytweak",
      "name": "My tweak",
      "author": "me",
      "summary": {"en": "One line shown in the list", "ru": "Строка для списка"},
      "description": {"en": "Longer text for the tweak page.", "ru": "Текст для страницы твика."},
      "icon": "icons/mytweak.png",
      "screenshots": ["screenshots/mytweak-1.png"],
      "type": "xposed",
      "scope": ["com.android.systemui"],
      "category": "lockscreen",
      "license": "GPL-3.0",
      "source": "https://github.com/me/mytweak",
      "requires": {"derealization": 50, "devices": ["galaga"], "android": 36},
      "versions": [
        {
          "name": "1.0",
          "code": 1,
          "apk": "https://github.com/me/mytweak/releases/download/v1.0/mytweak-1.0.apk",
          "sha256": "…",
          "size": 123456,
          "changelog": {"en": "First release", "ru": "Первый выпуск"},
          "date": "2026-10-28"
        }
      ]
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `id` | Package name of the APK. The store refuses to install an APK with a different one. |
| `type` | `app` — a regular app; `xposed` — a module, enabled in Vector with `scope` after install. |
| `scope` | Packages the module hooks (`com.android.systemui`, `com.nothing.launcher`, …). SystemUI is restarted only after the user agrees. |
| `requires` | All optional. `derealization` — minimum version code; `devices` — `ro.product.device` values (case-insensitive); `android` — minimum API level. |
| `versions` | The highest `code` is offered. `sha256` is strongly recommended — the store refuses a mismatching file. |
| text fields | A plain string, or `{"en": …, "ru": …}`; the phone's language is used, then English. |

Icons: square PNG, 256×256 or larger. Screenshots: portrait phone screenshots.

## Build

```sh
./build.sh assembleRelease
```

JDK 21, Android SDK 37. Release signing reads `../keys/keystore.properties` (or `$RELEASE_KEYS`).

## License

GPL-3.0 — see [LICENSE](LICENSE). Icons: Material Symbols (Apache-2.0).
