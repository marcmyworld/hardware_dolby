# LunarisDolby Atmos for Android

A next-generation, conflict-free Dolby Atmos implementation designed for Android 14, 15, 16, and 17 custom ROMs (LineageOS, Evolution X, and AOSP-based trees).

---

## 🌟 What Makes LunarisDolby Different?

Most standard Dolby Atmos ports (such as generic Motorola/Lenovo dumps or legacy ports) suffer from broken background state, aggressive volume compression, and ROM integration conflicts. LunarisDolby was engineered specifically to solve these long-standing issues:

### 1. 🎧 24/7 Per-Device Audio Memory (True Background Persistence)
- **The Problem in other ports:** Standard Dolby ports only track the active audio profile when the UI application is running in the foreground. As soon as you swipe the app away from Recent Apps, put the device to sleep, or let Android's LMK kill the process, the app completely forgets device states. It falls back to a single shared configuration or reverts to speaker settings when you reconnect Bluetooth or plug in headphones.
- **The LunarisDolby Solution:** Powered by an always-active, lightweight system service architecture (`DolbyNotificationListener` + `DolbyDeviceReceiver`), LunarisDolby monitors audio routing events across the entire Android OS lifecycle. Every audio endpoint (Internal Speakers, 3.5mm / Type-C Wired Headsets, Bluetooth A2DP & LE Audio endpoints indexed by unique Bluetooth device identity/MAC, and USB DACs) retains its own isolated profile, graphic equalizer bands, bass boost, and dialogue clarity settings. Tuning auto-switches instantaneously in the background, even when the UI has never been opened.

### 2. 🚫 Zero Audio Playback Interruption & Speaker Fallback Prevention
- Other ports listen naively to music track states. When a song pauses or between tracks, they falsely detect an idle state and fall back to the internal speaker profile, causing jarring volume jumps or EQ glitches when the next track begins.
- LunarisDolby locks the profile strictly to the physical audio routing topology and ignores transient playback pauses.

### 3. 🔊 Volume Leveler Fix (No More Muddy / Muffled Audio)
- Legacy ports enable Dolby's dynamic volume leveler (`dvle` / volume leveling) by default or clamp dynamic range excessively, resulting in flat, heavily compressed audio output.
- LunarisDolby tunes default profile definitions (`dax-default.xml`) to disable aggressive dynamic leveling out of the box, preserving the full dynamic range and natural punch of your music and media.

### 4. 🛡️ Conflict-Free ROM Package Integration
- When building modern custom ROMs that include pre-bundled Dolby packages (such as Evolution X or vendor-bundled variants), builds often fail due to duplicate package declarations, duplicate permission XMLs, or overlay collisions.
- LunarisDolby uses namespaced privileged permissions (`privapp_whitelist_org.lineageos.lunarisdolby.xml`, `sysconfig_org.lineageos.lunarisdolby.xml`), explicit module overrides (`overrides: ["DolbyAtmos", "DolbyManager", ...]`), and auto-granted notification listener permissions to ensure seamless, zero-conflict builds across any ROM source.

### 5. 🧩 Dynamic Blobs (`TARGET_PROVIDES_DOLBY_BLOBS`)
- Device trees with native vendor blobs (e.g. Xiaomi SM8650 / SM8635 devices like `chenfeng`) can set `TARGET_PROVIDES_DOLBY_BLOBS := true` to build only the modern LunarisDolby app and framework layer, preventing duplicate vendor HALs/binaries. Devices without native blobs inherit the full standalone Dolby Atmos stack automatically.

### 6. 🎨 Modern Material 3 Jetpack Compose UI
- Clean, responsive interface built with Android Jetpack Compose.
- Accessible interactive sliders, intuitive equalizer band adjustments, and dynamic theming that adapts to your system wallpaper.

---

## 🚀 How to Integrate into Your Device Tree

### Step 1: Clone into your ROM source
Clone this repository to `hardware/dolby` in your ROM source tree:
```bash
git clone https://gitlab.com/xiaomi-chenfeng/hardware_dolby.git -b 17 hardware/dolby
```
*(Or add it to your local roomservice / manifests XML).*

### Step 2: Inherit Dolby in your `device.mk`
In your device's `device.mk` (e.g. `device/<vendor>/<codename>/device.mk`), add:
```make
# Inherit Dolby Atmos
$(call inherit-product, hardware/dolby/dolby.mk)
```

> **Note for devices with native vendor Dolby blobs:**
> If your vendor tree already supplies vendor Dolby libraries (`libswdap`, `libdlbdsservice`, etc.), add the following flag before inheriting `dolby.mk`:
> ```make
> TARGET_PROVIDES_DOLBY_BLOBS := true
> ```

### Step 3: BoardConfig Manifest Rules
In your `BoardConfig.mk`, make sure you use `+=` (append) instead of `:=` (assignment) for framework compatibility matrix and device manifest files so the Dolby VINTF entries are not clobbered:
```make
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += ...
DEVICE_MANIFEST_FILE += ...
```

### Step 4: Include Dolby Media Codecs (Optional)
If your device's vendor media codecs configuration supports Dolby Audio Codec2 (AC-4 / EAC-3 / DDP), include the Dolby codecs XML in your vendor `media_codecs.xml`:
```xml
<Include href="media_codecs_dolby_audio.xml" />
```

### Step 5: Audio Effects Configuration
Make sure your vendor or device tree `audio_effects.xml` declares the Dolby Atmos effect UUIDs:
```xml
<libraries>
    <library name="dap" path="libswdap.so"/>
    <library name="dvl" path="libdlbvol.so"/>
    <library name="spatializer" path="libswspatializer.so"/>
</libraries>
<effects>
    <effect name="dap" library="dap" uuid="9d492000-8269-1000-8000-0002a5d5c51b"/>
    <effect name="dlb_music_listener" library="dvl" uuid="40f66c80-5944-11e1-a129-0002a5d5c51b"/>
    <effect name="dlb_ring_listener" library="dvl" uuid="c117b2b8-f27f-4d00-9280-0002a5d5c51b"/>
    <effect name="dlb_alarm_listener" library="dvl" uuid="55987048-47a3-4b62-bc61-0002a5d5c51b"/>
    <effect name="dlb_system_listener" library="dvl" uuid="8740a480-47a1-439c-ad43-0002a5d5c51b"/>
    <effect name="dlb_notification_listener" library="dvl" uuid="1e003b80-61ed-11e1-9860-0002a5d5c51b"/>
    <effect name="spatializer" library="spatializer" uuid="ccd4cf09-a79d-46c2-9aae-06a1698d6c8f"/>
</effects>
```

---

## 📜 License & Credits
- LunarisDolby UI and Background Audio Engine: Apache License 2.0.
- Dolby Atmos components and libraries: Proprietary Dolby Laboratories, Inc.
- Maintained for Xiaomi Snapdragon 8s Gen 3 / Chenfeng and LineageOS / Evolution X.
