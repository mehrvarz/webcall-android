<div align="center">
  <a href="https://timur.mobi/webcall/android"><img src="WebCall-for-Android.png" alt="WebCall for Android"></a>
</div>

# WebCall for Android

[WebCall](https://github.com/mehrvarz/webcall) is a telephony server and a set of web applications for making and receiving calls. It is based on WebRTC 1.0. WebCall let's you create low latency P2P connections with very high audio quality (Opus 280kbps). You can also add video streams at any time during the call. WebCall also lets you transfer files in both directions. Connections are always e2e encrypted. WebCall is selfcontaned. There is no use of 3rd party services at any time. If you run a WebCall server yourself, you will get a very private telephony solution.

[WebCall for Android](https://timur.mobi/webcall/android) offers the same functionality as the core WebCall clients do. Listed below are some of the extended functionality offered by the Android client. [APK Download.](https://timur.mobi/webcall/android/#download)

### NFC Connect

NFC Connect lets you to establish phone calls by touching two devices. Once connected the two parties can walk apart and continue the call. The other device does not need any special software. It needs to support NFC, have a 2020+ web browser and an internet connection (mobile, wifi, whatever). So even if you are using the Android version, you do benefit from the Web application, because anybody on the Web can call you. All they need to know is your WebCall link (your "phone number").

### 24/7 Operation

WebCall for Android lets you receive calls when the device is in sleep mode. This is a feature the Web application can not offer. It makes the Android variant a better solution for all day operations.

### Ring on Speaker

WebCall for Android can play back the ringtone on the loud speaker, even if you have a headset connected to the device. This lets you receive calls quicker.

### Low power use

WebCall for Android has very low power requirements when receiving calls in the background. It often uses less battery than a regular mobile browser running in parallel in complete idle mode.


## Building the APK

You need Java 11 and Gradle 7.3.3. You can build this project by running: gradle build --info

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

### 3rd party artwork

- Uri Herrera, KDE Visual Design, GNU Lesser General Public
- Timothy Miller, Public domain
- BadPiggies, Creative Commons Attribution-Share Alike 4.0

