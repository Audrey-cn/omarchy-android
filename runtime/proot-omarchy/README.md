# Omarchy PRoot read-only runtime v1

This directory records the origin of the independent Omarchy executable and its matching loader pair. It does not replace Tiny's bundled classic/latest executables or old loaders.

The three native files are packaged in `apps/android/app/src/main/jniLibs/arm64-v8a/` under independent names. Android extracts them through the existing `useLegacyPackaging=true` setting; startup executes the installed `nativeLibraryDir` files. They must not be copied into guest assets or app-writable storage as a native execution mechanism. The ARM32 loader intentionally remains an ARM ELF inside this package's ARM64 native directory, following the existing Tiny packaging convention.

## Provenance

- Successful build: [Actions run 45130596518](https://github.com/Audrey-cn/omarchy-android/actions/runs/45130596518).
- Artifact ID: `14797951135`; ZIP SHA-256: `b961879e2a588b54a3ed38926a39b1e369212189b6f80c205e6584bec549701a`.
- PR head: `39d6c6864593ed7cc49d96bffce37020cb23d736`; actual merge checkout: `8ad287d91820383aa3c851b258f8bffa23b34a44`.
- Tiny fork revision: `2919ebdcb865b192490f8c4ca1957a621f13d95b` from [tiny-computer/proot-termux](https://github.com/tiny-computer/proot-termux/tree/2919ebdcb865b192490f8c4ca1957a621f13d95b).
- Exact Termux fix: [a179d3e8](https://github.com/termux/proot/commit/a179d3e8a4e045aaa1fb8cc3284f23509d96d353); the common build compatibility patch adds only the missing `<string.h>` declaration in `ashmem_memfd.c`.
- talloc `2.5.0` and its target libreplace objects are statically linked. NDK r27d `27.3.13750724`, Android API 28.
- Original receipts, compiler identity, input hashes and source diff are preserved beside this README. Full source URLs, immutable revisions, archive hashes and the three native hashes are in `manifest.json`.

| Packaged file | Bytes | SHA-256 |
| --- | ---: | --- |
| `libproot-omarchy-readonly.so` | 339544 | `4dbc0c56480812f3ed0e9a8c39e85f759e8b4eba1b57a5d2a1d0e0d584a62eda` |
| `libproot-omarchy-loader-aarch64.so` | 6008 | `43161d1633dd9e174e362ddb335503a7456e2e76bbed96e3095964d9fcb7b880` |
| `libproot-omarchy-loader-arm.so` | 5952 | `b55fe7372eb290c53c4bbf3a9135da820023ba826e28b6c49737a1c14f9b824c` |

The recipe is `experiments/proot-readonly/build-android.sh`, with pinned inputs in `experiments/proot-readonly/sources.env`, the original upstream patch and the build compatibility patch. At import, all recipe files were checked byte-for-byte against the successful artifact. To rebuild on Ubuntu x86_64, follow `experiments/proot-readonly/README.md`; the script validates downloads and creates its own fresh scratch tree. Fixed inputs and an executable rebuild recipe are provided; a second build has not established bit-for-bit reproducibility.

The source license texts are `PROOT-COPYING` and `TALLOC-LICENSE`. The recipe and pinned source archives allow rebuilding the supplied native files; the patch does not bypass Chromium's read-only checks, fake `F_GETFL`, change Android policy or alter SELinux.

## Selection and verification

`ProotRuntimePolicy` selects this triplet only for container code `omarchy` whose boot command begins with the generic `proot`, `$BIN_DIR/proot` or `${BIN_DIR}/proot` executable. Ordinary shell double quoting is accepted. Explicit classic/latest/custom paths keep their existing selection and old loader pair, as do all other container codes. Missing components stop this Omarchy launch rather than mixing runtime versions.

The launcher updates the existing `bootstrap/bin/proot` symlink on every container start, and sets both `PROOT_LOADER` and `PROOT_LOADER_32` for this selection. It does not depend on first-bootstrap alias creation, change `/.tiny.yaml`, or change the global classic/latest setting. The old pair is restored by the existing environment initialization on every other launch.

The native files have dedicated `keepDebugSymbols` patterns so AGP does not change their verified bytes. Build preparation and final APK verification must both validate the three `sourcePath`/`apkPath`, sizes and SHA-256 values in `manifest.json`. An APK signature alone does not establish correspondence to this exact runtime receipt.

## Validation boundary

The private K20 runner compared control and patched executables with matching loaders and the same official Codex DEB. The patched ordinary-file probe enforced true read-only access, including after unlink; memfd reopen still reported the real Android `EACCES`. The control Codex process exited 133, while the patched process stayed alive until the 75-second diagnostic timeout (124), mapped its Sway window and connected to its internal services. These observations support Chromium's own safe file-backend fallback and do not claim unrestricted memfd reopening.

K20 device testing has since covered the installed stable test APKs with version codes 61 and 62. Each installed `base.apk` SHA-256 matched its CI artifact, and the three installed native files matched `manifest.json`. The running PRoot process was observed in the actual application's `untrusted_app` domain, with `/proc/<pid>/exe` pointing to that installation's `nativeLibraryDir`; this evidence goes beyond the earlier private `run-as` diagnostics.

Fresh automatic guest import, coherent restoration of the old guest and its application preferences, and a same-certificate 61-to-62 update followed by a cold start passed. Sway, Chromium and PTY checks passed in the actual application context. On version 61, the Codex login page remained visible alongside the other GUI applications for ten minutes. On version 62, Chromium, Hermes and the Codex login page started normally and coexisted. A new real Hermes AIAgent receipt recorded eight API calls, seven successful CDP calls and all eight acceptance checks passing, including a new UUID-named 55-byte download and a 2340×1080 PNG. The device operator retrieved the screenshot, verified its hash and inspected it visually. Device results are tracked in [the K20 second-round report](../../docs/reports/k20-round2-20261123.md).

No additional Tiny guest was newly installed for this regression. Classic/latest, explicit-command and other-container-code selection retain their passing policy tests; that is not a device test of another distribution. Codex account authentication and authenticated model/task operation remain unverified.
