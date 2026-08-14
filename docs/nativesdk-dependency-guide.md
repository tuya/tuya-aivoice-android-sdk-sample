# AI 录音 Native 接入 · 依赖配置

适用于**只使用 AI 录音 Native 接口**（`ThingAudioDetectManagerNative`，UI 自行实现）的接入方式。
不适用于跳转 RN 面板、设备配网、IPC、Matter 等场景。

配套示例见本仓库 `ai_voice` 模块，接口文档见 [`docs/modules/`](./modules/README.md)。

---

## 一、完整配置

```gradle
// 根 build.gradle
ext {
    sdk_version          = '7.8.0'
    ipc_sdk_version 	   = '7.8.1'
    biz_bom_version      = '7.8.19'
    audio_engine_version = '7.8.1'
}
```

```gradle
// 业务模块 build.gradle
dependencies {
    api enforcedPlatform("com.thingclips.smart:thingsmart-BizBundlesBom:${biz_bom_version}")

    api "com.thingclips.smart:thingsmart:${sdk_version}"
    api "com.thingclips.smart:thingsmart-bizbundle-wearkit"
    api "com.thingclips.smart:thingsmart-bizbundle-family"
    api "com.thingclips.smart:thingsmart-bizbundle-basekit"
    api "com.thingclips.smart:thingsmart-ipcsdk:${ipc_sdk_version}"
}
```

以上组件均为必需，缺失会导致编译失败或运行时异常，请勿删减。

后 7 个组件不由 BOM 管理版本，需按上述版本号显式声明。其余组件版本由
`enforcedPlatform` 统一仲裁，可通过
`./gradlew :app:dependencies --configuration debugRuntimeClasspath` 查看生效版本。

---

## 二、裁剪 组件（可选）

```gradle
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

需配置在 `allprojects` 下；
