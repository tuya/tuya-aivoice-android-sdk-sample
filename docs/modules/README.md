# Native TTT 能力 · 接入指南

面向 Android 接入方的 SDK 使用文档。按能力模块拆分，每份都包含
接口清单、调用时序、逐参数说明、数据结构字段表、错误码与接入清单。

配套的可运行示例在 [`ai_voice/.../nativeui`](../../ai_voice/src/main/java/com/tuya/smart/ai_voice/nativeui)，
每个模块的文档都指向对应的 Demo 页面。

---

## 一、入口

| 项 | 内容 |
|---|---|
| 入口类 | `ThingAudioDetectManagerNative` |
| 获取实例 | `ThingAudioDetectManagerNative.getInstance()` |
| 包名 | `com.thingclips.smart.plugin.tuniaudiodetectmanager` |

所有能力都挂在这一个单例上，接入方只需面向它编程。

```java
ThingAudioDetectManagerNative manager = ThingAudioDetectManagerNative.getInstance();
```

---

## 二、模块索引

| 模块 | 文档 | Demo 页面 |
|---|---|---|
| 1 · 录音控制与设备能力 | [module-01](./module-01-record-control.md) | `NativeRecordActivity` |
| 2 · 转写 / 总结 | [module-02](./module-02-transcribe-summary.md) | `NativeRecordDetailActivity` |
| 3 · 文件管理 | [module-03](./module-03-file-management.md) | `NativeRecordListActivity` |
| 4 · 离线文件传输 | [module-04](./module-04-offline-transfer.md) | `NativeOfflineFileActivity` |
| 5 · 音频导入 | [module-05](./module-05-audio-import.md) | `NativeAudioImportActivity` |
| 6 · 云同步 | [module-06](./module-06-cloud-sync.md) | `NativeCloudSyncActivity` |
| 8 · 合并录音 | [module-08](./module-08-merge-record.md) | `NativeMergeRecordActivity` |
| 9 · 分享 / 悬浮球 / 快捷入口 | [module-09](./module-09-share-and-entry.md) | `NativeQuickEntryActivity`、`NativeRecordActionSheet` |
| 10 · 文本翻译 | [module-10](./module-10-text-translation.md) | 不实现 |

> **模块 7（设备 / 通道 / 流控）** 只有一个真实接口 `getEarPhoneBTConntectedStatus`，
> 它是录音流程的组成部分，已并入[模块 1](./module-01-record-control.md)；
> 另两个接口是 no-op，见[第六节](#六其他接口)。
>
> **模块 11（事件监听）** 不是独立功能，各监听器归属于它服务的模块，
> 总表见[第四节](#四事件监听总表)。

---

## 三、全局约定

### 回调类型

所有异步方法通过以下 6 种标准回调返回结果：

| 回调类型 | 方法 | 适用场景 |
|---|---|---|
| `IResultCallback` | `onSuccess()` / `onError(String code, String error)` | 无返回数据的操作 |
| `IRecordCallBack<T>` | `onSuccess(T result)` / `onError(String code, String error)` | 带返回数据的查询 |
| `IAudioImportCallBack` | `onSuccess()` / `onError(Integer code, String error)` | 音频导入。**`code` 是 `Integer`**，与其余不同 |
| `IOfflineFilesProgress` | `onProgress(DeviceOfflineFileStatus)` / `onSuccess(Long sessionId)` / `onError(String, String)` | 离线文件传输 |
| `IOperateRecordShareLinkResult` | `onSuccess(String link)` / `onError(String, String)` | 分享链接操作 |
| `ICloudSyncSwitchCallBack<P, T>` | `onSuccess(P param, T info)` / `onError(String, String)` | 云同步开关查询 |

### 线程

**回调可能在子线程**，更新 UI 前必须切回主线程。Demo 中统一走
`NativeDemoBaseActivity.runOnUi(...)`。

### 「快照 + 监听」模式

三个后台任务类能力都遵循同一套模式：任务不随页面生命周期结束，
用户可能在任务进行中切走再切回，因此**进页必须取一次快照**，之后靠监听增量更新，
**不要轮询**。

| 模块 | 快照接口 | 监听 |
|---|---|---|
| 5 · 音频导入 | `getAudioImportStatus()` 同步 | `IFileImportStatusListener` |
| 6 · 云同步 | `getCloudSyncSwitchStatus()` 异步 | `SyncObserver` |
| 8 · 合并录音 | `getFileMergeStatus()` 同步 | `IMergeStatusListener` |

漏掉快照的表现是「进页时正在跑的任务界面上看不到，直到下一次事件到达」。

### `onSuccess` 通常只表示「已提交」

录音、转写、合并、快捷入口添加等接口的 `onSuccess` 都只代表指令已下发，
真正的结果一律走对应的事件监听。界面不要在 `onSuccess` 里就宣告成功。

### 两个 ID 别用混

| ID | 类型 | 用在哪 |
|---|---|---|
| `recordTransferId` | `Long` | `updateRecordTransferResult`、`removeFileList`、转写 / 总结结果查询 |
| `recordId` | `String` | `getRecordTransferResultDetail`、`updateRecordTagResult`、`mergeRecordList`、分享链接 |

---

## 四、事件监听总表

监听**成对**使用：`add` 时注册，`remove` 时注销，**传入同一 listener 实例**。

| add / remove | listener 类型 | 触发场景 | 归属模块 |
|---|---|---|---|
| `addRecordListener` / `removeRecordListener` | `IRecordListener` | 录音状态 / 振幅 / 实时识别 / 音源切换 / 结束 | [1](./module-01-record-control.md) |
| `addNativeAbilityListener` / `removeNativeAbilityListener` | `INativeAbilityListener` | 设备能力：蓝牙 / 电量 / 音质 | [1](./module-01-record-control.md) |
| `addTransferListener` / `removeTransferListener` | `ITransferListener` | 转写前的音频上传进度 | [2](./module-02-transcribe-summary.md) |
| `addFileRecordUpdateListener` / `removeFileRecordUpdateListener` | `IRecordFileUpdateCallback<List<RecordUpdateInfo>>` | 录音条目局部 / 全量刷新 | [3](./module-03-file-management.md) |
| `registerFileProgressCallback` / `unRegisterFileProgressCallback` | `IOfflineFilesProgress` | 离线文件传输进度 | [4](./module-04-offline-transfer.md) |
| `addFileImportStatusListener` / `removeFileImportStatusListener()` ⚠️无参 | `IFileImportStatusListener` | 音频导入状态 | [5](./module-05-audio-import.md) |
| `addCloudSwitchListener` / `removeCloudSwitchListener` | `ICloudSwitchListener<CloudSyncSwitchParam, Long>` | 云同步开关变化 | [6](./module-06-cloud-sync.md) |
| `addAudioSyncObserver` / `removeAudioSyncObserver` | `SyncObserver` | 云同步上传 / 下载过程 | [6](./module-06-cloud-sync.md) |
| `addMergeStatusListener` / `removeMergeStatusListener()` ⚠️无参 | `IMergeStatusListener` | 合并任务进度 / 结果 | [8](./module-08-merge-record.md) |
| `addQuickEntryAddListener` / `removeQuickEntryAddListener()` ⚠️无参 | `IQuickEntryAddListener` | 快捷入口添加结果 | [9](./module-09-share-and-entry.md) |
| `addTranslationListener`（⚠️**无 remove**） | `ITranslationListener` | 文本翻译 TTS 合成完成 | [10](./module-10-text-translation.md) |
| `addPushRouteInfoListener` / `removePushRouteInfoListener` | `IPushRouteInfoListener` | 推送路由跳转 | 见下 |

### 三个无参 remove

`removeFileImportStatusListener()`、`removeMergeStatusListener()`、
`removeQuickEntryAddListener()` **不接受参数，会移除全部已注册的同类监听**。
多个页面同时注册同一类监听时，任一页面退出都会把其他页面的监听一并摘掉——
这类能力建议只在一处注册。

### `addTranslationListener` 无对应 remove

底层未提供移除方法，注册后会一直存活。实现里**不要持有 Activity 强引用**，
否则会造成内存泄漏。

### `IPushRouteInfoListener`（推送路由）

不属于任何业务模块，是**推送点击后的落地页路由分发**：用户点了「转写已完成」这类通知，
SDK 把该跳哪里告诉 App，由 App 自行 `startActivity`。

| 回调 | 参数 |
|---|---|
| `onPush(PushRouteInfo info)` | 见下表 |

**`PushRouteInfo`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `pushType` | `String` | 推送事件类型，如 `RecordTranscribe` |
| `recordId` | `String` | 目标录音 ID |
| `tab` | `String` | 详情页应打开的 Tab：`transcribe` / `summarize` |
| `extraInfo` | `String` | 额外信息 JSON 字符串（预留字段） |

宿主 App 若不接管推送跳转，可不实现。**Demo 未实现。**

---

## 五、Demo 工程结构

| 文件 | 说明 |
|---|---|
| `base/NativeDemoBaseActivity` | 演示页基类：标题栏、内容容器、事件日志区、设备选择器、`runOnUi` |
| `NativeApiListActivity` | 能力清单导航页 |
| `NativeRecordActivity` | 模块 1 · 录音控制 |
| `NativeRecordListActivity` / `NativeRecordListAdapter` | 模块 3 · 列表、搜索、空间统计 |
| `NativeRecordDetailActivity` | 模块 2 · 转写 / 总结，兼模块 3 详情 |
| `NativeRecordActionSheet` | 模块 3 重命名 / 标签 / 删除，模块 9 分享，模块 6 按需下载 |
| `NativeOfflineFileActivity` | 模块 4 · 离线文件传输 |
| `NativeAudioImportActivity` | 模块 5 · 音频导入 |
| `NativeCloudSyncActivity` | 模块 6 · 云同步 |
| `NativeMergeRecordActivity` | 模块 8 · 合并录音 |
| `NativeQuickEntryActivity` | 模块 9 · 快捷入口 |
| `business/CloudSyncSwitchBusiness` | atop：云同步开关写入 |
| `business/AudioContentBusiness` | atop：文件重命名、已读状态写云端 |

### 有几件事 SDK 不管

以下能力**不在 SDK 内**，需接入方自行调 atop 业务接口：

| 能力 | atop 接口 | 说明 |
|---|---|---|
| 云同步开关写入 | `m.wearable.sync.switch.save` | 全量提交，见[模块 6](./module-06-cloud-sync.md) |
| 文件重命名（云端） | `m.wearable.audio.record.add` | SDK 只写本地，见[模块 3](./module-03-file-management.md) |
| 已读状态（云端） | `m.wearable.audio.record.read.update` | 同上 |
| 转写正文编辑（云端） | `m.wearable.audio.content.edit` | **Demo 未实现**，见[模块 2](./module-02-transcribe-summary.md) |
| 总结正文编辑（云端） | `m.wearable.audio.summary.edit` | 同上 |

---

## 六、其他接口

不归属任何业务流程的三个接口：

| 方法 | 说明 |
|---|---|
| `operateEventLimit(String eventName, boolean operate, IResultCallback callback)` | ⚠️ **no-op**。Native 版不做事件限流，调用即 `onSuccess`，保留仅为接口对齐 |
| `readyToSetupNativeChannel(IResultCallback callback)` | ⚠️ **no-op**。Android 无对应动作（仅 iOS 需要），直接 `onSuccess` |
| `operateRecordingFloat(boolean isVisible, IResultCallback callback)` | 控制录音悬浮球显隐，见[模块 9](./module-09-share-and-entry.md)。**Demo 未实现** |

`operateAudioFileSafePath` 归在文件管理下，AI 笔记业务未使用、Demo 未实现，
字段说明见[模块 3](./module-03-file-management.md)。
