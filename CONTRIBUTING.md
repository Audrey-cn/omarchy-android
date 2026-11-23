# Contributing to Omarchy Android

Fork it, make it yours, and share useful improvements. Themes, device compatibility reports, app installers, translations and focused fixes are all welcome. For a larger change—such as another distribution or a new runtime—open a [feature request](https://github.com/Audrey-cn/omarchy-android/issues/new/choose) first so we can agree on scope and maintenance needs. You can experiment freely in your own fork.

## Find the right part of the project

| Change | Location |
| --- | --- |
| Desktop commands, menus, themes, Sway and Fcitx5 | `core/` |
| Android UI, floating controls, IME bridge and initial installer | `apps/android/` |
| Root chroot and external Termux PRoot integration | `runtime/`, `install.sh` |
| External Termux:X11 input companion | `apps/deck/` |
| Packaging, source locks and release verification | `scripts/`, `.github/workflows/` |
| User guides and device evidence | `docs/`, `README.md`, `README.zh-CN.md` |

Read the [architecture](docs/ARCHITECTURE.md), [development guide](docs/DEVELOPMENT.md) and [maintenance handbook](docs/MAINTENANCE.md) before changing runtime or update behavior.

## Work in a fork

1. Fork [Audrey-cn/omarchy-android](https://github.com/Audrey-cn/omarchy-android), clone your fork and create a focused branch from `main`.
2. Make one coherent change. Keep English and Chinese root READMEs aligned when changing shared user-facing information.
3. Run the checks below and any build or device check relevant to your change.
4. Open a PR against upstream `main`. Explain the problem, resulting behavior and validation. Link an issue when there is one.

PRs and fork builds use temporary signing; they do not receive the upstream persistent signing key. A differently signed APK cannot replace an installed official APK. Read the [version and signing guide](docs/VERSIONS.md) before switching builds, and keep your desktop data backed up. GitHub Actions artifacts are test outputs; publishing a fork release is a separate step.

For personal customization, start with themes and configuration in `core/`. Keep credentials and personal settings outside version control. A fork targeting a different distribution should document its own requirements, package sources and supported devices; the upstream default remains Ubuntu 24.04 ARM64.

## Check your change

Run from the repository root:

```bash
git diff --check
python3 -m unittest discover -s tests -v
```

For shell changes, run `bash -n` on the affected scripts. For Android changes, use the documented cloud workflow or a configured local Android toolchain:

```bash
cd apps/android
./gradlew testDebugUnitTest assembleDebug
```

Pure documentation changes need link, spelling and rendering checks. Runtime changes need evidence from the affected route: include device model, Android/vendor version, root status, APK/image/component versions, reproduction steps and observed results. Preserve failures and state what you did not test. A build passing does not establish that a desktop starts or input works on a device.

## Keep changes reviewable

- Describe the concrete trigger and expected result. For UI changes, attach a screenshot or short recording when useful.
- Keep app updates, guest component updates and new-install images separate. Normal app startup must preserve the user's guest; use explicit component update and recovery paths for guest changes.
- Preserve update rollback behavior, checksum checks and existing personal configuration. Test with isolated data rather than clearing a working desktop.
- Never commit APKs, rootfs archives, signing keys, API tokens, account databases or personal backups. Redact private data from logs and screenshots.
- Keep original copyright and license notices. The Android application retains Tiny Container's GPL-3.0-or-later terms; other repository components retain their existing licenses. Follow [LICENSES.md](LICENSES.md) when redistributing a fork.

## Report a bug or device result

Use [the issue forms](https://github.com/Audrey-cn/omarchy-android/issues/new/choose) and search existing issues first. Text steps and logs are useful; a fresh installation or a video is not required to report a problem. For compatibility reports, distinguish a tested result from an assumption and include both working and failing paths. Please be patient and constructive with other contributors.
