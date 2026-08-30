package com.tuya.smart.ai_voice.nativeui.widget;

import androidx.core.content.FileProvider;

/**
 * 导出转写文件专用的 {@link FileProvider} 子类。
 * <p>
 * <b>为什么不直接用 {@code androidx.core.content.FileProvider}：</b>
 * SDK 依赖里（{@code thingsmart-stencilLib}）已经声明过一个 {@code android:name} 为
 * {@code androidx.core.content.FileProvider} 的节点。Manifest 合并以 {@code android:name}
 * 判定是否为同一节点，再声明一次会因 {@code authorities} 与 {@code FILE_PROVIDER_PATHS}
 * 不同而直接合并失败；若用 {@code tools:replace} 强行覆盖，又会把 SDK 自己那份顶掉。
 * <p>
 * 起一个空子类就能拿到独立的 {@code android:name}，两份 provider 各自成立、互不干扰。
 * 这个类没有任何自有逻辑，全部行为继承自父类。
 */
public class TranscriptFileProvider extends FileProvider {
}
