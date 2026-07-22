# ThingAudioDetectManagerNative 接口参考手册

> Native（Android）侧 TTT 能力 API 的完整字段参考。
> 开发者只需面向 `ThingAudioDetectManagerNative` 单例编程。
> 实战流程见 [wearkit-ttt-native-sop.md](./wearkit-ttt-native-sop.md)。

| 项目 | 信息 |
| --- | --- |
| 入口类 | `ThingAudioDetectManagerNative` |
| 获取实例 | `ThingAudioDetectManagerNative.getInstance()` |
| 包名 | `com.thingclips.smart.plugin.tuniaudiodetectmanager` |

---

## 目录

- [回调类型](#回调类型)
- [模块 1 · 录音控制](#模块-1--录音控制)
- [模块 2 · 转写 / 总结](#模块-2--转写--总结)
- [模块 3 · 文件管理](#模块-3--文件管理)
- [模块 4 · 离线文件传输](#模块-4--离线文件传输)
- [模块 5 · 音频导入](#模块-5--音频导入)
- [模块 6 · 云同步](#模块-6--云同步)
- [模块 7 · 设备 / 通道 / 流控](#模块-7--设备--通道--流控)
- [模块 8 · 合并录音](#模块-8--合并录音)
- [模块 9 · 分享 / 悬浮 / 快捷入口](#模块-9--分享--悬浮--快捷入口)
- [模块 10 · 文本翻译](#模块-10--文本翻译)
- [模块 11 · 事件监听](#模块-11--事件监听)
- [数据字典（Bean 全字段）](#数据字典bean-全字段)

---

## 回调类型

所有异步方法均通过以下标准回调返回结果：

| 回调类型 | 方法 | 适用场景 |
| --- | --- | --- |
| `IResultCallback` | `onSuccess()` / `onError(String code, String error)` | 无返回数据的操作 |
| `IRecordCallBack<T>` | `onSuccess(T result)` / `onError(String code, String error)` | 带返回数据的查询 |
| `IAudioImportCallBack` | `onSuccess()` / `onError(Integer code, String error)` | 音频导入 |
| `IOfflineFilesProgress` | `onProgress(DeviceOfflineFileStatus)` / `onSuccess(Long sessionId)` / `onError(String, String)` | 离线文件传输进度 |
| `IOperateRecordShareLinkResult` | `onSuccess(String link)` / `onError(String, String)` | 分享链接操作 |
| `ICloudSyncSwitchCallBack<P, T>` | `onSuccess(P param, T time)` / `onError(String, String)` | 云同步开关查询 |

> 回调可能在子线程，更新 UI 请切回主线程。

---

## 模块 1 · 录音控制

### `recordTransferTask(String deviceId) → RecordStatusBean`
查询当前设备是否存在进行中的录音转写任务。**同步返回**，无任务返回 `null`。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `deviceId` | `String` | 是 | 设备 ID；手机录音传 `"PHONE"` |

### `startAudioRecording(String deviceId, RecordParamsV2 params, IResultCallback callback)`
开始录音转写。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `deviceId` | `String` | 是 | 设备 ID |
| `params` | [`RecordParamsV2`](#recordparamsv2) | 是 | 录音参数 |
| `callback` | `IResultCallback` | 否 | 结果回调 |

### `updateParams(String deviceId, RecordParamsV2 params, IResultCallback callback)`
录音过程中更新参数。**立即返回 `onSuccess`**，不等底层结果；参数真正生效由 `onRecordStatusUpdate` 事件反映。

### `pauseRecordTransfer / resumeRecordTransfer / stopRecordTransfer(String deviceId, IResultCallback callback)`
暂停 / 恢复 / 停止录音转写。

### `switchRecordChannel(String deviceId, int recordChannel, IResultCallback callback)`
切换收音通道。

| 参数 | 取值 | 说明 |
| --- | --- | --- |
| `recordChannel` | `0` | 未指定 |
| | `1` | BT（耳机） |
| | `2` | Micro（手机麦） |

---

## 模块 2 · 转写 / 总结

### `processRecordTransferResult(TranscribeParam param, IResultCallback callback)`
对录音记录发起转写 / 总结处理。**`onSuccess` 仅表示任务已提交，非完成**。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `param` | [`TranscribeParam`](#transcribeparam) | 是 | 转写参数 |

### `getRecordTransferRecognizeResult(long recordTransferId, int from, IRecordCallBack<String> callback)`
查询转写（识别）结果文本。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `recordTransferId` | `long` | 文件 ID |
| `from` | `int` | `0` 本地 / `1` 云端 |

### `saveRecordTransferRecognizeResult(long recordTransferId, String text, IResultCallback callback)`
保存转写结果。

### `getRecordTransferSummaryResult(long recordTransferId, int from, IRecordCallBack<String> callback)`
查询总结结果文本。参数同 `getRecordTransferRecognizeResult`。

### `saveRecordTransferSummaryResult(long recordTransferId, String text, IResultCallback callback)`
保存总结结果。

### `getRecordTransferRealTimeResult(String fileId, String recordId, String asrId, IRecordCallBack<List<RecordTransferRealTimeResult>> callback)`
查询实时 / 历史转写句列表。三个参数均可为 `null`。

### `saveRecordTransferRealTimeRecognizeResult(long asrId, String text, String asr, String translate, IResultCallback callback)`
按 `asrId` 更新实时转写单句。

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `asrId` | `long` | 是 | 单句 ID |
| `text` | `String` | 否 | 完整显示文案 |
| `asr` | `String` | 否 | 原始 ASR 结果 |
| `translate` | `String` | 否 | 翻译结果 |

---

## 模块 3 · 文件管理

### `getRecordTransferResultList(FilesParam param, IRecordCallBack<List<RecordTransferResultBean>> callback)`
获取录音转写列表。

### `getRecordTransferResultDetail(String recordId, int amplitudeMaxCount, IRecordCallBack<RecordTransferResultBean> callback)`
按业务 `recordId` 获取单条详情。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `recordId` | `String` | 业务录音 ID |
| `amplitudeMaxCount` | `int` | 振幅采样上限，`0` 表示全量 |

### `searchRecordTransferResult(AudioSearchMixParams param, IRecordCallBack<ArrayList<AudioSearchMixItem>> callback)`
按关键词搜索标题 / 标签 / 总结（支持高亮）。

### `updateRecordTransferResult(...)`
更新文件元信息。完整参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `recordTransferId` | `long` | 是 | 文件 ID |
| `name` | `String` | 否 | 文件名称，`null` 不更新 |
| `status` | `Integer` | 否 | 同步状态：`0`未上传/`1`上传中/`2`已上传/`3`失败 |
| `visit` | `String` | 否 | 访问状态；兼容 `"true"`/`"false"`/数字字符串 |
| `remove` | `Boolean` | 否 | 是否移入回收站 |
| `transfer` | `Integer` | 否 | 转录状态：`0`未转录/`1`中/`2`已转录/`3`失败 |
| `directoryId` | `Long` | 否 | 目录 ID |
| `storageKey` | `String` | 否 | 云端存储 key |
| `callback` | `IResultCallback` | 否 | 结果回调 |

### `removeFileList(List<Long> fileIds, boolean isDeleteAll, IResultCallback callback)`
批量删除 / 清本地音源 / 一键清空。

| `isDeleteAll` | `fileIds` | 行为 |
| --- | --- | --- |
| `true` | — | 彻底删除（本地 + 云端） |
| `false` | 非空 | 仅删本地音源 |
| `false` | 空 / `null` | 清空所有本地缓存音源 |

### `getAudioFilesSize(IRecordCallBack<Integer> callback)`
获取本地音频占用空间（字节）。

### `updateRecordTagResult(UpdateRecordTagResultParams param, IResultCallback callback)`
更新标签。

### `operateAudioFileSafePath(boolean isGetPath, String audioPath, IRecordCallBack<String> callback)`
获取或删除小程序合规分享路径。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `isGetPath` | `boolean` | `true` 获取 / `false` 删除 |
| `audioPath` | `String` | earphone 目录下音频文件地址 |

---

## 模块 4 · 离线文件传输

### `getDeviceOfflineFileStatus(String deviceId, IRecordCallBack<DeviceOfflineFileStatus> callback)`
查询设备离线文件列表与下载会话状态。

### `loadOfflineFile(String deviceId, int channel, long sessionId, IOfflineFilesProgress callback)`
发起 / 继续离线文件下载任务。

| 参数 | 取值 | 说明 |
| --- | --- | --- |
| `channel` | `0` | 未指定 |
| | `1` | BLE |
| | `2` | AP（热点） |
| `sessionId` | `long` | `0` 开启新任务；非 0 续传 |

### `switchModeLoadOfflineFile(String deviceId, int channel, IOfflineFilesProgress callback)`
切换 AP / BLE 模式继续传输。

---

## 模块 5 · 音频导入

| 方法 | 回调 | 说明 |
| --- | --- | --- |
| `startImport(IAudioImportCallBack callback)` | `IAudioImportCallBack` | 开始导入本地音频 |
| `retryImport(IAudioImportCallBack callback)` | `IAudioImportCallBack` | 重试导入 |
| `cancelImport(IAudioImportCallBack callback)` | `IAudioImportCallBack` | 取消导入 |
| `cancelRetry(IAudioImportCallBack callback)` | `IAudioImportCallBack` | 取消重试 |
| `getAudioImportStatus() → FileImportStatusEventApp` | 同步返回 | 查询当前导入状态快照，可能为 `null` |

---

## 模块 6 · 云同步

### `getCloudSyncSwitchStatus(ICloudSyncSwitchCallBack<CloudSyncSwitchParam, Long> callback)`
获取云同步开关与同步类型（异步）。

### `syncNoteRecord(IResultCallback callback)`
同步上传 Note（文件 + 音频），并下载云端 Note。

### `syncDownloadNoteAudio(Long fileId, String recordId)`
按需下载指定 Note 音频。**同步无返回**。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `fileId` | `Long` | 文件 ID，`null` 时传 `-1` |
| `recordId` | `String` | 业务录音 ID |

---

## 模块 7 · 设备 / 通道 / 流控

### `getEarPhoneBTConntectedStatus(String devId, IRecordCallBack<BTConnectedStatus> callback)`
查询耳机蓝牙连接状态。

### `operateEventLimit(String eventName, boolean operate, IResultCallback callback)`
⚠️ **no-op**。native 版不做事件限流，调用即 `onSuccess`，保留仅为接口对齐。

### `readyToSetupNativeChannel(IResultCallback callback)`
⚠️ **no-op**。Android 无对应动作（仅 iOS），直接 `onSuccess`。

---

## 模块 8 · 合并录音

| 方法 | 说明 |
| --- | --- |
| `mergeRecordList(List<String> recordIds, IResultCallback callback)` | 批量合并录音 |
| `cancelMergeRecordList(IResultCallback callback)` | 取消合并任务 |
| `getFileMergeStatus() → MergeStatusEvent` | 同步返回当前合并状态快照 |

---

## 模块 9 · 分享 / 悬浮 / 快捷入口

### `operateRecordShareLink(String recordId, List<String> shareType, long expireTime, int shareStatus, String password, IOperateRecordShareLinkResult callback)`
保存或关闭录音分享链接。

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `recordId` | `String` | 录音 ID |
| `shareType` | `List<String>` | 分享类型数组 |
| `expireTime` | `long` | 过期时间戳 |
| `shareStatus` | `int` | `1` 分享中 / `2` 关闭 |
| `password` | `String` | 访问密码，可 `null` |

### `operateRecordingFloat(boolean isVisible, IResultCallback callback)`
控制 App 录音悬浮球显隐。

### `getQuickEntryList(IRecordCallBack<List<LauncherStateBean>> callback)`
获取组件 / 快捷图标 / 磁贴添加状态。

### `setQuickEntryEnabled(int type, int componentId, int enabled, IResultCallback callback)`
按类型开启或关闭入口。

| 参数 | 说明 |
| --- | --- |
| `type` | Android：`101`/`102`/`103`（组件/快捷图标/磁贴） |
| `componentId` | 组件 ID |
| `enabled` | `1` 开启 / `0` 关闭 |

---

## 模块 10 · 文本翻译

### `startTranslate(String originText, String originLanguage, String targetLanguage, IRecordCallBack<TranslationResult> callback)`
发起文本翻译。

### `getTranslateFileList(TranslationFilesParams param, IRecordCallBack<List<TranslationFile>> callback)`
获取翻译 / 录音历史列表。

### `getTranslateFileDetail(long fileId, int amplitudeMaxCount, IRecordCallBack<TranslationFile> callback)`
获取翻译记录详情。

### `sendToNote(long fileId, IRecordCallBack<String> callback)`
将翻译记录发送到 AI Note。

### `batchRemoveTranslationFiles(List<Long> fileIds, IRecordCallBack<Boolean> callback)`
批量删除翻译记录。

---

## 模块 11 · 事件监听

事件监听**成对**使用：`add` 时注册，`remove` 时注销，**传入同一 listener 实例**。

| add / remove 方法对 | listener 类型 | 触发场景 |
| --- | --- | --- |
| `addRecordListener` / `removeRecordListener` | `IRecordListener` | 录音状态/振幅/实时识别/音源切换/结束 |
| `addPushRouteInfoListener` / `removePushRouteInfoListener` | `IPushRouteInfoListener` | 推送路由跳转 |
| `addTransferListener` / `removeTransferListener` | `ITransferListener` | 转写文件上传进度 |
| `addCloudSwitchListener` / `removeCloudSwitchListener` | `ICloudSwitchListener<CloudSyncSwitchParam, Long>` | 云同步开关变化 |
| `addFileRecordUpdateListener` / `removeFileRecordUpdateListener` | `IRecordFileUpdateCallback<List<RecordUpdateInfo>>` | 录音条目局部/全量刷新 |
| `addTranslationListener`（无 remove ⚠️） | `ITranslationListener` | 文本翻译 TTS 完成 |
| `addQuickEntryAddListener` / `removeQuickEntryAddListener()` | `IQuickEntryAddListener` | 快捷入口添加结果 |
| `registerFileProgressCallback` / `unRegisterFileProgressCallback` | `IOfflineFilesProgress` | 离线文件传输进度 |
| `addFileImportStatusListener` / `removeFileImportStatusListener()` | `IFileImportStatusListener` | 音频导入状态 |
| `addMergeStatusListener` / `removeMergeStatusListener()` | `IMergeStatusListener` | 合并任务进度/结果 |
| `addNativeAbilityListener` / `removeNativeAbilityListener` | `INativeAbilityListener` | 设备能力：BT/电量/音质 |
| `addAudioSyncObserver` / `removeAudioSyncObserver` | `SyncObserver` | 云同步上传/下载综合状态 |

> ⚠️ `addTranslationListener` 底层无对应 remove，使用时留意。
> ⚠️ `removeQuickEntryAddListener` / `removeFileImportStatusListener` / `removeMergeStatusListener` 为无参移除（移除全部）。

### listener 回调方法速查

**`IRecordListener`**（录音核心事件）

| 方法 | 说明 |
| --- | --- |
| `onRecordStatusUpdate(String deviceId, RecordStatusBean bean)` | 录音状态变更 |
| `onRecordAmplitudeUpdate(String deviceId, int channel, double amplitude)` | 振幅更新（波形） |
| `onRealTimeStatusUpdate(RealTimeTransferStatus status)` | 实时转写推送 |
| `onRecordSwitchAudioSourceEvent(String devId, int recordType, int audioSource)` | 音源切换 |
| `onRecordFinish(String deviceId)` | 录音正常结束（code=0） |
| `onRecordErrorFinish(String deviceId, int errorCode, String errorMsg)` | 录音异常结束 |

**`IRecordFileUpdateCallback<List<RecordUpdateInfo>>`**（文件数据变更）

| 方法 | 说明 |
| --- | --- |
| `onUpdate(List<RecordUpdateInfo> infos)` | 状态变化（转写完成等） |
| `onRecordOperate(String operate, List<RecordUpdateInfo> infos)` | 文件新增/删除/修改，`operate` 见 `RecordOperateDef` |
| `onRecordListSyncSuccess()` | 云同步完成 → 建议全量刷新 |
| `onUpdateWitheTags(List<RecordUpdateInfo> infos)` | 仅 tags 变更 |

**其它 listener**（单方法，参数为对应 Bean）

| listener | 回调方法 | 参数 Bean |
| --- | --- | --- |
| `IPushRouteInfoListener` | `onPush(PushRouteInfo)` | [`PushRouteInfo`](#pushrouteinfo) |
| `ITransferListener` | `onRecordTransferFileUploadEvent(String fileId, int progress, int status)` | 原子参数 |
| `ICloudSwitchListener<P,T>` | `onRefreshSwitchState(P, T, CloudSyncRefreshType)` / `onError(String, String)` | [`CloudSyncSwitchParam`](#cloudsyncswitchparam) + `Long` |
| `ITranslationListener` | `onTextTranslateTtsCompleteEvent(long fileId, String ttsPath)` | 原子参数 |
| `IQuickEntryAddListener` | `onAddResult(int type, int id, boolean success)` | 原子参数 |
| `IFileImportStatusListener` | `onStatusUpdate(FileImportStatusEventApp)` | [`FileImportStatusEventApp`](#fileimportstatuseventapp) |
| `IMergeStatusListener` | `onStatusUpdate(MergeStatusEvent)` | [`MergeStatusEvent`](#mergestatusevent) |
| `INativeAbilityListener` | `onBTConnectChange(BTConnectedStatus)` / `onPhoneBatteryChange(PhoneBatteryInfo)` / `onRecordQualityChange(RecordQualityInfo)` | 见各自 Bean |

---

## 数据字典（Bean 全字段）

### `RecordStatusBean`
录音状态（`recordTransferTask` 返回；`IRecordListener.onRecordStatusUpdate` 回调）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `status` | `Int` | 录音状态：`0`待开始/`1`录音中/`2`暂停/`3`结束 |
| `duration` | `Long` | 录音时长（毫秒） |
| `type` | `Int?` | 录音类型：`0`电话/`1`会议 |
| `transferType` | `Int?` | 转写模式：`0`文件转写/`1`实时转写 |
| `needTranslate` | `Boolean?` | 是否需要翻译 |
| `originalLanguage` | `String?` | 源语言 |
| `targetLanguage` | `String?` | 目标语言 |
| `currentRealTimeAsrId` | `String?` | 当前实时转写 asrId |
| `recordId` | `String?` | 实时转写记录 ID |
| `devId` | `String?` | 设备 ID |
| `audioSource` | `Int?` | 音频源 |
| `needTts` | `Boolean?` | 是否需要 TTS |
| `businessType` | `Int?` | 业务类型：`0`Note/`1`翻译 |

### `RecordParamsV2`
录音参数（`startAudioRecording` / `updateParams` 入参）。

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `updateParams` | `Boolean?` | `false` | 是否为更新参数（`updateParams` 方法内部置 `true`） |
| `audioSource` | `Int?` | `null` | 音频源（见下表） |
| `audioSourceList` | `List<Int>?` | `null` | 多路音频源，如 `[0, 20]` |
| `recordMode` | `Int?` | `null` | 录音模式：`0`电话/`1`现场(会议)/`2`面对面 |
| `f2fChannel` | `Int?` | `null` | 面对面通道：`0`默认/`1`左/`2`右 |
| `needAsr` | `Boolean` | `false` | 是否需要实时转写 |
| `needTranslate` | `Boolean` | `false` | 是否需要翻译（同传强制 true） |
| `needTts` | `Boolean` | `false` | 是否需要 TTS |
| `needAmplitude` | `Boolean` | `false` | 是否需要振幅回调 |
| `originalLanguage` | `String?` | `null` | 源语言 |
| `targetLanguage` | `String?` | `null` | 目标语言 |
| `agentId` | `String?` | `null` | 智能体/渠道 ID |
| `recordTransfer3AConfig` | `Audio3AConfig?` | `null` | 3A 配置 |
| `ttsConfig` | `TTSConfig?` | `null` | TTS 配置（2.3.0+） |
| `ttsConfigList` | `List<TTSConfig>?` | `null` | TTS 多输出源配置 |
| `businessType` | `Int?` | `null` | `0`AI Note/`1`Translation |
| `autoRecognize` | `Boolean?` | `null` | 是否自动识别语言 |
| `startLivingStatus` | `Int` | `0` | 直播状态：`0`仅同传/`1`开启直播/`2`直播中变更 |

**`audioSource` 取值**

| 值 | 含义 |
| --- | --- |
| `0` | 系统蓝牙 16K 单声道 |
| `1` | 系统 MIC 16K 单声道 |
| `20` | Pro 耳机 16K 单声道 |
| `21` | Pro 耳机 16K 双声道 |
| `22` | Pro 耳机 32K 单声道（卡片） |
| `40` | 卡片 16K 单声道 |
| `41` | 卡片 16K 双声道 |

### `Audio3AConfig`
3A 音频处理配置。

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `ansEnable` | `Boolean` | — | 降噪开关 |
| `ansLevel` | `Int` | `1` | 降噪等级 |
| `agcEnable` | `Boolean` | — | 自动增益开关 |
| `aecEnable` | `Boolean` | — | 回声消除开关 |

### `TTSConfig`
TTS 输出配置。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `devId` | `String?` | 输出到设备时传设备 ID；BT/MIC 传 `null` |
| `output` | `TTSOutput` | 输出源：`0`默认不开启/`1`MIC/`2`BT/`3`设备 |
| `encode` | `TTSEncode` | 编码：`0`默认/`1`opus_silk/`2`opus_celt |
| `channel` | `TTSOutputChannel` | 输出通道：`0`默认双耳/`1`左耳/`2`右耳 |

### `TranscribeParam`
转写 / 总结入参（`processRecordTransferResult`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `fileId` | `Long` | 文件 ID |
| `template` | `String?` | 总结模板 |
| `transferType` | `Int?` | `0`文件转写/`1`实时转写 |
| `audioLang` | `String?` | ASR 目标语言 |
| `transLang` | `String?` | 翻译目标语言 |
| `summaryLang` | `String?` | 总结输出语言（默认同转写） |
| `enableSpeaker` | `Boolean` | 是否说话人分离 |
| `ownerId` | `String?` | 家庭 ID（gid） |
| `devId` | `String?` | 设备 ID |
| `key` | `String?` | recordId |
| `objectKey` | `String?` | 对象存储 key |
| `duration` | `Int?` | 音频时长（秒） |
| `channelMode` | `String?` | 声道模式 |

### `FilesParam`
列表查询入参（`getRecordTransferResultList`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `directoryId` | `Long?` | 目录 ID，`null` 查所有目录 |
| `recordType` | `Int?` | 音频类型：`0`电话/`1`会议，`null` 查全部 |
| `deviceId` | `String?` | 设备 ID，`null` 查所有设备 |
| `transfer` | `Int?` | 转写状态：`0`未/`1`中/`2`成功/`3`失败，`null` 查全部 |
| `source` | `Int?` | 来源：`0`app/`1`设备，`null` 查全部 |
| `remove` | `Boolean?` | 是否回收站，`null` 查全部 |
| `orderBy` | `Int?` | 排序字段：`0`fileId/`1`recordTime/`2`updateAt |
| `asc` | `Int?` | `0`降序/`1`升序 |
| `lastFileId` | `Int?` | 分页游标（上一页最后一条 ID），`null`/`0` 为第一页 |
| `pageSize` | `Int?` | 分页大小，`null`/`0` 不分页 |

### `RecordTransferResultBean`
列表 / 详情返回（30+ 字段，全 public）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `recordTransferId` | `Long` | 文件 ID（业务主键，分页游标用此） |
| `directoryId` | `Long` | 目录 ID |
| `deviceUniqueId` | `String` | 设备生成的录音文件唯一标识 |
| `name` | `String` | 文件名称 |
| `recordTime` | `Long` | 录音时间（秒，时间戳） |
| `duration` | `Long` | 录音时长（毫秒） |
| `recordType` | `Int` | 音频类型：`0`电话/`1`现场+实时/`2`、`3`面对面/`4`文本翻译/`5`音频导入 |
| `audioFormat` | `Int` | 音频格式 |
| `deviceId` | `String` | 设备 ID |
| `filePath` | `String?` | 录音文件路径 |
| `wavFilePath` | `String?` | ⚠️已废弃，用 `filePath` |
| `amplitudes` | `String?` | 振幅字符串（逗号分隔） |
| `status` | `Int` | 同步状态：`0`未上传/`1`上传中/`2`已上传/`3`失败 |
| `visit` | `Int` | 访问：`0`未读/`1`已读/`2`转录未读/`3`转录已读 |
| `remove` | `Boolean` | 是否回收站 |
| `storageKey` | `String?` | 云端存储 key |
| `transfer` | `Int` | 转录：`0`未/`1`中/`2`已/`3`失败 |
| `summary` | `Int` | 总结：`1`未/`2`中/`3`成功/`4`失败（`0`兼容老数据） |
| `source` | `Int` | 音频源（与 audioSource 一致） |
| `transferType` | `Int` | 转写模式：`0`文件/`1`实时 |
| `needTranslate` | `Boolean` | 是否需要翻译 |
| `originalLanguage` | `String?` | 源语言 |
| `targetLanguage` | `String?` | 目标语言 |
| `recordId` | `String?` | 实时转写记录 ID |
| `agentId` | `String?` | 智能体 ID |
| `cloudTranscription` | `Boolean` | 是否云端转写 |
| `translateState` | `Int` | 翻译状态 |
| `transcriptionStatus` | `Int` | ASR 覆盖状态：`0`未知/`1`无/`2`部分/`3`完整 |
| `cloudSyncStatus` | `Int?` | UI 同步状态（`0`/`5`/`10`/`15`/`20`/`-10`/`-20`） |
| `isFromCloud` | `Boolean` | 是否云同步记录 |
| `linkShared` | `Int` | 分享：`0`未/`1`已 |
| `summaryImageStatus` | `Int` | 总结配图：`1`未/`2`中/`3`成功/`4`失败 |
| `noteFileConvertState` | `Int` | note 转化：`0`成功/`1`中/`2`失败 |
| `offlineUploadProgress` | `Int` | 离线上传进度（0-100） |
| `offlineUploadStatus` | `Int` | 离线上传状态：`-1`未/`0`等待/`1`中/`2`完成/`3`失败/`4`取消 |
| `tags` | `List<String>?` | 标签列表 |

### `RecordTransferRealTimeResult`
实时 / 历史转写句（`getRecordTransferRealTimeResult` 返回列表元素）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `asrId` | `Long` | 单句 ID |
| `recordTransferId` | `Long` | 文件 ID |
| `beginOffset` | `Long` | 开始偏移（毫秒） |
| `endOffset` | `Long` | 结束偏移（毫秒） |
| `text` | `String` | 显示文案 |
| `asr` | `String?` | 原始 ASR 文案 |
| `translate` | `String?` | 翻译文案 |
| `requestId` | `String` | 请求 ID |
| `recordId` | `String` | 实时转写记录 ID |
| `channel` | `Int` | 声道（会议0；电话0近端/1远端；同传0左/1右） |
| `status` | `Int` | 转录状态：`0`未/中、`1`成功、`2`失败 |
| `businessType` | `Int` | `0`Note/`1`翻译 |
| `ttsPath` | `String` | TTS 音频路径 |

### `RealTimeTransferStatus`
实时转写事件（`IRecordListener.onRealTimeStatusUpdate` 回调）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `deviceId` | `String` | 设备 ID |
| `recordId` | `String` | 实时转写记录 ID |
| `requestId` | `String` | 请求 ID |
| `asrId` | `Long` | 单句 ID |
| `channel` | `Int` | 声道 |
| `phase` | `Int` | 阶段：`0`任务/`1`asr/`2`text/`3`skill/`4`tts |
| `status` | `Int` | 阶段状态：`0`未开启/`1`进行中/`2`结束/`3`取消 |
| `text` | `String` | 文本 |
| `beginOffset` | `Long` | 开始时间（毫秒） |
| `endOffset` | `Long` | 结束时间（毫秒） |
| `translateText` | `String` | 翻译文本 |
| `translateStatus` | `Int` | 翻译状态 |
| `errorCode` | `Int` | 错误码 |
| `errorMessage` | `String?` | 错误消息 |

### `AudioSearchMixParams`
搜索入参（`searchRecordTransferResult`）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `keyword` | `String?` | 关键词/标签（预留） |
| `content` | `String?` | 搜索内容/标签 |
| `pageNum` | `Int?` | 页码，默认 1 |
| `pageSize` | `Int?` | 页大小，默认 20 |

### `AudioSearchMixItem`
搜索单条结果。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `recordId` | `String?` | 笔记 ID |
| `title` | `String?` | 标题（可含 `<em>` 高亮） |
| `tags` | `List<String>?` | 标签列表（命中者带高亮且置顶） |
| `summary` | `String?` | 总结摘要片段（可含高亮） |
| `content` | `String?` | 转写内容摘要片段（可含高亮） |
| `score` | `Int?` | 综合得分 |
| `bizTime` | `Long?` | 业务时间戳（毫秒） |
| `audioSource` | `Int` | 音频来源（默认 `-1`） |
| `isFromCloud` | `Boolean` | 是否云同步记录 |
| `recordTime` | `Long` | 录音时间（秒） |
| `duration` | `Long` | 录音时长（毫秒） |

### `UpdateRecordTagResultParams`
标签更新入参。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `recordId` | `String` | 录音 ID |
| `bizType` | `Int` | `0`新增/`1`删除/`2`排序 |
| `tags` | `List<String>?` | 标签列表 |

### `DeviceOfflineFileStatus`
离线文件状态。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `status` | `Int` | 下载状态：`0`未开始/`1`下载中/`2`已结束 |
| `sessionId` | `Long` | 任务 ID（`0` 未开始） |
| `response` | `OfflineFilesResponse` | 资源详情 |
| `errorCode` | `Int` | 过程中错误码 |

### `OfflineFilesResponse`
离线文件下载详情。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `channel` | `Int` | 下载通道：`0`未指定/`1`ble/`2`ap |
| `apConnectState` | `Int` | AP 状态：`0`未开启/`1`已开启/`2`已连接 |
| `speed` | `Double` | 下载速度（KB/s） |
| `total` | `Int` | 可下载文件总数 |
| `size` | `Int` | 已下载文件总数 |
| `curFile` | `FileDigest` | 当前下载中的文件 |
| `files_waiting` | `List<FileDigest>` | 待下载文件 |
| `files_failed` | `List<FileDigest>` | 下载失败文件 |
| `files_transform` | `List<FileDigest>` | 已传输转换中文件 |
| `files_successed` | `List<FileDigest>` | 传输转换完成文件 |
| `remainingDownloadTime` | `Int` | 剩余时间（秒） |
| `downloadedFileProgress` | `Int` | 下载进度（0-100） |

### `FileDigest`
文件摘要。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `fileId` | `Long` | 文件 ID |
| `fileType` | `Int` | 文件类型 |
| `progress` | `Double` | 进度（0-100%） |
| `timeStamp` | `Long` | 时间戳 |
| `fileName` | `String` | 文件名 |
| `fileDuring` | `Long` | 内容时长 |

### `FileImportStatusEventApp`
导入状态。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `status` | `Int` | `0`未开始/`1`导入中/`2`完成/`3`中断/`4`分享导入失败 |
| `errorCode` | `Int?` | 错误码（status=3 时） |
| `errorMessage` | `String?` | 错误消息 |
| `totalFileCount` | `Int?` | 总文件数 |
| `successCount` | `Int?` | 成功导入数 |
| `failedFiles` | `List<FailedFileInfo>?` | 失败文件列表 |
| `timestamp` | `Long` | 时间戳 |

### `CloudSyncSwitchParam`
云同步开关状态。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `enabled` | `Boolean` | 开关状态 |
| `syncType` | `Int?` | 同步类型：`0`全网络/`1`仅 Wi-Fi |
| `modifyTime` | `Long` | 更新时间（默认 `-1`） |

### `MergeStatusEvent`
合并任务状态。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `status` | `Int` | `0`未开始/`1`执行中/`2`结束/`3`异常 |
| `subStatus` | `Int?` | 执行中子状态：`10`下载中/`20`合并中 |
| `errorCode` | `Int?` | 错误码（status=3 时，20220-20230） |
| `progress` | `Int?` | 进度 0-100 |
| `recordId` | `String?` | 合并结果 recordId |

### `TaskStatusParam`
转写任务状态查询入参。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskType` | `Int?` | 任务类型 |
| `deviceId` | `String` | 设备 ID |
| `fileIds` | `List<String>` | 查询的文件 ID 列表 |
| `keys` | `String?` | 额外 key |

### `TaskStatusResponse`
转写任务状态响应。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `success` | `List<String>` | 转写成功的文件 ID |
| `fail` | `List<String>` | 转写失败的文件 ID |
| `summarySuccess` | `List<String>` | 总结成功的文件 ID |
| `summaryFail` | `List<String>` | 总结失败的文件 ID |
| `translateSuccess` | `List<String>` | 翻译成功的文件 ID |
| `translateFail` | `List<String>` | 翻译失败的文件 ID |

### `LauncherStateBean`
快捷入口状态（`getQuickEntryList` 返回元素）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | `Int` | 组件类型（101/102/103/104） |
| `id` | `RecordLauncherId` | 组件 ID |
| `state` | `ComponentAddState` | 添加状态：`0`不显示添加/`1`已添加/`2`未添加 |

### `PushRouteInfo`
推送路由信息。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `pushType` | `String` | 推送事件类型（如 `RecordTranscribe`） |
| `recordId` | `String` | 录音 ID |
| `tab` | `String` | 详情页 Tab：`transcribe`/`summarize` |
| `extraInfo` | `String` | 额外信息 JSON 字符串（预留） |

### `RecordUpdateInfo`
录音条目变更信息（文件数据变更事件）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `recordId` | `String` | 录音 ID |
| `name` | `String` | 文件名 |
| `cloudSyncStatus` | `Int` | 云同步状态 |
| `transferStatus` | `Int` | 转写状态 |
| `summaryStatus` | `Int` | 总结状态 |
| `translateStatus` | `Int` | 翻译状态 |
| `noteFileConvertState` | `Int` | note 转化状态 |
| `asrStatus` | `Int` | ASR 状态 |
| `summaryImageStatus` | `Int` | 总结配图状态 |
| `summaryImageUrl` | `String?` | 总结配图 URL |
| `tags` | `List<String>?` | 标签列表 |

### `BTConnectedStatus`
耳机蓝牙连接状态。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `devId` | `String` | 设备 ID |
| `connectedStatus` | `Int` | `1`已连接/`0`未连接 |

### `PhoneBatteryInfo`
设备电量。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `needShow` | `Boolean` | 是否需要展示 |
| `batteryValue` | `Double` | 电量值 |

### `RecordQualityInfo`
录音音质。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `needShow` | `Boolean` | 是否需要展示 |
| `snrValue` | `Double` | 信噪比 |

### `TranslationResult`
文本翻译结果。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `from` | `String` | 实际源语言 |
| `to` | `String` | 实际目标语言 |
| `translateResult` | `ArrayList<TranslationDetail>` | 翻译明细列表 |

**`TranslationDetail`**：`src: String?`（原文）、`dst: String?`（译文）

### `TranslationFile`
翻译记录文件。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `fileId` | `Long?` | 编号（自增） |
| `noteFileId` | `Long?` | 关联 note 文件 ID |
| `uid` | `String?` | 用户 ID |
| `directoryId` | `Long?` | 目录 ID |
| `deviceUniqueId` | `String?` | 设备录音唯一标识 |
| `name` | `String?` | 文件名 |
| `recordTime` | `Long?` | 录音时间 |
| `duration` | `Long?` | 录音时长 |
| `recordType` | `Int?` | `0`电话/`1`会议/`2`Pro 1v1/`3`入门 1v1/`4`文本翻译 |
| `audioFormat` | `Int?` | 音频格式 |
| `deviceId` | `String?` | 设备 ID |
| `source` | `Int?` | 来源：`0`未知/`1`蓝牙/`2`MIC/`3`Pro/`4`卡片 |
| `filePath` | `String?` | 录音文件路径 |
| `wavFilePath` | `String?` | wav 文件路径 |
| `amplitudes` | `String?` | 振幅字符串 |
| `visit` | `Int?` | 访问状态：`0`未读/`1`已读/`2`转录未读/`3`转录已读 |
| `originalLanguage` | `String?` | 原始语言 |
| `targetLanguage` | `String?` | 目标语言 |
| `originalText` | `String?` | 原文 |
| `targetText` | `String?` | 译文 |
| `recordId` | `String?` | 录音 ID |
| `agentId` | `String?` | 智能体 ID |
| `gid` | `String?` | 家庭 ID |

### `TranslationFilesParams`
翻译列表查询入参。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `type` | `Int?` | 翻译类型：`0`全部/`1`实时转录/`2`面对面Pro/`3`面对面入门/`4`文本 |
| `orderBy` | `Int?` | `0`fileId/`1`recordTime/`2`updateAt |
| `asc` | `Int?` | `0`降序/`1`升序 |

### `RemoveParam`
删除入参（内部构造用，`removeFileList` 自动生成）。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `fileId` | `Long` | 文件 ID |
| `isDeleteAll` | `Boolean` | `false`仅删音频/`true`音频+记录 |
| `deleteType` | `DeleteTypeDef?` | 删除类型（优先使用） |

---