
## Project Introduction

The **AI Voice UI BizBundle** is designed to upgrade standard audio products—such as Bluetooth headphones, smart glasses, and speakers—into AI-powered devices. It provides access to AI recording and translation features, utilizing professional recording algorithms combined with advanced language models. It supports real-time, accurate transcription and translation across more than 100 languages worldwide.

Before integrating this BizBundle, please complete the [Preparation](https://developer.tuya.com/en/docs/app-development/preparation?id=Ka8qhzjybzmko) and [Quick Integration](https://developer.tuya.com/en/docs/app-development/integrated?id=Ka69nt96cw0uj) steps.

AI Voice BizBundle Demo Link: [https://github.com/tuya/tuya-aivoice-android-sdk-sample.git](https://github.com/tuya/tuya-aivoice-android-sdk-sample.git).

---

## Native API Integration Guide

Besides the ready-made UI BizBundle, this project also exposes a **Native API**
(`ThingAudioDetectManagerNative`) so you can build your own UI on top of recording,
transcription, cloud sync and the other capabilities.

👉 **[Native API Integration Guide](./docs/modules/README.md)** (written in Chinese)

Split per capability module. Each document covers the API list, call sequence,
per-parameter reference, data structure field tables, error codes and an integration checklist:

| Module | Document |
|---|---|
| 1 · Recording control & device capability | [module-01](./docs/modules/module-01-record-control.md) |
| 2 · Transcription & summary | [module-02](./docs/modules/module-02-transcribe-summary.md) |
| 3 · File management | [module-03](./docs/modules/module-03-file-management.md) |
| 4 · Offline file transfer | [module-04](./docs/modules/module-04-offline-transfer.md) |
| 5 · Audio import | [module-05](./docs/modules/module-05-audio-import.md) |
| 6 · Cloud sync | [module-06](./docs/modules/module-06-cloud-sync.md) |
| 8 · Merge recordings | [module-08](./docs/modules/module-08-merge-record.md) |
| 9 · Share / floating ball / quick entry | [module-09](./docs/modules/module-09-share-and-entry.md) |
| 10 · Text translation | [module-10](./docs/modules/module-10-text-translation.md) |

Runnable samples live in [`ai_voice/.../nativeui`](./ai_voice/src/main/java/com/tuya/smart/ai_voice/nativeui);
every module document links to its own demo page.

---

## Brief Summary of Considerations

### 1. Preparation

- **Description**: Ensure that you have applied for the AppKey, AppSecretKey, AppID, signing components, etc., on the Tuya IoT Platform and integrated them into your project.
- **Documentation**: [Preparation for Integrating App SDK](https://developer.tuya.com/en/docs/app-development/integrated?id=Ka69nt96cw0uj)

### Add Maven Repositories
Add the following content to the `build.gradle` file in the root directory of your project:

```groovy
buildscript {

    ext {
        kotlin_version = '2.1.0'
        hilt_version = '2.58'
        sdk_version = '7.8.0'
        ipc_sdk_version = '7.8.1'
        biz_bom_version = "7.8.15"
        applicationId = "com.sample.sdk"
    }
    repositories {
        mavenLocal()
        maven { url "https://maven-other.tuya.com/repository/maven-commercial-releases/" }

        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.8.0'
        classpath "com.thingclips.smart:thingsmart-theme-open-plugin:2.0.5"
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
        classpath "com.google.dagger:hilt-android-gradle-plugin:$hilt_version"
    }
}

allprojects {

    repositories {
        maven { url "https://maven-other.tuya.com/repository/maven-commercial-releases/" }

        google()
        mavenCentral()

        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url "https://oss.sonatype.org/content/repositories/snapshots/" }
        maven { url "https://jitpack.io" }
        maven { url 'https://developer.huawei.com/repo/' }
    }

    configurations.all {
        resolutionStrategy.force 'com.google.code.findbugs:jsr305:1.3.9'
        resolutionStrategy.force 'com.squareup.okhttp3:okhttp-jvm:5.0.0-alpha.11'
        resolutionStrategy.force 'com.squareup.okhttp3:okhttp-java-net-cookiejar:5.0.0-alpha.11'
        resolutionStrategy.force 'com.squareup.okhttp3:okhttp-urlconnection:5.0.0-alpha.11'
        resolutionStrategy.force 'com.squareup.okio:okio-jvm:3.2.0'
        resolutionStrategy.force "org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version"
        resolutionStrategy.force "org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlin_version"
        resolutionStrategy.force "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version"
        resolutionStrategy.force "org.jetbrains.kotlin:kotlin-reflect:$kotlin_version"
        exclude group: "com.umeng.umsdk", module: 'huawei-basetb'
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}

```

### Add Component Dependencies
Add the following component dependencies to the `build.gradle` file of the module where the BizBundle is being integrated:

```groovy
api enforcedPlatform("com.thingclips.smart:thingsmart-BizBundlesBom:${biz_bom_version}")

api "com.thingclips.smart:thingsmart-bizbundle-wearkit"
api "com.thingclips.smart:thingsmart-bizbundle-device_activator"
api "com.thingclips.smart:thingsmart-bizbundle-qrcode_mlkit"
api ("com.thingclips.smart:thingsmart-bizbundle-basekit"){
        exclude group:"com.thingclips.smart",module:"thingplugin-annotation"
    }
api "com.thingclips.smart:thingsmart-bizbundle-devicekit"
api "com.thingclips.smart:thingsmart-bizbundle-bizkit"
api "com.thingclips.smart:thingsmart-bizbundle-homekit"

api "com.thingclips.smart:thingsmart-bizbundle-panelmore"
api "com.thingclips.smart:thingsmart-bizbundle-family"
api "com.thingclips.smart:thingsmart-bizbundle-miniapp"
api "com.thingclips.smart:thingsmart-bizbundle-share"

api "com.thingclips.smart:thingsmart:${sdk_version}"
api "com.thingclips.smart:thingsmart-ipcsdk:${ipc_sdk_version}"

```