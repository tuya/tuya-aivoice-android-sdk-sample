
## Project Introduction

The **AI Voice UI BizBundle** is designed to upgrade standard audio products—such as Bluetooth headphones, smart glasses, and speakers—into AI-powered devices. It provides access to AI recording and translation features, utilizing professional recording algorithms combined with advanced language models. It supports real-time, accurate transcription and translation across more than 100 languages worldwide.

Before integrating this BizBundle, please complete the [Preparation](https://developer.tuya.com/en/docs/app-development/preparation?id=Ka8qhzjybzmko) and [Quick Integration](https://developer.tuya.com/en/docs/app-development/integrated?id=Ka69nt96cw0uj) steps.

AI Voice BizBundle Demo Link: [https://github.com/tuya/tuya-aivoice-android-sdk-sample.git](https://github.com/tuya/tuya-aivoice-android-sdk-sample.git).

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
        kotlin_version = '1.8.20'
        hilt_version = '2.50'
        sdk_version = '7.0.0-beta.2'
        ipc_sdk_version = '6.11.1'
        biz_bom_version = "6.11.1-feature-bizbundle-6.11.0.21"
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
        resolutionStrategy.force 'org.jetbrains.kotlin:kotlin-stdlib:1.8.20'
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
