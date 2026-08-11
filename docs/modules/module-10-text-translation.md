# 模块 10 · 文本翻译

> **本 Demo 不实现该模块。**
> 文本翻译属于 AI Translate 业务（`businessType = 1`），与 AI 笔记（`businessType = 0`）
> 是两条独立的产品线，AI 笔记业务未使用这些接口。
> 本文只提供接口与数据结构的参考，没有经过验证的调用时序与接入清单。

| 项 | 内容 |
|---|---|
| Demo 页面 | 无 |
| 全局约定 | [接入指南](./README.md) |

---

## 一、能力概述

一组围绕「文本翻译记录」的接口：发起一次文本翻译、查询翻译历史列表与详情、
把某条翻译记录转存到 AI Note、批量删除。

翻译记录与 AI 笔记的录音记录是**两套独立的数据**，各有自己的列表与详情接口，
`TranslationFile.fileId` 与模块 3 的 `recordTransferId` 不通用。

---

## 二、接口清单

| 方法 | 作用 | 回调 |
|---|---|---|
| `startTranslate` | 发起一次文本翻译 | `IRecordCallBack<TranslationResult>` |
| `getTranslateFileList` | 查询翻译 / 录音历史列表 | `IRecordCallBack<List<TranslationFile>>` |
| `getTranslateFileDetail` | 查询单条翻译记录详情 | `IRecordCallBack<TranslationFile>` |
| `sendToNote` | 把翻译记录转存到 AI Note | `IRecordCallBack<String>` |
| `batchRemoveTranslationFiles` | 批量删除翻译记录 | `IRecordCallBack<Boolean>` |

事件监听：`ITranslationListener`（文本翻译 TTS 合成完成）。
⚠️ 该监听**只有 `addTranslationListener`，底层无对应的 remove 方法**。

---

## 三、接口详解

### `startTranslate(String originText, String originLanguage, String targetLanguage, IRecordCallBack<TranslationResult> callback)`

发起文本翻译。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `originText` | `String` | 是 | 待翻译的原文 |
| `originLanguage` | `String` | 是 | 源语言，如 `"zh"` |
| `targetLanguage` | `String` | 是 | 目标语言，如 `"en"` |
| `callback` | `IRecordCallBack<TranslationResult>` | 否 | 返回 [`TranslationResult`](#translationresult) |

---

### `getTranslateFileList(TranslationFilesParams param, IRecordCallBack<List<TranslationFile>> callback)`

查询翻译历史列表。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `param` | [`TranslationFilesParams`](#translationfilesparams) | 是 | 筛选与排序条件 |
| `callback` | `IRecordCallBack<List<TranslationFile>>` | 否 | 返回 [`TranslationFile`](#translationfile) 列表 |

---

### `getTranslateFileDetail(long fileId, int amplitudeMaxCount, IRecordCallBack<TranslationFile> callback)`

查询单条翻译记录详情。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fileId` | `long` | 是 | 记录 ID，取自 `TranslationFile.fileId` |
| `amplitudeMaxCount` | `int` | 是 | 振幅采样点上限，不画波形时传 `0` |
| `callback` | `IRecordCallBack<TranslationFile>` | 否 | 返回单条记录 |

---

### `sendToNote(long fileId, IRecordCallBack<String> callback)`

把这条翻译记录转存到 AI Note，之后它就是一条普通录音记录。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fileId` | `long` | 是 | 翻译记录 ID |
| `callback` | `IRecordCallBack<String>` | 否 | `onSuccess` 返回生成的 Note 侧 `recordId` |

转存后 `TranslationFile.noteFileId` 会指向对应的 Note 文件。

---

### `batchRemoveTranslationFiles(List<Long> fileIds, IRecordCallBack<Boolean> callback)`

批量删除翻译记录。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fileIds` | `List<Long>` | 是 | 待删除的记录 ID 列表 |
| `callback` | `IRecordCallBack<Boolean>` | 否 | `onSuccess(true)` 表示删除成功 |

---

### 事件监听 · `ITranslationListener`

```java
manager.addTranslationListener(listener);   // ⚠️ 无对应 remove
```

| 回调 | 说明 |
|---|---|
| `onTextTranslateTtsCompleteEvent(long fileId, String ttsPath)` | 译文的 TTS 音频合成完成，`ttsPath` 为本地音频路径 |

> 底层未提供移除方法，注册后会一直存活。实现里**不要持有 Activity 强引用**，
> 否则会造成内存泄漏。

---

## 四、数据结构

### `TranslationResult`

`startTranslate` 的返回值。

| 字段 | 类型 | 说明 |
|---|---|---|
| `from` | `String` | 实际识别 / 使用的源语言 |
| `to` | `String` | 实际目标语言 |
| `translateResult` | `ArrayList<TranslationDetail>` | 翻译明细，按句拆分 |

**`TranslationDetail`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `src` | `String?` | 该句原文 |
| `dst` | `String?` | 该句译文 |

### `TranslationFilesParams`

`getTranslateFileList` 的入参，字段传 `null` 表示不限。

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | `Int?` | 翻译类型：`0` 全部 / `1` 实时转录 / `2` 面对面 Pro / `3` 面对面入门 / `4` 文本 |
| `orderBy` | `Int?` | 排序字段：`0` fileId / `1` recordTime / `2` updateAt |
| `asc` | `Int?` | `0` 降序 / `1` 升序 |

### `TranslationFile`

翻译记录，`getTranslateFileList` / `getTranslateFileDetail` 的返回元素。

| 字段 | 类型 | 说明 |
|---|---|---|
| `fileId` | `Long?` | 记录 ID（自增），后续接口的入参 |
| `noteFileId` | `Long?` | 关联的 AI Note 文件 ID，`sendToNote` 之后才有值 |
| `uid` | `String?` | 用户 ID |
| `directoryId` | `Long?` | 目录 ID |
| `deviceUniqueId` | `String?` | 设备录音唯一标识 |
| `name` | `String?` | 文件名 |
| `recordTime` | `Long?` | 录音时间（秒） |
| `duration` | `Long?` | 录音时长（毫秒） |
| `recordType` | `Int?` | `0` 电话 / `1` 会议 / `2` Pro 1v1 / `3` 入门 1v1 / `4` 文本翻译 |
| `audioFormat` | `Int?` | 音频格式编码，播放走 `filePath` 即可，通常无需关心 |
| `deviceId` | `String?` | 设备 ID |
| `source` | `Int?` | 来源：`0` 未知 / `1` 蓝牙 / `2` MIC / `3` Pro / `4` 卡片 |
| `filePath` | `String?` | 录音文件本地路径 |
| `wavFilePath` | `String?` | wav 文件本地路径 |
| `amplitudes` | `String?` | 振幅字符串，用于画波形 |
| `visit` | `Int?` | 访问状态：`0` 未读 / `1` 已读 / `2` 转录未读 / `3` 转录已读 |
| `originalLanguage` | `String?` | 源语言 |
| `targetLanguage` | `String?` | 目标语言 |
| `originalText` | `String?` | 原文 |
| `targetText` | `String?` | 译文 |
| `recordId` | `String?` | 业务录音 ID |
| `agentId` | `String?` | 智能体 ID |
| `gid` | `String?` | 家庭 ID |

---

## 五、错误码

**本模块没有专属错误码表。** 各接口失败时走通用回调 `onError(String code, String error)`，
`code` 为底层透传，无固定枚举。
