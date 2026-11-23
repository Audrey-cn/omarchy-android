# Online Initial Installer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Ship a small same-signature Android APK with persistent resumable first desktop installation, preserve existing guests during App updates, and delay onboarding until desktop readiness.

**Architecture:** A pure JVM HTTPS downloader owns versioned partial/verified archives. An Android foreground service persists and exposes download/install stages; an initial-setup Activity uses a fixed Release catalog and an absent-target-only staged installer. CI builds desktop+browser images separately while APK builds carry only native runtime and catalog.

**Tech Stack:** Existing Kotlin/AGP9 Android API28+, JDK file/network APIs, existing SnakeYAML, Android foreground service/notifications/StateFlow; Python cloud packaging tools and existing GitHub Actions.

**Spec:** docs/superpowers/specs/2026-11-23-online-installer.md

## Global Constraints

- Preserve package com.omarchy.android, persistent certificate and monotonic versions; native PRoot stays in nativeLibraryDir.
- Existing guests and personal data are never reimported, retired or synchronized during ordinary APK startup.
- Images are initial-install media; no rootfs updater, automatic Ubuntu major upgrade or new APT server.
- No credentials, phone identifiers or private archives in source/artifacts; only root owns ADB and only K20 user0.
- No global IME/network/security/partition changes. Files outside each worker's assigned area are read-only.
- Download/install/pause/cancel state survives process death. SHA, exact length, range consistency, storage budget and absent final directory gate successful install.

## Review Focus

- Range requests ignored, wrong Content-Range, changed ETag/entity, short/oversized bodies and corrupt hashes must not publish a mixed image (Task1 fixture tests).
- Process death at partial download, extraction or final commit must preserve a recoverable archive and never register incomplete guest (Tasks1/4).
- App upgrade with no embedded managed payload must not delete existing Chromium/Node/scripts or configs (Task4 preservation test and K20 hash comparison).
- A connected X11 Activity with a black/unready desktop must not show guide anchors; old/stale Sway sockets must not count (Task2 state/IPC tests).
- A successful download but failed tar/config/space check must never wipe an existing guest or mark first launch complete (Task4 transaction fixture tests).

---

### Task 1: Pure resumable image transfer

**Files:** Create apps/android/app/src/main/java/com/fct/tc4/install/ImageSpec.kt and ResumableImageDownloader.kt; matching tests under src/test/java/com/fct/tc4/install/.

**Interfaces:** `data class ImageSpec(val id:String,val name:String,val abi:String,val minAppVersionCode:Int,val bytes:Long,val unpackedBytes:Long,val sha256:String,val sourceSha:String,val urls:List<String>)`. `class ResumableImageDownloader(connectTimeoutMs:Int=15000,readTimeoutMs:Int=15000,allowHttpForTests:Boolean=false)` exposes `download(spec:ImageSpec,directory:File,shouldPause:()->Boolean={false},progress:(Long,Long)->Unit={_,_->}):File?`, `partialBytes(spec:ImageSpec,directory:File):Long`, `cancel(spec:ImageSpec,directory:File)`. Null means intentionally paused. HTTP is permitted only for loopback fixtures when explicitly enabled; production accepts HTTPS only. Verified returned file has exact expected bytes/hash.

- [x] Write and observe failing local-server tests for fresh download, pause/restart instance resume, ranges/ETags, ignored Range, truncation, oversized content, corrupt SHA and cache symlinks.
- [x] Implement the minimal bounded downloader with owned hash-addressed files and atomic entity metadata.
- [x] Run actual JVM tests; report red/green commands and independent review package.

### Task 2: Onboarding readiness

**Files:** Modify only ui/misc/OmarchyX11Overlay.kt; create narrowly scoped readiness probe/state helper(s) and tests. Do not change guest scripts.

**Interfaces:** Overlay attachment starts a cancellable readiness poll for current app-private X11/Sway session, replaces the unconditional 2200ms guide launch and cancels on detach. Keep completed onboarding preferences and all input/overlay settings intact.

- [x] Write failing tests for connected viewport without desktop, stale IPC socket, ready desktop, detach/retry and completion flag remaining false until the actual flow completes.
- [x] Implement readiness probe using current app-private sockets/processes and actual X11 connection state; bounded polling and useful waiting/retry behavior.
- [x] Run focused tests and report boundary/device checks needed.

### Task 3: Independent image and small APK delivery

**Files:** scripts/android-build.py, new scripts/prepare-initial-image.py, relevant Python tests, apps/android/app/build.gradle.kts, .github/workflows/build-android.yml and build-container.yml; bundled assets/initial-images.json.

**Interfaces:** Initial image contains staged core plus browser bundle under opt and outputs the exact catalog schema in spec. Publish canonical trusted main manual image builds with non-latest tag `desktop-ubuntu24-<sha12>` and rootfs.tar.zst/image.json/SHA256SUMS/source/license evidence. A catalog asset is produced separately; root updates the small APK's bundled catalog from verified public metadata. APK verify supports online-installer delivery with no rootfs/managed payload and still verifies certificate, version and the native triplet.

- [x] Add failing archive/catalog/APK-layout tests for correct size/hash/source and rejection of image/managed payload in small installer.
- [x] Move Linux payload staging into the image, materialize tar hardlinks, decouple manual image builds from frequent shell APK workflow and preserve trusted/PR signing boundaries.
- [x] Run Python suite and inspect workflow contracts. Do not push/publish/ADB from worker.

### Task 4: Android first installation and preservation integration (controller)

**Files:** New install catalog/state/service/transaction and setup Activity/UI resources; MainActivity and container entry hooks, AndroidManifest; ManagedCoreAssets/ContainerMainViewModel startup policy.

**Interfaces:** Consume Task1 ImageSpec/downloader. Bundled catalog initial-images.json and refresh the fixed owned Release catalog. Foreground service retains download state, exposes honest progress and controlled pause/resume/cancel; extraction/commit through separate transaction. Main normal startup only offers initial setup when no existing Omarchy; old managed receipt is left in place without synchronization/removal.

- [x] Add failing catalog, path/space/transaction and existing-guest preservation tests, then implement adapters and first-run UI.
- [x] Check actual tar exit status, validate image/config, set fresh aid mapping and commit from private staging into an absent guest; journal and recover interrupted work.
- [x] Compile Android and run all unit checks in cloud. Resolve integration failures before device mutation.

### Task 5: Review, cloud and K20 delivery

- [x] Independent spec and broad diff review; no open high-impact data-loss/recovery/signing issues.
- [x] Publish verified clean initial image and catalog; build signed small APK with compatible bundled fallback.
- [x] K20 install-r preservation + desktop cold start/browser/Agent; download interruption/resume/install readiness in isolated recoverable test environment.
- [x] Publish tested App release, update two-route docs/initial-image policy and report exact validation boundaries. Clean only owned temporary duplicates.
