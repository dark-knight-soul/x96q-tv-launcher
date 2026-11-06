# X96Q firmware notes: preinstalled adware and how to remove it

Device examined: **X96Q**, Allwinner H313 (`sun50iw9p1`), Mali-G31, 2 GB RAM, firmware `X96Q_20251113-1021`
(Android 10 `userdebug`, `test-keys`, security patch 2019-12, 32-bit userspace, fingerprint spoofed as Pixel 2 `walleye`).
Other X96Q batches and firmware builds may differ — check before you change anything.

## What is inside

| Package | Location | What it does |
|---|---|---|
| `com.androidkm.store` ("AtvStore") | `/system/app/AtvStore`, **uid system** | Dropper. Every 2 h downloads a plugin (`plugin-98k-*.apk`) and encrypted APKs from `xfile.kkoper.cc`, decrypts them and silently installs them |
| `com.google.android.adffa` | installed by the dropper | Fake "Google" package, ad SDK with `hidden_mode=true`, channel `TCL_FFA`, keep-alive process `:bak` |
| `com.chihim.lockscreentv.play` | installed by the dropper | Ad SDK, `:bak` keep-alive process |
| `com.localvideoplayer.tv` | installed by the dropper | "Video player" with an embedded WebView and the same ad SDK, `:bak` process |
| `com.easycontrol.mdm` | `/system/priv-app/MDM`, uid system, persistent | Makes itself **Device Owner** on every start (`DevicePolicyManagerService` in the firmware is patched to allow it) |
| `com.android.remotecare.generic` | `/system/priv-app/RemoteControl`, persistent | Adups remote control: accessibility input service, `MANAGE_MEDIA_PROJECTION` (screen capture) |
| `com.abupdate.fota_demo_iot` | `/system/app/Abupdate` | Adups FOTA |
| `com.yxt.accessibility`, `com.yxt.appstore`, `com.yxt.memoryclean`, `com.vv.browser` | system | Vendor extras; `yxt.accessibility` can toggle components and reboot |

Domains used by the dropper and the ad SDKs: `api.vemovie.cc`, `api.361bestmovie.com`, `random.vivosoc.cc`,
`xfile.kkoper.cc`, `api.bcytua.cc` (plus Alibaba public DNS `223.5.5.5` over HTTP).

Security posture of this firmware: **network ADB is open without authorization** (`ro.adb.secure=0`,
`service.adb.tcp.port=5555`) and `su` is available to the `shell` user (the `/system/bin/qw` daemon is a renamed
Koush superuser daemon) — anyone on the same Wi-Fi gets root. SELinux is permissive. `/vendor/bin/dom2reg` is harmless
(sets the Wi-Fi regulatory domain from the time zone).

## Removal that worked (no reflashing)

Make backups first (`adb pull` the APKs and `/data/system/device_owner_2.xml`, `/data/system/device_policies.xml`).

```bash
adb connect <BOX-IP>:5555
# 1. Stop the dropper and remove what it installed
adb shell pm disable-user --user 0 com.androidkm.store
adb shell pm uninstall com.google.android.adffa
adb shell pm uninstall com.chihim.lockscreentv.play
adb shell pm uninstall com.localvideoplayer.tv
# 2. Vendor extras
for p in com.android.remotecare.generic com.abupdate.fota_demo_iot com.yxt.accessibility com.yxt.appstore com.vv.browser; do
  adb shell pm disable-user --user 0 $p
done
```

**MDM and RemoteControl** are `persistent` system apps: Android starts their code even when they are disabled, and the
MDM re-registers itself as Device Owner on every boot. What worked: hide their APKs from the package manager
(rename, keeps a trivial rollback) and reboot.

```bash
adb shell su 0 mount -o remount,rw /
adb shell su 0 mv /system/priv-app/MDM/MDM.apk /system/priv-app/MDM/MDM.apk.off
adb shell su 0 mv /system/priv-app/RemoteControl/RemoteControl.apk /system/priv-app/RemoteControl/RemoteControl.apk.off
adb shell su 0 mv /system/app/AtvStore/AtvStore.apk /system/app/AtvStore/AtvStore.apk.off
adb shell su 0 mount -o remount,ro /
adb reboot
```

The box boots normally afterwards; `dumpsys device_policy` shows no Device Owner. Rollback: rename `*.apk.off` back.

**Blocking the domains in `/system/etc/hosts`.** The system partition (no dm-verity on this build) is 100 % full and the
kernel keeps 4096 clusters (16 MB) in reserve, so any write fails with `ENOSPC` — and a plain `cp` **truncates the file
to 0 bytes first**. Temporarily drop the reserve, write with `dd`, restore it:

```bash
adb shell su 0 sh -c 'echo 0 > /sys/fs/ext4/dm-0/reserved_clusters'
adb shell su 0 mount -o remount,rw /
adb push hosts /data/local/tmp/hosts
adb shell su 0 dd if=/data/local/tmp/hosts of=/system/etc/hosts conv=fsync
adb shell su 0 sh -c 'echo 4096 > /sys/fs/ext4/dm-0/reserved_clusters'
adb shell su 0 mount -o remount,ro /
```

## Speeding up apps

The firmware sets `ro.config.low_ram=true`, so apps are installed **without ahead-of-time compilation**
(`dumpsys package dexopt` shows `status=extract`). Compiling heavy apps helps a lot:

```bash
adb shell cmd package compile -m speed -f com.google.android.youtube.tv
```

YouTube, Kinopoisk and Rutube cold start became ~2× faster (2.4 → 1.2 s, 6.2 → 3.1 s, 5.5 → 2.7 s).
App updates reset this — repeat after updates (`tools/dexstat.sh` lists the current state).

Side effect of `low_ram`: notification listeners are not started at all, so apps cannot read media sessions —
the launcher's "Now playing" card stays hidden on this box.

## Other quirks

- The box starts in **touch mode**: views must be `focusableInTouchMode`, otherwise nothing is focused until the first key press.
- Default time zone was `Europe/Moscow` with automatic zone on: `settings put global auto_time_zone 0` and
  `service call alarm 3 s16 <Area/City>` fix it.
- `input keyevent --longpress DPAD_CENTER` does not produce a long click on a View — use `KEYCODE_MENU` in scripts.
