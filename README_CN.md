
## 项目介绍

AI 音频 UI 业务包是针对普通蓝牙耳机、眼镜、音箱等音频类产品直接升级为 AI 产品，即可使用 AI 记录和翻译功能，专业录音算法配合先进的语言模型，覆盖全球 100+ 种语言的实时精准转写和实时翻译。

在接入该业务包之前请先完成 [准备工作](https://developer.tuya.com/cn/docs/app-development/preparation?id=Ka8qhzjybzmko) 和 [快速集成](https://developer.tuya.com/cn/docs/app-development/integrated?id=Ka69nt96cw0uj)。

AI 音频业务包：[https://github.com/tuya/tuya-aivoice-android-sdk-sample.git](https://github.com/tuya/tuya-aivoice-android-sdk-sample.git).

---

## Native 能力接入文档

除 UI 业务包外，本工程还提供一套 **Native API**（`ThingAudioDetectManagerNative`），
可在不使用现成 UI 的情况下自行实现录音、转写、云同步等能力。

👉 **[Native 能力接入指南](./docs/modules/README.md)**

按能力模块拆分，每份包含接口清单、调用时序、逐参数说明、数据结构字段表、错误码与接入清单：

| 模块 | 文档 |
|---|---|
| 1 · 录音控制与设备能力 | [module-01](./docs/modules/module-01-record-control.md) |
| 2 · 转写 / 总结 | [module-02](./docs/modules/module-02-transcribe-summary.md) |
| 3 · 文件管理 | [module-03](./docs/modules/module-03-file-management.md) |
| 4 · 离线文件传输 | [module-04](./docs/modules/module-04-offline-transfer.md) |
| 5 · 音频导入 | [module-05](./docs/modules/module-05-audio-import.md) |
| 6 · 云同步 | [module-06](./docs/modules/module-06-cloud-sync.md) |
| 8 · 合并录音 | [module-08](./docs/modules/module-08-merge-record.md) |
| 9 · 分享 / 悬浮球 / 快捷入口 | [module-09](./docs/modules/module-09-share-and-entry.md) |
| 10 · 文本翻译 | [module-10](./docs/modules/module-10-text-translation.md) |

对应的可运行示例在 [`ai_voice/.../nativeui`](./ai_voice/src/main/java/com/tuya/smart/ai_voice/nativeui)，
每个模块的文档都指向它的 Demo 页面。

---

## 注意事项

### 1. 准备工作

- **说明**: 请确保在Tuya IOT平台申请了AppKey、AppSecretKey、AppID、签名组件等，并集成到项目中。
- **文档**：[集成APP SDK准备工作](https://developer.tuya.com/cn/docs/app-development/integrated?id=Ka69nt96cw0uj)


### 添加maven仓库
在项目的根目录下的build.gradle文件中，添加以下内容
```groovy
// Top-level build file where you can add configuration options common to all sub-projects/modules.
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

### 添加组件依赖
在集成业务包的module工程下的build.gradle文件中添加以下组件依赖
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

