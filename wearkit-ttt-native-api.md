# ThingAudioDetectManagerNative API Reference

> Complete field-level reference for the Native (Android) TTT capability API.
> Developers only need to program against the `ThingAudioDetectManagerNative` singleton.
> For practical workflows, see [wearkit-ttt-native-sop.md](./wearkit-ttt-native-sop.md).

| Item | Info |
| --- | --- |
| Entry class | `ThingAudioDetectManagerNative` |
| Get instance | `ThingAudioDetectManagerNative.getInstance()` |
| Package | `com.thingclips.smart.plugin.tuniaudiodetectmanager` |

---

## Table of Contents

- [Callback Types](#callback-types)
- [Module 1 · Recording Control](#module-1--recording-control)
- [Module 2 · Transcription / Summary](#module-2--transcription--summary)
- [Module 3 · File Management](#module-3--file-management)
- [Module 4 · Offline File Transfer](#module-4--offline-file-transfer)
- [Module 5 · Audio Import](#module-5--audio-import)
- [Module 6 · Cloud Sync](#module-6--cloud-sync)
- [Module 7 · Device / Channel / Flow Control](#module-7--device--channel--flow-control)
- [Module 8 · Merge Recordings](#module-8--merge-recordings)
- [Module 9 · Share / Floating / Quick Entry](#module-9--share--floating--quick-entry)
- [Module 10 · Text Translation](#module-10--text-translation)
- [Module 11 · Event Listening](#module-11--event-listening)
- [Data Dictionary (Bean full fields)](#data-dictionary-bean-full-fields)

---

## Callback Types

All asynchronous methods return results via the following standard callbacks:

| Callback Type | Methods | Use Case |
| --- | --- | --- |
| `IResultCallback` | `onSuccess()` / `onError(String code, String error)` | Operations without return data |
| `IRecordCallBack<T>` | `onSuccess(T result)` / `onError(String code, String error)` | Queries with return data |
| `IAudioImportCallBack` | `onSuccess()` / `onError(Integer code, String error)` | Audio import |
| `IOfflineFilesProgress` | `onProgress(DeviceOfflineFileStatus)` / `onSuccess(Long sessionId)` / `onError(String, String)` | Offline file transfer progress |
| `IOperateRecordShareLinkResult` | `onSuccess(String link)` / `onError(String, String)` | Share link operations |
| `ICloudSyncSwitchCallBack<P, T>` | `onSuccess(P param, T time)` / `onError(String, String)` | Cloud sync switch queries |

> Callbacks may arrive on a background thread; switch back to the main thread to update the UI.

---

## Module 1 · Recording Control

### `recordTransferTask(String deviceId) → RecordStatusBean`
Query whether an in-progress recording/transcription task exists for the device. **Returns synchronously**; returns `null` when there is no task.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `deviceId` | `String` | Yes | Device ID; pass `"PHONE"` for phone recording |

### `startAudioRecording(String deviceId, RecordParamsV2 params, IResultCallback callback)`
Start recording with transcription.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `deviceId` | `String` | Yes | Device ID |
| `params` | [`RecordParamsV2`](#recordparamsv2) | Yes | Recording parameters |
| `callback` | `IResultCallback` | No | Result callback |

### `updateParams(String deviceId, RecordParamsV2 params, IResultCallback callback)`
Update parameters during recording. **Returns `onSuccess` immediately** without waiting for the underlying result; the actual effect is reflected by the `onRecordStatusUpdate` event.

### `pauseRecordTransfer / resumeRecordTransfer / stopRecordTransfer(String deviceId, IResultCallback callback)`
Pause / resume / stop the recording and transcription.

### `switchRecordChannel(String deviceId, int recordChannel, IResultCallback callback)`
Switch the audio capture channel.

| Param | Value | Description |
| --- | --- | --- |
| `recordChannel` | `0` | Unspecified |
| | `1` | BT (earphone) |
| | `2` | Micro (phone mic) |

---

## Module 2 · Transcription / Summary

### `processRecordTransferResult(TranscribeParam param, IResultCallback callback)`
Trigger transcription / summary processing for a recording. **`onSuccess` only indicates the task was submitted, not completed.**

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `param` | [`TranscribeParam`](#transcribeparam) | Yes | Transcription parameters |

### `getRecordTransferRecognizeResult(long recordTransferId, int from, IRecordCallBack<String> callback)`
Query the transcription (recognition) result text.

| Param | Type | Description |
| --- | --- | --- |
| `recordTransferId` | `long` | File ID |
| `from` | `int` | `0` local / `1` cloud |

### `saveRecordTransferRecognizeResult(long recordTransferId, String text, IResultCallback callback)`
Save the transcription result.

### `getRecordTransferSummaryResult(long recordTransferId, int from, IRecordCallBack<String> callback)`
Query the summary result text. Same params as `getRecordTransferRecognizeResult`.

### `saveRecordTransferSummaryResult(long recordTransferId, String text, IResultCallback callback)`
Save the summary result.

### `getRecordTransferRealTimeResult(String fileId, String recordId, String asrId, IRecordCallBack<List<RecordTransferRealTimeResult>> callback)`
Query the list of real-time / historical transcription sentences. All three params can be `null`.

### `saveRecordTransferRealTimeRecognizeResult(long asrId, String text, String asr, String translate, IResultCallback callback)`
Update a single real-time transcription sentence by `asrId`.

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `asrId` | `long` | Yes | Sentence ID |
| `text` | `String` | No | Full display text |
| `asr` | `String` | No | Raw ASR result |
| `translate` | `String` | No | Translation result |

---

## Module 3 · File Management

### `getRecordTransferResultList(FilesParam param, IRecordCallBack<List<RecordTransferResultBean>> callback)`
Get the recording/transcription list.

### `getRecordTransferResultDetail(String recordId, int amplitudeMaxCount, IRecordCallBack<RecordTransferResultBean> callback)`
Get a single item detail by business `recordId`.

| Param | Type | Description |
| --- | --- | --- |
| `recordId` | `String` | Business recording ID |
| `amplitudeMaxCount` | `int` | Max amplitude sample count; `0` means full |

### `searchRecordTransferResult(AudioSearchMixParams param, IRecordCallBack<ArrayList<AudioSearchMixItem>> callback)`
Search by keyword across titles / tags / summaries (supports highlighting).

### `updateRecordTransferResult(...)`
Update file metadata. Full parameters:

| Param | Type | Required | Description |
| --- | --- | --- | --- |
| `recordTransferId` | `long` | Yes | File ID |
| `name` | `String` | No | File name; `null` to skip |
| `status` | `Integer` | No | Sync status: `0`not uploaded/`1`uploading/`2`uploaded/`3`failed |
| `visit` | `String` | No | Visit status; accepts `"true"`/`"false"`/numeric string |
| `remove` | `Boolean` | No | Whether to move to trash |
| `transfer` | `Integer` | No | Transcription status: `0`not/`1`in progress/`2`done/`3`failed |
| `directoryId` | `Long` | No | Directory ID |
| `storageKey` | `String` | No | Cloud storage key |
| `callback` | `IResultCallback` | No | Result callback |

### `removeFileList(List<Long> fileIds, boolean isDeleteAll, IResultCallback callback)`
Batch delete / clear local audio / clear all.

| `isDeleteAll` | `fileIds` | Behavior |
| --- | --- | --- |
| `true` | — | Permanent delete (local + cloud) |
| `false` | non-empty | Delete local audio only |
| `false` | empty / `null` | Clear all cached local audio |

### `getAudioFilesSize(IRecordCallBack<Integer> callback)`
Get the disk space used by local audio (bytes).

### `updateRecordTagResult(UpdateRecordTagResultParams param, IResultCallback callback)`
Update tags.

### `operateAudioFileSafePath(boolean isGetPath, String audioPath, IRecordCallBack<String> callback)`
Get or delete the compliant share path for the mini-program.

| Param | Type | Description |
| --- | --- | --- |
| `isGetPath` | `boolean` | `true` get / `false` delete |
| `audioPath` | `String` | Audio file path under the earphone directory |

---

## Module 4 · Offline File Transfer

### `getDeviceOfflineFileStatus(String deviceId, IRecordCallBack<DeviceOfflineFileStatus> callback)`
Query the device's offline file list and download session status.

### `loadOfflineFile(String deviceId, int channel, long sessionId, IOfflineFilesProgress callback)`
Start / resume an offline file download task.

| Param | Value | Description |
| --- | --- | --- |
| `channel` | `0` | Unspecified |
| | `1` | BLE |
| | `2` | AP (hotspot) |
| `sessionId` | `long` | `0` to start a new task; non-zero to resume |

### `switchModeLoadOfflineFile(String deviceId, int channel, IOfflineFilesProgress callback)`
Switch AP / BLE mode to continue transfer.

---

## Module 5 · Audio Import

| Method | Callback | Description |
| --- | --- | --- |
| `startImport(IAudioImportCallBack callback)` | `IAudioImportCallBack` | Start importing local audio |
| `retryImport(IAudioImportCallBack callback)` | `IAudioImportCallBack` | Retry import |
| `cancelImport(IAudioImportCallBack callback)` | `IAudioImportCallBack` | Cancel import |
| `cancelRetry(IAudioImportCallBack callback)` | `IAudioImportCallBack` | Cancel retry |
| `getAudioImportStatus() → FileImportStatusEventApp` | Synchronous | Query the current import status snapshot; may be `null` |

---

## Module 6 · Cloud Sync

### `getCloudSyncSwitchStatus(ICloudSyncSwitchCallBack<CloudSyncSwitchParam, Long> callback)`
Get the cloud sync switch and sync type (async).

### `syncNoteRecord(IResultCallback callback)`
Sync-upload Notes (file + audio), and download cloud Notes.

### `syncDownloadNoteAudio(Long fileId, String recordId)`
On-demand download of a specific Note audio. **No return (synchronous)**.

| Param | Type | Description |
| --- | --- | --- |
| `fileId` | `Long` | File ID; pass `-1` when `null` |
| `recordId` | `String` | Business recording ID |

---

## Module 7 · Device / Channel / Flow Control

### `getEarPhoneBTConntectedStatus(String devId, IRecordCallBack<BTConnectedStatus> callback)`
Query the earphone Bluetooth connection status.

### `operateEventLimit(String eventName, boolean operate, IResultCallback callback)`
⚠️ **no-op**. The native version does no event throttling; calling returns `onSuccess` immediately, kept only for interface alignment.

### `readyToSetupNativeChannel(IResultCallback callback)`
⚠️ **no-op**. No corresponding action on Android (iOS only); returns `onSuccess` directly.

---

## Module 8 · Merge Recordings

| Method | Description |
| --- | --- |
| `mergeRecordList(List<String> recordIds, IResultCallback callback)` | Batch merge recordings |
| `cancelMergeRecordList(IResultCallback callback)` | Cancel a merge task |
| `getFileMergeStatus() → MergeStatusEvent` | Synchronously returns the current merge status snapshot |

---

## Module 9 · Share / Floating / Quick Entry

### `operateRecordShareLink(String recordId, List<String> shareType, long expireTime, int shareStatus, String password, IOperateRecordShareLinkResult callback)`
Save or close a recording share link.

| Param | Type | Description |
| --- | --- | --- |
| `recordId` | `String` | Recording ID |
| `shareType` | `List<String>` | Share type array |
| `expireTime` | `long` | Expiry timestamp |
| `shareStatus` | `int` | `1` sharing / `2` close |
| `password` | `String` | Access password; can be `null` |

### `operateRecordingFloat(boolean isVisible, IResultCallback callback)`
Show or hide the app recording floating bubble.

### `getQuickEntryList(IRecordCallBack<List<LauncherStateBean>> callback)`
Get the add status of widgets / quick icons / tiles.

### `setQuickEntryEnabled(int type, int componentId, int enabled, IResultCallback callback)`
Enable or disable an entry by type.

| Param | Description |
| --- | --- |
| `type` | Android: `101`/`102`/`103` (widget/quick icon/tile) |
| `componentId` | Component ID |
| `enabled` | `1` enable / `0` disable |

---

## Module 10 · Text Translation

### `startTranslate(String originText, String originLanguage, String targetLanguage, IRecordCallBack<TranslationResult> callback)`
Initiate text translation.

### `getTranslateFileList(TranslationFilesParams param, IRecordCallBack<List<TranslationFile>> callback)`
Get the translation / recording history list.

### `getTranslateFileDetail(long fileId, int amplitudeMaxCount, IRecordCallBack<TranslationFile> callback)`
Get the detail of a translation record.

### `sendToNote(long fileId, IRecordCallBack<String> callback)`
Send a translation record to AI Note.

### `batchRemoveTranslationFiles(List<Long> fileIds, IRecordCallBack<Boolean> callback)`
Batch delete translation records.

---

## Module 11 · Event Listening

Event listeners are used **in pairs**: register with `add`, unregister with `remove`, **passing the same listener instance**.

| add / remove pair | listener type | Trigger scenario |
| --- | --- | --- |
| `addRecordListener` / `removeRecordListener` | `IRecordListener` | Recording status/amplitude/real-time recognition/audio source switch/finish |
| `addPushRouteInfoListener` / `removePushRouteInfoListener` | `IPushRouteInfoListener` | Push route navigation |
| `addTransferListener` / `removeTransferListener` | `ITransferListener` | Transcription file upload progress |
| `addCloudSwitchListener` / `removeCloudSwitchListener` | `ICloudSwitchListener<CloudSyncSwitchParam, Long>` | Cloud sync switch changes |
| `addFileRecordUpdateListener` / `removeFileRecordUpdateListener` | `IRecordFileUpdateCallback<List<RecordUpdateInfo>>` | Recording item partial/full refresh |
| `addTranslationListener` (no remove ⚠️) | `ITranslationListener` | Text translation TTS completion |
| `addQuickEntryAddListener` / `removeQuickEntryAddListener()` | `IQuickEntryAddListener` | Quick entry add result |
| `registerFileProgressCallback` / `unRegisterFileProgressCallback` | `IOfflineFilesProgress` | Offline file transfer progress |
| `addFileImportStatusListener` / `removeFileImportStatusListener()` | `IFileImportStatusListener` | Audio import status |
| `addMergeStatusListener` / `removeMergeStatusListener()` | `IMergeStatusListener` | Merge task progress/result |
| `addNativeAbilityListener` / `removeNativeAbilityListener` | `INativeAbilityListener` | Device ability: BT/battery/quality |
| `addAudioSyncObserver` / `removeAudioSyncObserver` | `SyncObserver` | Cloud sync upload/download aggregated status |

> ⚠️ `addTranslationListener` has no matching remove at the underlying level; be aware when using it.
> ⚠️ `removeQuickEntryAddListener` / `removeFileImportStatusListener` / `removeMergeStatusListener` are parameterless removes (remove all).

### Listener callback quick reference

**`IRecordListener`** (core recording events)

| Method | Description |
| --- | --- |
| `onRecordStatusUpdate(String deviceId, RecordStatusBean bean)` | Recording status change |
| `onRecordAmplitudeUpdate(String deviceId, int channel, double amplitude)` | Amplitude update (waveform) |
| `onRealTimeStatusUpdate(RealTimeTransferStatus status)` | Real-time transcription push |
| `onRecordSwitchAudioSourceEvent(String devId, int recordType, int audioSource)` | Audio source switch |
| `onRecordFinish(String deviceId)` | Recording finished normally (code=0) |
| `onRecordErrorFinish(String deviceId, int errorCode, String errorMsg)` | Recording finished with error |

**`IRecordFileUpdateCallback<List<RecordUpdateInfo>>`** (file data changes)

| Method | Description |
| --- | --- |
| `onUpdate(List<RecordUpdateInfo> infos)` | Status change (e.g. transcription done) |
| `onRecordOperate(String operate, List<RecordUpdateInfo> infos)` | File add/delete/modify; `operate` see `RecordOperateDef` |
| `onRecordListSyncSuccess()` | Cloud sync complete → recommend full refresh |
| `onUpdateWitheTags(List<RecordUpdateInfo> infos)` | Tags-only change |

**Other listeners** (single method, param is the corresponding Bean)

| listener | callback method | param Bean |
| --- | --- | --- |
| `IPushRouteInfoListener` | `onPush(PushRouteInfo)` | [`PushRouteInfo`](#pushrouteinfo) |
| `ITransferListener` | `onRecordTransferFileUploadEvent(String fileId, int progress, int status)` | atomic params |
| `ICloudSwitchListener<P,T>` | `onRefreshSwitchState(P, T, CloudSyncRefreshType)` / `onError(String, String)` | [`CloudSyncSwitchParam`](#cloudsyncswitchparam) + `Long` |
| `ITranslationListener` | `onTextTranslateTtsCompleteEvent(long fileId, String ttsPath)` | atomic params |
| `IQuickEntryAddListener` | `onAddResult(int type, int id, boolean success)` | atomic params |
| `IFileImportStatusListener` | `onStatusUpdate(FileImportStatusEventApp)` | [`FileImportStatusEventApp`](#fileimportstatuseventapp) |
| `IMergeStatusListener` | `onStatusUpdate(MergeStatusEvent)` | [`MergeStatusEvent`](#mergestatusevent) |
| `INativeAbilityListener` | `onBTConnectChange(BTConnectedStatus)` / `onPhoneBatteryChange(PhoneBatteryInfo)` / `onRecordQualityChange(RecordQualityInfo)` | see each Bean |

---

## Data Dictionary (Bean full fields)

### `RecordStatusBean`
Recording status (returned by `recordTransferTask`; callback of `IRecordListener.onRecordStatusUpdate`).

| Field | Type | Description |
| --- | --- | --- |
| `status` | `Int` | Recording status: `0`idle/`1`recording/`2`paused/`3`stopped |
| `duration` | `Long` | Recording duration (milliseconds) |
| `type` | `Int?` | Recording type: `0`phone/`1`meeting |
| `transferType` | `Int?` | Transcription mode: `0`file transcription/`1`real-time |
| `needTranslate` | `Boolean?` | Whether translation is needed |
| `originalLanguage` | `String?` | Source language |
| `targetLanguage` | `String?` | Target language |
| `currentRealTimeAsrId` | `String?` | Current real-time transcription asrId |
| `recordId` | `String?` | Real-time transcription record ID |
| `devId` | `String?` | Device ID |
| `audioSource` | `Int?` | Audio source |
| `needTts` | `Boolean?` | Whether TTS is needed |
| `businessType` | `Int?` | Business type: `0`Note/`1`Translation |

### `RecordParamsV2`
Recording parameters (input of `startAudioRecording` / `updateParams`).

| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `updateParams` | `Boolean?` | `false` | Whether this is a param update (set to `true` inside `updateParams`) |
| `audioSource` | `Int?` | `null` | Audio source (see table below) |
| `audioSourceList` | `List<Int>?` | `null` | Multi-channel audio sources, e.g. `[0, 20]` |
| `recordMode` | `Int?` | `null` | Recording mode: `0`phone/`1`live(meeting)/`2`face-to-face |
| `f2fChannel` | `Int?` | `null` | Face-to-face channel: `0`default/`1`left/`2`right |
| `needAsr` | `Boolean` | `false` | Whether real-time transcription is needed |
| `needTranslate` | `Boolean` | `false` | Whether translation is needed (forced true for simultaneous interpretation) |
| `needTts` | `Boolean` | `false` | Whether TTS is needed |
| `needAmplitude` | `Boolean` | `false` | Whether amplitude callbacks are needed |
| `originalLanguage` | `String?` | `null` | Source language |
| `targetLanguage` | `String?` | `null` | Target language |
| `agentId` | `String?` | `null` | Agent/channel ID |
| `recordTransfer3AConfig` | `Audio3AConfig?` | `null` | 3A config |
| `ttsConfig` | `TTSConfig?` | `null` | TTS config (2.3.0+) |
| `ttsConfigList` | `List<TTSConfig>?` | `null` | TTS multi-output config |
| `businessType` | `Int?` | `null` | `0`AI Note/`1`Translation |
| `autoRecognize` | `Boolean?` | `null` | Whether to auto-detect language |
| `startLivingStatus` | `Int` | `0` | Live status: `0`interpretation only/`1`start live/`2`change during live |

**`audioSource` values**

| Value | Meaning |
| --- | --- |
| `0` | System Bluetooth 16K mono |
| `1` | System MIC 16K mono |
| `20` | Pro earphone 16K mono |
| `21` | Pro earphone 16K stereo |
| `22` | Pro earphone 32K mono (card) |
| `40` | Card 16K mono |
| `41` | Card 16K stereo |

### `Audio3AConfig`
3A audio processing config.

| Field | Type | Default | Description |
| --- | --- | --- | --- |
| `ansEnable` | `Boolean` | — | Noise suppression toggle |
| `ansLevel` | `Int` | `1` | Noise suppression level |
| `agcEnable` | `Boolean` | — | Automatic gain control toggle |
| `aecEnable` | `Boolean` | — | Acoustic echo cancellation toggle |

### `TTSConfig`
TTS output config.

| Field | Type | Description |
| --- | --- | --- |
| `devId` | `String?` | Device ID when outputting to a device; `null` for BT/MIC |
| `output` | `TTSOutput` | Output source: `0`disabled/`1`MIC/`2`BT/`3`device |
| `encode` | `TTSEncode` | Encoding: `0`default/`1`opus_silk/`2`opus_celt |
| `channel` | `TTSOutputChannel` | Output channel: `0`default both ears/`1`left/`2`right |

### `TranscribeParam`
Transcription / summary input (for `processRecordTransferResult`).

| Field | Type | Description |
| --- | --- | --- |
| `fileId` | `Long` | File ID |
| `template` | `String?` | Summary template |
| `transferType` | `Int?` | `0`file transcription/`1`real-time |
| `audioLang` | `String?` | ASR target language |
| `transLang` | `String?` | Translation target language |
| `summaryLang` | `String?` | Summary output language (defaults to same as transcription) |
| `enableSpeaker` | `Boolean` | Whether to enable speaker diarization |
| `ownerId` | `String?` | Home ID (gid) |
| `devId` | `String?` | Device ID |
| `key` | `String?` | recordId |
| `objectKey` | `String?` | Object storage key |
| `duration` | `Int?` | Audio duration (seconds) |
| `channelMode` | `String?` | Channel mode |

### `FilesParam`
List query input (for `getRecordTransferResultList`).

| Field | Type | Description |
| --- | --- | --- |
| `directoryId` | `Long?` | Directory ID; `null` queries all directories |
| `recordType` | `Int?` | Audio type: `0`phone/`1`meeting; `null` queries all |
| `deviceId` | `String?` | Device ID; `null` queries all devices |
| `transfer` | `Int?` | Transcription status: `0`not/`1`in progress/`2`success/`3`failed; `null` queries all |
| `source` | `Int?` | Source: `0`app/`1`device; `null` queries all |
| `remove` | `Boolean?` | Whether in trash; `null` queries all |
| `orderBy` | `Int?` | Sort field: `0`fileId/`1`recordTime/`2`updateAt |
| `asc` | `Int?` | `0`descending/`1`ascending |
| `lastFileId` | `Int?` | Pagination cursor (last item ID of previous page); `null`/`0` for first page |
| `pageSize` | `Int?` | Page size; `null`/`0` means no pagination |

### `RecordTransferResultBean`
List / detail return (30+ fields, all public).

| Field | Type | Description |
| --- | --- | --- |
| `recordTransferId` | `Long` | File ID (business primary key; used as pagination cursor) |
| `directoryId` | `Long` | Directory ID |
| `deviceUniqueId` | `String` | Unique identifier of the recording file generated by the device |
| `name` | `String` | File name |
| `recordTime` | `Long` | Recording time (seconds, timestamp) |
| `duration` | `Long` | Recording duration (milliseconds) |
| `recordType` | `Int` | Audio type: `0`phone/`1`live+real-time/`2`,`3`face-to-face/`4`text translation/`5`audio import |
| `audioFormat` | `Int` | Audio format |
| `deviceId` | `String` | Device ID |
| `filePath` | `String?` | Recording file path |
| `wavFilePath` | `String?` | ⚠️Deprecated, use `filePath` |
| `amplitudes` | `String?` | Amplitude string (comma-separated) |
| `status` | `Int` | Sync status: `0`not uploaded/`1`uploading/`2`uploaded/`3`failed |
| `visit` | `Int` | Visit: `0`unread/`1`read/`2`transcribed unread/`3`transcribed read |
| `remove` | `Boolean` | Whether in trash |
| `storageKey` | `String?` | Cloud storage key |
| `transfer` | `Int` | Transcription: `0`not/`1`in progress/`2`done/`3`failed |
| `summary` | `Int` | Summary: `1`not/`2`in progress/`3`success/`4`failed (`0` for legacy data) |
| `source` | `Int` | Audio source (consistent with audioSource) |
| `transferType` | `Int` | Transcription mode: `0`file/`1`real-time |
| `needTranslate` | `Boolean` | Whether translation is needed |
| `originalLanguage` | `String?` | Source language |
| `targetLanguage` | `String?` | Target language |
| `recordId` | `String?` | Real-time transcription record ID |
| `agentId` | `String?` | Agent ID |
| `cloudTranscription` | `Boolean` | Whether cloud transcription |
| `translateState` | `Int` | Translation status |
| `transcriptionStatus` | `Int` | ASR coverage: `0`unknown/`1`none/`2`partial/`3`full |
| `cloudSyncStatus` | `Int?` | UI sync status (`0`/`5`/`10`/`15`/`20`/`-10`/`-20`) |
| `isFromCloud` | `Boolean` | Whether a cloud sync record |
| `linkShared` | `Int` | Share: `0`not/`1`yes |
| `summaryImageStatus` | `Int` | Summary image: `1`not/`2`in progress/`3`success/`4`failed |
| `noteFileConvertState` | `Int` | Note convert: `0`success/`1`in progress/`2`failed |
| `offlineUploadProgress` | `Int` | Offline upload progress (0-100) |
| `offlineUploadStatus` | `Int` | Offline upload status: `-1`not/`0`waiting/`1`in progress/`2`done/`3`failed/`4`cancelled |
| `tags` | `List<String>?` | Tag list |

### `RecordTransferRealTimeResult`
Real-time / historical transcription sentence (list element returned by `getRecordTransferRealTimeResult`).

| Field | Type | Description |
| --- | --- | --- |
| `asrId` | `Long` | Sentence ID |
| `recordTransferId` | `Long` | File ID |
| `beginOffset` | `Long` | Start offset (milliseconds) |
| `endOffset` | `Long` | End offset (milliseconds) |
| `text` | `String` | Display text |
| `asr` | `String?` | Raw ASR text |
| `translate` | `String?` | Translation text |
| `requestId` | `String` | Request ID |
| `recordId` | `String` | Real-time transcription record ID |
| `channel` | `Int` | Channel (meeting 0; phone 0 near-end/1 far-end; simultaneous 0 left/1 right) |
| `status` | `Int` | Transcription status: `0`not/in progress, `1`success, `2`failed |
| `businessType` | `Int` | `0`Note/`1`Translation |
| `ttsPath` | `String` | TTS audio path |

### `RealTimeTransferStatus`
Real-time transcription event (callback of `IRecordListener.onRealTimeStatusUpdate`).

| Field | Type | Description |
| --- | --- | --- |
| `deviceId` | `String` | Device ID |
| `recordId` | `String` | Real-time transcription record ID |
| `requestId` | `String` | Request ID |
| `asrId` | `Long` | Sentence ID |
| `channel` | `Int` | Channel |
| `phase` | `Int` | Phase: `0`task/`1`asr/`2`text/`3`skill/`4`tts |
| `status` | `Int` | Phase status: `0`not started/`1`in progress/`2`finished/`3`cancelled |
| `text` | `String` | Text |
| `beginOffset` | `Long` | Start time (milliseconds) |
| `endOffset` | `Long` | End time (milliseconds) |
| `translateText` | `String` | Translation text |
| `translateStatus` | `Int` | Translation status |
| `errorCode` | `Int` | Error code |
| `errorMessage` | `String?` | Error message |

### `AudioSearchMixParams`
Search input (for `searchRecordTransferResult`).

| Field | Type | Description |
| --- | --- | --- |
| `keyword` | `String?` | Keyword/tag (reserved) |
| `content` | `String?` | Search content/tag |
| `pageNum` | `Int?` | Page number, default 1 |
| `pageSize` | `Int?` | Page size, default 20 |

### `AudioSearchMixItem`
Single search result.

| Field | Type | Description |
| --- | --- | --- |
| `recordId` | `String?` | Note ID |
| `title` | `String?` | Title (may contain `<em>` highlight) |
| `tags` | `List<String>?` | Tag list (hits are highlighted and pinned to top) |
| `summary` | `String?` | Summary snippet (may contain highlight) |
| `content` | `String?` | Transcription content snippet (may contain highlight) |
| `score` | `Int?` | Overall score |
| `bizTime` | `Long?` | Business timestamp (milliseconds) |
| `audioSource` | `Int` | Audio source (default `-1`) |
| `isFromCloud` | `Boolean` | Whether a cloud sync record |
| `recordTime` | `Long` | Recording time (seconds) |
| `duration` | `Long` | Recording duration (milliseconds) |

### `UpdateRecordTagResultParams`
Tag update input.

| Field | Type | Description |
| --- | --- | --- |
| `recordId` | `String` | Recording ID |
| `bizType` | `Int` | `0`add/`1`delete/`2`reorder |
| `tags` | `List<String>?` | Tag list |

### `DeviceOfflineFileStatus`
Offline file status.

| Field | Type | Description |
| --- | --- | --- |
| `status` | `Int` | Download status: `0`not started/`1`downloading/`2`finished |
| `sessionId` | `Long` | Task ID (`0` not started) |
| `response` | `OfflineFilesResponse` | Resource details |
| `errorCode` | `Int` | Error code during the process |

### `OfflineFilesResponse`
Offline file download details.

| Field | Type | Description |
| --- | --- | --- |
| `channel` | `Int` | Download channel: `0`unspecified/`1`ble/`2`ap |
| `apConnectState` | `Int` | AP status: `0`not enabled/`1`enabled/`2`connected |
| `speed` | `Double` | Download speed (KB/s) |
| `total` | `Int` | Total downloadable files |
| `size` | `Int` | Total downloaded files |
| `curFile` | `FileDigest` | Current downloading file |
| `files_waiting` | `List<FileDigest>` | Pending files |
| `files_failed` | `List<FileDigest>` | Failed files |
| `files_transform` | `List<FileDigest>` | Transferred files under conversion |
| `files_successed` | `List<FileDigest>` | Transferred and converted files |
| `remainingDownloadTime` | `Int` | Remaining time (seconds) |
| `downloadedFileProgress` | `Int` | Download progress (0-100) |

### `FileDigest`
File digest.

| Field | Type | Description |
| --- | --- | --- |
| `fileId` | `Long` | File ID |
| `fileType` | `Int` | File type |
| `progress` | `Double` | Progress (0-100%) |
| `timeStamp` | `Long` | Timestamp |
| `fileName` | `String` | File name |
| `fileDuring` | `Long` | Content duration |

### `FileImportStatusEventApp`
Import status.

| Field | Type | Description |
| --- | --- | --- |
| `status` | `Int` | `0`not started/`1`importing/`2`done/`3`interrupted/`4`share import failed |
| `errorCode` | `Int?` | Error code (when status=3) |
| `errorMessage` | `String?` | Error message |
| `totalFileCount` | `Int?` | Total file count |
| `successCount` | `Int?` | Successfully imported count |
| `failedFiles` | `List<FailedFileInfo>?` | Failed file list |
| `timestamp` | `Long` | Timestamp |

### `CloudSyncSwitchParam`
Cloud sync switch status.

| Field | Type | Description |
| --- | --- | --- |
| `enabled` | `Boolean` | Switch state |
| `syncType` | `Int?` | Sync type: `0`all networks/`1`Wi-Fi only |
| `modifyTime` | `Long` | Update time (default `-1`) |

### `MergeStatusEvent`
Merge task status.

| Field | Type | Description |
| --- | --- | --- |
| `status` | `Int` | `0`not started/`1`running/`2`finished/`3`error |
| `subStatus` | `Int?` | Running sub-status: `10`downloading/`20`merging |
| `errorCode` | `Int?` | Error code (when status=3, 20220-20230) |
| `progress` | `Int?` | Progress 0-100 |
| `recordId` | `String?` | Merged result recordId |

### `TaskStatusParam`
Transcription task status query input.

| Field | Type | Description |
| --- | --- | --- |
| `taskType` | `Int?` | Task type |
| `deviceId` | `String` | Device ID |
| `fileIds` | `List<String>` | File IDs to query |
| `keys` | `String?` | Extra key |

### `TaskStatusResponse`
Transcription task status response.

| Field | Type | Description |
| --- | --- | --- |
| `success` | `List<String>` | Successfully transcribed file IDs |
| `fail` | `List<String>` | Failed transcription file IDs |
| `summarySuccess` | `List<String>` | Successfully summarized file IDs |
| `summaryFail` | `List<String>` | Failed summary file IDs |
| `translateSuccess` | `List<String>` | Successfully translated file IDs |
| `translateFail` | `List<String>` | Failed translation file IDs |

### `LauncherStateBean`
Quick entry status (element returned by `getQuickEntryList`).

| Field | Type | Description |
| --- | --- | --- |
| `type` | `Int` | Component type (101/102/103/104) |
| `id` | `RecordLauncherId` | Component ID |
| `state` | `ComponentAddState` | Add state: `0`hide add/`1`added/`2`not added |

### `PushRouteInfo`
Push route info.

| Field | Type | Description |
| --- | --- | --- |
| `pushType` | `String` | Push event type (e.g. `RecordTranscribe`) |
| `recordId` | `String` | Recording ID |
| `tab` | `String` | Detail page tab: `transcribe`/`summarize` |
| `extraInfo` | `String` | Extra info JSON string (reserved) |

### `RecordUpdateInfo`
Recording item change info (file data change event).

| Field | Type | Description |
| --- | --- | --- |
| `recordId` | `String` | Recording ID |
| `name` | `String` | File name |
| `cloudSyncStatus` | `Int` | Cloud sync status |
| `transferStatus` | `Int` | Transcription status |
| `summaryStatus` | `Int` | Summary status |
| `translateStatus` | `Int` | Translation status |
| `noteFileConvertState` | `Int` | Note convert status |
| `asrStatus` | `Int` | ASR status |
| `summaryImageStatus` | `Int` | Summary image status |
| `summaryImageUrl` | `String?` | Summary image URL |
| `tags` | `List<String>?` | Tag list |

### `BTConnectedStatus`
Earphone Bluetooth connection status.

| Field | Type | Description |
| --- | --- | --- |
| `devId` | `String` | Device ID |
| `connectedStatus` | `Int` | `1`connected/`0`not connected |

### `PhoneBatteryInfo`
Device battery.

| Field | Type | Description |
| --- | --- | --- |
| `needShow` | `Boolean` | Whether to show |
| `batteryValue` | `Double` | Battery value |

### `RecordQualityInfo`
Recording quality.

| Field | Type | Description |
| --- | --- | --- |
| `needShow` | `Boolean` | Whether to show |
| `snrValue` | `Double` | Signal-to-noise ratio |

### `TranslationResult`
Text translation result.

| Field | Type | Description |
| --- | --- | --- |
| `from` | `String` | Actual source language |
| `to` | `String` | Actual target language |
| `translateResult` | `ArrayList<TranslationDetail>` | Translation detail list |

**`TranslationDetail`**: `src: String?` (source text), `dst: String?` (translated text)

### `TranslationFile`
Translation record file.

| Field | Type | Description |
| --- | --- | --- |
| `fileId` | `Long?` | Number (auto-increment) |
| `noteFileId` | `Long?` | Associated note file ID |
| `uid` | `String?` | User ID |
| `directoryId` | `Long?` | Directory ID |
| `deviceUniqueId` | `String?` | Unique device recording identifier |
| `name` | `String?` | File name |
| `recordTime` | `Long?` | Recording time |
| `duration` | `Long?` | Recording duration |
| `recordType` | `Int?` | `0`phone/`1`meeting/`2`Pro 1v1/`3`entry 1v1/`4`text translation |
| `audioFormat` | `Int?` | Audio format |
| `deviceId` | `String?` | Device ID |
| `source` | `Int?` | Source: `0`unknown/`1`Bluetooth/`2`MIC/`3`Pro/`4`card |
| `filePath` | `String?` | Recording file path |
| `wavFilePath` | `String?` | wav file path |
| `amplitudes` | `String?` | Amplitude string |
| `visit` | `Int?` | Visit status: `0`unread/`1`read/`2`transcribed unread/`3`transcribed read |
| `originalLanguage` | `String?` | Source language |
| `targetLanguage` | `String?` | Target language |
| `originalText` | `String?` | Source text |
| `targetText` | `String?` | Translated text |
| `recordId` | `String?` | Recording ID |
| `agentId` | `String?` | Agent ID |
| `gid` | `String?` | Home ID |

### `TranslationFilesParams`
Translation list query input.

| Field | Type | Description |
| --- | --- | --- |
| `type` | `Int?` | Translation type: `0`all/`1`real-time/`2`face-to-face Pro/`3`face-to-face entry/`4`text |
| `orderBy` | `Int?` | `0`fileId/`1`recordTime/`2`updateAt |
| `asc` | `Int?` | `0`descending/`1`ascending |

### `RemoveParam`
Delete input (for internal construction; `removeFileList` generates it automatically).

| Field | Type | Description |
| --- | --- | --- |
| `fileId` | `Long` | File ID |
| `isDeleteAll` | `Boolean` | `false`audio only/`true`audio + record |
| `deleteType` | `DeleteTypeDef?` | Delete type (preferred) |

---
