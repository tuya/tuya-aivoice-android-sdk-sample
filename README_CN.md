
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

### 2. 依赖配置

根工程仓库、版本基线、Native 最小依赖和完整 UI BizBundle 依赖统一维护在
[依赖配置指南](./docs/nativesdk-dependency-guide.md)，README 不再重复 Gradle 配置。

- 只使用 Native API：按「仅 Native 接入」引入最小依赖；
- 使用完整 AI 音频业务包：按「完整 UI BizBundle 接入」引入组件。

