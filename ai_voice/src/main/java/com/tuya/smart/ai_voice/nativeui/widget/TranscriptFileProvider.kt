package com.tuya.smart.ai_voice.nativeui.widget

import androidx.core.content.FileProvider

/**
 * 导出转写文件专用的 [FileProvider] 子类。
 * 
 * 
 * **为什么不直接用 `androidx.core.content.FileProvider`：**
 * SDK 依赖里（`thingsmart-stencilLib`）已经声明过一个 `android:name` 为
 * `androidx.core.content.FileProvider` 的节点。Manifest 合并以 `android:name`
 * 判定是否为同一节点，再声明一次会因 `authorities` 与 `FILE_PROVIDER_PATHS`
 * 不同而直接合并失败；若用 `tools:replace` 强行覆盖，又会把 SDK 自己那份顶掉。
 * 
 * 
 * 起一个空子类就能拿到独立的 `android:name`，两份 provider 各自成立、互不干扰。
 * 这个类没有任何自有逻辑，全部行为继承自父类。
 */
class TranscriptFileProvider : FileProvider()
