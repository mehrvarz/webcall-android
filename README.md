<div align="center">
  <a href="https://timur.mobi/webcall/android"><img src="WebCall-for-Android.png" alt="WebCall for Android"></a>
</div>

# WebCall for Android

WebCall for Android offers the following features on top of the core WebCall package:

- NFC Connect
- Ring on speaker
- Receiving calls while in deep sleep
- Low battery consumption

WebCall for Android offers all WebRTC 1.0 based audio/video telephony features provided by the core WebCall client. This includes low latency E2E-encrypted P2P communications and support for very high quality audio using the Opus codec (two-way 20-280kbp/s). Find out more about [Core WebCall.](https://github.com/mehrvarz/webcall/) Learn how you can run your own selfcontaned [WebCall server.](https://timur.mobi/webcall/install/)
Even if you only use WebCall for Android, you still benefit from core WebCall, as it allows anybody on the Web to give you a call. All they need to know is your WebCall link (your WebCall "phone number").

### NFC Connect

NFC Connect lets you establish phone calls by touching two devices. Once connected the two parties can split and walk away, while continuing the call. The other device does not require any special software. It only needs internet (mobile or wifi), NFC and a 2020+ web browser. If both devices are connected to the same Wifi network, the call will occur over Wifi only.

### Ring on Speaker

WebCall for Android can play back the ringtone on the loud speaker, even if you have a headset connected. If you intend to use a headset, this feature can simplify picking up calls a lot.

### Receiving calls while in deep sleep

WebCall for Android lets you receive calls when your device is in deep sleep mode. This makes the Android client a much better solution for all day operations.

### Low power requirements

WebCall for Android has very low power requirements while awaiting calls in the background.

## More info + APK Download

You can use WebCall for Android as your only phone software (say, on your Wifi-only Android tablet) or as a companion phone solution. Find more about WebCall for Android and download the APK [here.](https://timur.mobi/webcall/android)

## Build the APK

You need Java 11 and Gradle 7.3.3. You can build this project by running: gradle build

If you only want to build an unsigned debug APK, just outcomment the "release" section under "buildTypes" in "build.gradle". If you want to build a signed release APK, add two files "keystore.properties" and "releasekey.keystore" to the base directory. The "keystore.properties" should have three entries:
```
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

## License

This program is Free Software: You can use, study share and improve it at your will. Specifically you can redistribute and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

GPL3.0 - see: [LICENSE](LICENSE)

### 3rd party code

- github.com/TooTallNate/Java-WebSocket, MIT license

### 3rd party icons

- Uri Herrera, KDE Visual Design, GNU Lesser General Public
- Timothy Miller, Public domain
- BadPiggies, Creative Commons Attribution-Share Alike 4.0

