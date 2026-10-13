# 50 MP capture on the Galaxy Z Fold 5 under LineageOS

**Result:** [`evidence/fold5-50mp-8160x6120.jpg`](evidence/fold5-50mp-8160x6120.jpg) — a
true 8160x6120 (49.9 MP) JPEG, 14.8 MB, captured by a third-party app on **LineageOS
23.2 (Android 16)** from the S5KGN3 main sensor through the vendor HAL's own
HighResolution remosaic pipeline.

The same pipeline One UI uses for Expert RAW / 50 MP stills, reached without a Samsung
camera app, without Expert RAW, and without any Samsung framework component.

```
[CHI_HR] needToHRShot: [HR] HighResolution Shot with Single Shot
[CHI_HR] ExecuteCaptureRequest: [HR] 3. Destroyed Binning Preview Session. Creating Quadra Preview Session...
[CHI_HR] CreateHRSession: [HR] m_pHighResolutionRTPSession created
[CHI_HR] ActivatePipeline for HighResolutionAiRealtimeSensor2RDI
```

Baseline: SM-F946B (q5q), LineageOS 23.2 `lineage-23.2-20260726-UNOFFICIAL-q5q`,
stock Samsung vendor partition (vendor fingerprint `samsung/q5qxxx/q5q:13/...`),
KernelSU root with an unlocked bootloader. All three cameraserver patches are also in
this repository as a series.

## Why it does not work out of the box

On stock One UI the chain is: app -> `CameraDevice.setParameters(PDK string)` +
`OutputConfiguration.semCreateOutputConfiguration(option=2)` -> Samsung's cameraserver ->
private AIDL -> vendor HAL -> HighResolution use case. LineageOS replaces every piece
above the vendor HAL, so five separate gates had to be opened:

1. **No framework API.** AOSP has no `CameraDevice.setParameters` and no
   `semCreateOutputConfiguration`; the PDK string has no route into the HAL.
2. **The private channel is a binder *extension*.** Samsung exposes
   `vendor.samsung.hardware.camera.device.ISehCameraDeviceSession.setParameters(string)`
   (transaction 1, `FLAG_PRIVATE_LOCAL`) as an **AIDL binder extension** on the standard
   session binder — `AIBinder_getExtension()` in the vendor's
   `AidlCameraDeviceSession::Initialize`. Sending the transaction to the session binder
   itself returns `FAILED_TRANSACTION`; the proxy descriptor rule
   (`BpBinder::getInterfaceDescriptor()` queries the remote) means the standard session
   can never be class-cast to the Samsung interface.
3. **The HAL classifies its client.** Without `samsungcamera=true` in the parameter
   string the session stays in `UNI_SHOOTING_MODE_3RD_PARTY_BYPASS` and the HAL clamps
   every 8160x6120 request to 1920x1440. With it, `isSamsungCamera=true` and the
   scenario becomes `UNI_SHOOTING_MODE_GALAXY_RAW_HIRES` (see
   `evidence/hal-highresolution-run.txt`).
4. **AOSP stream validation rejects the size.** 8160x6120 RAW10/JPEG exist only in
   Samsung's private tags
   (`samsung.android.scaler.availableHighresRawStreamConfigurations`,
   `...availablePictureStreamConfigurations`, `samsung.android.unihal.mmfSize`), never
   in the standard `SCALER_STREAM_CONFIGURATION_MAP`, so
   `SessionConfigurationUtils::createConfiguredSurface` rejects the stream before the
   HAL sees it.
5. **Replacing a system daemon on a verity build.** The overlayfs metamodule copies
   module files into its ext4 image, and that copy loses the `cameraserver_exec` label,
   so init refuses to start the service; even with the label fixed, init will not
   perform the domain transition into `u:r:cameraserver` for a bind mount from `/data`
   (`security_bounded_transition` denied, then `execute_no_trans` denied, exit 127).
   Staging the binary on a **tmpfs** and bind-mounting from there starts normally.

## Patches

Apply in order to a LineageOS 23.2 tree (`frameworks/av`):

| Patch | What it does |
|---|---|
| `patches/0001-cameraserver-vendor-session-keys.patch` | `filterParameters()` also accepts the HAL's own session keys (`samsung.android.request.availableSessionKeys`) instead of dropping them. |
| `patches/0002-cameraserver-samsung-session-extension.patch` | Takes the Samsung session **extension** off the session binder (`AIBinder_getExtension`) and sends `setParameters` (transaction 1, `FLAG_PRIVATE_VENDOR`) with One UI's PDK string before `configureStreams`, whenever a stream carries Samsung's high-resolution option. The string is built in and overridable with `persist.camera.samsung_hr_params`. |
| `patches/0003-cameraserver-vendor-stream-options.patch` | Skips AOSP's stream-size rounding when the stream carries a Samsung vendor option (`streamUseCase` bits 16+, i.e. One UI's `option << 16`), so 8160x6120 RAW10/JPEG reach the HAL, which validates them itself. |

```sh
cd frameworks/av
git apply -p1 <patches/0001-cameraserver-vendor-session-keys.patch>
git apply -p1 <patches/0002-cameraserver-samsung-session-extension.patch>
git apply -p1 <patches/0003-cameraserver-vendor-stream-options.patch>
cd $ANDROID_ROOT && source build/envsetup.sh && lunch lineage_q5q-bp4a-userdebug
mka cameraserver
```

The parameter string the HAL needs (from the stock probe in the prior-art repository):

```
first-entrance=false;samsungcamera=true;factorytest=false;shootingmode=40;
recording-fps=0;sw-vdis=false;video-beautyface=false;vtmode=0;
operation_mode=none;ssm_shot_mode=2;recording_dr_mode=sdr;sw_super_vdis=false;stream_type=0;
```

## Probe app (`app/`)

Camera2 client that reproduces One UI's stream shape, using only public API — Samsung's
`option` rides in `OutputConfiguration.setStreamUseCase` bits 16+:

- RAW10 8160x6120, streamUseCase `0x20000` (option 2 = high resolution)
- JPEG 8160x6120, streamUseCase `0x20000`
- YUV_420_888 1920x1440, streamUseCase `0x10000` (option 1 = preview)
- repeating preview with vendor keys `shootingMode=40`, `ssmShotMode=2`,
  `capturePhysicalId=56`; still capture on raw+JPEG after 8 previews

The launcher activity runs the recipe on open and writes `highres.log` plus
`highres.jpg` to its external files directory. `MainActivity` keeps the original
nine-case sweep used while hunting the gate.

Build: Gradle 8.13 + AGP 8.7.3, `compileSdk 35`, `sdk.dir` in `local.properties`.

## KernelSU module (`module/`)

`module.prop`, `post-fs-data.sh`, `service.sh`, `sepolicy.rule`, `skip_mount`.
The module replaces `/system/bin/cameraserver` with the patched build:

- `skip_mount` keeps the module out of the overlayfs metamodule's image, whose copies
  lose the `cameraserver_exec` label and make init refuse the service.
- `post-fs-data.sh` mounts a private tmpfs, copies the binary there, labels it
  `u:object_r:cameraserver_exec:s0` and bind-mounts it over `/system/bin/cameraserver`
  before init starts class `main`.
- `service.sh` verifies **what actually ended up running**: it compares the md5 of
  `/system/bin/cameraserver` with the module's binary and, if they differ (for example
  when the overlayfs metamodule mounts its `/system` overlay after the early bind mount
  and hides it — the v4 failure), re-stages from the tmpfs on top of everything and
  `ctl.restart cameraserver`. A failed staging is safe: with `skip_mount` and no mount,
  `/system/bin/cameraserver` stays the ROM's stock binary and the camera keeps working.

Package it as a zip with the patched binary at `system/bin/cameraserver`, install with
KernelSU Manager (or `ksud module install`), reboot. Uninstall + reboot restores stock.

## Verified across a cold boot

The module was installed, the device rebooted, and the chain verified afterwards — see
[`evidence/boot-verification.txt`](evidence/boot-verification.txt):

```
post-fs-data: bind mount from tmpfs OK, label u:object_r:cameraserver_exec:s0
service(v8): boot state=running binary=46a75474... want=46a75474...
service(v8): patched cameraserver running (md5 match)
```

init started the patched daemon from the tmpfs stage (`sid=u:r:cameraserver:s0`), and a
probe run after that boot produced another 8160x6120 JPEG. The 50 MP path therefore
survives reboots; it is not a runtime-only adb mount.

## Evidence (`evidence/`)

- `fold5-50mp-8160x6120.jpg` — the capture (8160x6120, 14,812,179 bytes)
- `highres.log` — the app's run log
- `hal-highresolution-run.txt` — cameraserver call, HAL scenario selection, CHI_HR pipeline
- `boot-verification.txt` — module log, post-boot service state and post-reboot probe run

## Prior art and credit

The stock-OS half of this work is
[HyperRamzey/fold5-camera2-research](https://github.com/HyperRamzey/fold5-camera2-research):
IDA analysis of Samsung's `cameraserver` package gate, the decoded
`setParameters` call chain, the PDK parameter string, the 50 MP stream recipe and the
AGC/Expert RAW work that proved the hardware path. This repository is the LineageOS
counterpart: it rebuilds the access path that One UI's framework and cameraserver
provide, and documents where AOSP has to be taught about Samsung's private interfaces.

## Notes and limitations

- The patches target the Android 16 / LineageOS 23.2 cameraserver; they will need
  rebasing on other revisions.
- Building this from a tree newer than the installed ROM can produce a binary that
  behaves differently; keep the tree at the ROM's revision.
- `persist.camera.samsung_hr_params` could not be set on the test device (SELinux
  denies setting the undefined property), so the string is compiled in as the default;
  the code reads the property first if it is settable.
- No Samsung binaries are redistributed here: the patches and scripts build from source,
  and the stock firmware is the user's own.
- Reverse engineering for interoperability on hardware you own; check your local rules
  and your warranty before flashing anything.
