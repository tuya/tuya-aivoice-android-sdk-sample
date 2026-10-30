# 模块 12 · 文件夹管理

| 项 | 内容 |
|---|---|
| Demo 页面 | 暂未实现 |
| 全局约定 | [接入指南](./README.md) |
| 分类查询 | [模块 3](./module-03-file-management.md) |

---

## 一、能力概述

提供录音文件夹的新建、重命名、删除、批量移动、回收站恢复和云端同步。
文件夹 ID 与录音列表中的 `RecordTransferResultBean.folderId` 对应。

系统有两个虚拟文件夹，不需要创建、也不占自建文件夹数量上限：

| `folderId` | 含义 |
|---|---|
| `UNFILED` | 未分类 |
| `RECYCLE_BIN` | 回收站（软删除，30 天内可恢复） |

---

## 二、接口清单

| 方法 | 作用 | 回调 |
|---|---|---|
| `addFolder` | 新建文件夹 | `IAudioFolderCallBack` |
| `renameFolder` | 重命名文件夹 | `IAudioFolderCallBack` |
| `deleteFolder` | 删除文件夹，夹内录音变为未分类 | `IAudioFolderCallBack` |
| `moveRecords` | 批量移动录音，或移入回收站 | `IAudioFolderCallBack` |
| `restoreFromRecycleBin` | 从回收站恢复录音 | `IAudioFolderCallBack` |
| `syncFolders` | 拉取云端文件夹及归属并写入本地 | `IRecordCallBack<Boolean>` |
| `addFolderChangeListener` / `removeFolderChangeListener` | 监听文件夹变更 | `IFolderChangeCallback` |

---

## 三、接口详解

### `addFolder(String folderName, IAudioFolderCallBack callback)`

新建文件夹。账号 `uid` 由 SDK 内部获取，无需传入。

### `renameFolder(String folderId, String newName, IAudioFolderCallBack callback)`

重命名文件夹。

### `deleteFolder(String folderId, IAudioFolderCallBack callback)`

删除文件夹。夹内录音不会被删除，而是变为未分类。

### `moveRecords(List<String> recordIds, String targetFolderId, IAudioFolderCallBack callback)`

批量移动录音。

| `targetFolderId` | 行为 |
|---|---|
| 普通文件夹 ID | 移动到该文件夹 |
| `null` 或空串 | 移回未分类 |
| `RECYCLE_BIN` | 移入回收站（软删除，30 天内可恢复） |

单次最多 200 条，超限时 `onError` 返回 `20306`。

### `restoreFromRecycleBin(List<String> recordIds, IAudioFolderCallBack callback)`

从回收站恢复录音。

- 恢复到移入回收站前的原文件夹；原文件夹已删除时落到未分类
- 成功后清除回收站过期时间
- `recordIds` 必须全部在回收站内，数量 1~200；空列表或超限返回 `20306`
- **失败只回调 `onError`，禁止降级为永久删除**

`moveRecords` 不能替代本方法：移动接口需要显式指定目标文件夹，且不会清除回收站过期时间。

成功后派发 `FolderChangeEvent`，`folderIds` 包含 `RECYCLE_BIN`。

### `syncFolders(String uid, boolean forceFullSync, IRecordCallBack<Boolean> callback)`

拉取云端文件夹及夹内归属并写入本地。`forceFullSync=true` 时忽略本地修改时间，强制全量覆盖。
成功返回 `true`，失败走 `onError`。

### 文件夹变更监听

```java
manager.addFolderChangeListener(callback);
manager.removeFolderChangeListener(callback);   // 传同一实例
```

`IFolderChangeCallback.onFolderChange(FolderChangeEvent event)` 当前主要派发 `delete` / `update`，
以底层实际回调为准。录音列表的中间状态仍由 `IRecordFileUpdateCallback` 推送。

---

## 四、数据结构

### `FolderWriteResult`

| 字段 | 类型 | 说明 |
|---|---|---|
| `folderId` | `String` | 文件夹 ID；移动时为目标文件夹 |
| `folderName` | `String` | 文件夹名称 |
| `sortName` | `String` | 排序名称，新建 / 重命名时由云端返回 |
| `result` | `Boolean` | 删除 / 移动是否成功；新建 / 重命名可能为 `null` |
| `sourceFolderIds` | `List<String>` | 移动时的源文件夹 ID 列表 |

### `FolderChangeEvent`

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | `String` | 变更类型：`add` / `move` / `delete` / `update` |
| `folderIds` | `List<String>` | 涉及的文件夹 ID，至少一个 |
| `folderName` | `String` | 新建或改名后的名称，可能为 `null` |

---

## 五、接入清单

1. 先用 `getCategoryOverview(CategoryOverviewScopeDef.FOLDER, ...)` 获取文件夹列表
2. 移动 / 恢复单次不超过 200 条
3. 回收站是虚拟文件夹，不占自建文件夹数量上限
4. 恢复失败时不要补删数据
5. 文件夹变更监听与录音列表刷新监听分工不同，不要互相替代
