# 指定音频直传 AI 笔记 · 接入示例

面向已经拿到音频地址的 Native SDK 接入方。本文覆盖一条完整链路：

```text
业务 Uri → importAudioFilesForNative → 导入状态 → 录音列表 → 详情页 → 转写 / 总结 / 翻译
```

接口规格见 [模块 5 · 音频导入](../modules/module-05-audio-import.md)。

---

## 一、适用边界

| 项 | 要求 |
|---|---|
| SDK 版本 | `>= 7.11.0` |
| URI 协议 | `content://` / `file://` |
| 音频格式 | MP3 / M4A / WAV |
| 单文件 | 不超过 500 MB，时长不超过 5 小时 |
| 单批 | 最多 20 个文件 |

`importAudioFilesForNative` 不拉起系统文件选择器。调用前应确认每个 `Uri` 都可读：
`content://` 需要宿主持有读权限；`file://` 通常来自应用私有目录，不要把其他应用私有路径
传给 SDK。`content://` 权限不是持久授权时，不要长期缓存后延后导入。

---

## 二、直传

```java
private void importFiles(List<Uri> uris) {
    if (uris == null || uris.isEmpty()) {
        return;
    }
    ThingAudioDetectManagerNative.getInstance().importAudioFilesForNative(uris);
}
```

方法没有启动回调。不要把“调用了”当成“导入成功了”：
进度、完成和失败都等 `IFileImportStatusListener`。

导入前可以按业务需要做两层检查：

1. **调用前检查**：文件数量、扩展名 / MIME、大小、时长和 `Uri` 可读性；
2. **事件兜底**：SDK 还会校验一次，失败文件进入 `failedFiles`。

assets 里的文件不能直接用 `file://` 访问，需要先拷到应用私有目录：

```kotlin
val targetDir = File(cacheDir, "audio_impt")
targetDir.deleteRecursively()
targetDir.mkdirs()

val uris = assets.list("audio_impt")
    ?.filter { it.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "wav") }
    ?.sorted()
    ?.map { fileName ->
        val targetFile = File(targetDir, fileName)
        assets.open("audio_impt/$fileName").use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
        Uri.fromFile(targetFile)
    }
    .orEmpty()

if (uris.isNotEmpty()) {
    ThingAudioDetectManagerNative.getInstance().importAudioFilesForNative(uris)
}
```

---

## 三、导入状态

```java
manager.addFileImportStatusListener(new IFileImportStatusListener() {
    @Override
    public void onStatusUpdate(FileImportStatusEventApp event) {
        int status = event.status == null ? 0 : event.status;
        int total = event.totalFileCount == null ? 0 : event.totalFileCount;
        int success = event.successCount == null ? 0 : event.successCount;
        runOnUiThread(() -> {
            progressView.setText(success + "/" + total);
            if (status == 2) {
                if (event.failedFiles == null || event.failedFiles.isEmpty()) {
                    showImportFinished();
                } else {
                    showImportFailures(event.failedFiles);
                }
            }
        });
    }
});
```

`onStatusUpdate` 可能在子线程回调。上面示例中的 `runOnUiThread(...)` 不能省略；
更新状态、弹窗或跳转前都要切回主线程。

状态含义：

| 状态 | 含义 |
|---|---|
| `0` | 未开始 |
| `1` | 导入中 |
| `2` | 完成；是否全部成功还要看 `failedFiles` |
| `3` | 中断，看任务级 `errorCode` |

进页时先调 `getAudioImportStatus()` 取一次快照，再注册监听增量更新；
退出时调用无参的 `removeFileImportStatusListener()`。

导入过程中可调 `cancelImport`。已成功的文件保留，未处理的文件中止。

失败后优先用 `retryImport` 从导入记录恢复；`Uri` 已失效或业务想换文件时，
再重新调用 `importAudioFilesForNative`。放弃失败列表用 `cancelRetry`。

---

## 四、拿到导入后的记录

导入成功的每条音频都会成为普通 AI Note 记录。导入完成的判断只依赖
`IFileImportStatusListener` 回调：`status == 2` 且 `failedFiles` 为空时，
`successCount` 就是本次成功数。

`importedFiles` 携带每条成功记录的 `recordId` / `fileId`，按 `successCount` 分流：
1 条进详情，多条进录音列表：

```java
if (status == 2 && (event.failedFiles == null || event.failedFiles.isEmpty())) {
    int successCount = event.successCount == null ? 0 : event.successCount;
    List<FileImportedFileInfo> importedFiles =
            event.importedFiles == null ? Collections.emptyList() : event.importedFiles;
    runOnUiThread(() -> showCompletionNavigation(successCount, importedFiles));
}
```

列表页可在回到前台或下拉刷新时全量重拉。

---

## 五、详情页后续加工

后续任务统一走 [模块 2](../modules/module-02-transcribe-summary.md)：

| 目标 | 任务类型 | 关键参数 |
|---|---|---|
| 转写 | `transferType = 0` | `fileId = recordTransferId`，可传 `audioLang` / `enableSpeaker` |
| 总结 | `transferType = 1` | 只在已有转写结果时发起 |
| 翻译 | `transferType = 2` | `transLang` 必填 |

```java
TranscribeParam param = new TranscribeParam(
        fileId, "", 0, "zh", null, null, false);
manager.processRecordTransferResult(param, callback);
```

`onSuccess` 只表示任务已提交。上传进度看 `ITransferListener`，
完成状态看 `IRecordFileUpdateCallback`；状态完成后重拉详情和正文，不要轮询。

---

## 六、Demo 展示

`NativeAudioImportActivity` 使用「文件选择器」和「业务直传」两个 Tab。
「业务直传」Tab 演示同一套能力：

1. 拷贝 `assets/audio_impt` 下的 MP3 / M4A / WAV 到 `cacheDir/audio_impt`；
2. 调 `importAudioFilesForNative`；
3. 复用导入状态、失败列表和取消 / 重试控件；
4. 全部成功后按 `successCount` 分流：1 条用 `importedFiles.recordId` 进入详情页，
   多条进入录音列表。

把 `audio_impt` 目录中的示例音频替换成业务测试文件即可复现完整流程。
