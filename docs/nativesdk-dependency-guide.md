# AI 音频接入 · 依赖配置

本文是 README、Native 能力文档和灌流工具文档共用的**唯一依赖基线**。
README 不再重复版本号或 Gradle 配置，避免多处维护后出现版本漂移。

覆盖两种接入范围：

| 接入方式 | 适用场景 |
|---|---|
| 仅 Native | 只使用 `ThingAudioDetectManagerNative`，UI 自行实现 |
| 完整 UI BizBundle | 直接使用 AI 音频业务包页面和完整 Demo 能力 |

配套示例见本仓库 `ai_voice` 模块，接口文档见 [`docs/modules/`](./modules/README.md)。

---

## 一、版本基线

| 项 | 变量 / 插件 | 版本 |
|---|---|---|
| Kotlin | `kotlin_version` | `2.1.0` |
| Hilt | `hilt_version` | `2.58` |
| Android Gradle Plugin | `com.android.tools.build:gradle` | `8.8.0` |
| 主题插件 | `thingsmart-theme-open-plugin` | `2.0.5` |
| BizBundle 插件 | `bizbundlePlugin` | `2.0.6` |
| Tuya SDK | `sdk_version` | `7.11.0` |
| IPC SDK | `ipc_sdk_version` | `7.11.1` |
| BizBundle BOM | `biz_bom_version` | `7.11.19` |

升级依赖时先改这张表，再同步根工程和业务模块中的变量引用。

---

## 二、根工程 `build.gradle`

下面是完整 Demo 使用的根工程配置。仅 Native 接入时，可以保留宿主工程已有的
Android / Kotlin 插件配置，但版本与仓库应与此处保持一致。

```groovy
buildscript {
    ext {
        kotlin_version = '2.1.0'
        hilt_version = '2.58'
        sdk_version = '7.11.0'
        ipc_sdk_version = '7.11.1'
        biz_bom_version = "7.11.19"
        applicationId = "com.sample.sdk"
    }
    repositories {
        mavenLocal()
        maven { url "https://maven-other.tuya.com/repository/maven-commercial-releases/" }
        // Tuya 预发布仓库，按需保留
        maven { url "https://maven.tuya-inc.top/repository/maven-android-pre/" }

        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.8.0'
        classpath "com.thingclips.smart:thingsmart-theme-open-plugin:2.0.5"
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
        classpath "com.google.dagger:hilt-android-gradle-plugin:$hilt_version"
        classpath "com.thingclips.smart.android:bizbundlePlugin:2.0.6"
    }
}

allprojects {
    repositories {
        maven { url "https://maven.tuya-inc.top/repository/maven-android-pre/" }
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
```

---

## 三、仅 Native 接入

适用于只使用 `ThingAudioDetectManagerNative`、自行实现 UI 的工程。
不包含 RN 面板、设备配网、IPC、Matter 等完整业务包页面 / 能力；
`thingsmart-ipcsdk` 仍作为 SDK 依赖声明。

```groovy
// 业务模块 build.gradle
dependencies {
    api enforcedPlatform("com.thingclips.smart:thingsmart-BizBundlesBom:${biz_bom_version}")

    api "com.thingclips.smart:thingsmart:${sdk_version}"
    api "com.thingclips.smart:thingsmart-bizbundle-wearkit"
    api "com.thingclips.smart:thingsmart-bizbundle-panelmore"
    api "com.thingclips.smart:thingsmart-bizbundle-family"
    api "com.thingclips.smart:thingsmart-ipcsdk:${ipc_sdk_version}"
}
```

以上组件均为必需，缺失会导致编译失败或运行时异常，请勿删减。

`thingsmart` 与 `thingsmart-ipcsdk` 需按版本号显式声明；其余组件版本由
`enforcedPlatform` 统一仲裁，可通过
`./gradlew :app:dependencies --configuration debugRuntimeClasspath` 查看生效版本。

---

## 四、完整 UI BizBundle 接入

需要直接使用 AI 音频业务包及完整 Demo 能力时，在业务模块中声明以下组件。

```groovy
api enforcedPlatform("com.thingclips.smart:thingsmart-BizBundlesBom:${biz_bom_version}")

api "com.thingclips.smart:thingsmart-bizbundle-wearkit"
api "com.thingclips.smart:thingsmart-bizbundle-device_activator"
api "com.thingclips.smart:thingsmart-bizbundle-qrcode_mlkit"
api("com.thingclips.smart:thingsmart-bizbundle-basekit") {
    exclude group: "com.thingclips.smart", module: "thingplugin-annotation"
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

仅接入 Native 时不要直接复制这一整段——它包含面板、配网、小程序等完整业务组件。

---

## 五、裁剪组件（可选）

```groovy
// 根 build.gradle
allprojects {
    configurations.all {
        exclude group: "com.thingclips.smart", module: 'react-native'
        exclude group: "com.thingclips.smart", module: 'thingsmart-matterlib'
        exclude group: "com.thingclips.smart", module: 'thingsmart-camera-sdk'
        exclude group: "com.thingclips.smart", module: 'thingsmart-mediaplayer-sdk'
        exclude group: "com.thingclips.smart", module: 'thingsmart-p2p-sdk'
    }
}
```

裁剪配置必须放在 `allprojects` 下；移除前先确认业务没有使用对应能力。
