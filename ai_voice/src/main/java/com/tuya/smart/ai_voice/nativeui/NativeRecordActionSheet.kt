package com.tuya.smart.ai_voice.nativeui

import android.app.Activity
import android.content.DialogInterface
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.thingclips.smart.android.network.Business
import com.thingclips.smart.android.network.http.BusinessResponse
import com.thingclips.smart.api.service.MicroServiceManager
import com.thingclips.smart.commonbiz.bizbundle.family.api.AbsBizBundleFamilyService
import com.thingclips.smart.earphone.enhance.api.bean.UpdateRecordTagResultParams
import com.thingclips.smart.earphone.enhance.api.listener.IOperateRecordShareLinkResult
import com.thingclips.smart.plugin.tuniaudiodetectmanager.ThingAudioDetectManagerNative
import com.thingclips.smart.sdk.api.IResultCallback
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.DialogNativeInputBinding
import com.tuya.smart.ai_voice.nativeui.business.AudioContentBusiness
import java.util.Arrays

/**
 * 录音「更多操作」面板：针对单条录音的一次性 API 调用集合。
 * 
 * 
 * 这些能力分属不同模块，但共同点是「作用于某一条录音」，因此集中在详情页而非各自独立成页：
 * 
 *  * 模块 9 · 分享链接 —— `operateRecordShareLink`，开启与关闭
 *  * 模块 6 · 按需下载音频 —— `syncDownloadNoteAudio`
 *  * 模块 3 · 重命名 —— `updateRecordTransferResult`
 *  * 模块 3 · 标签新增 / 删除 —— `updateRecordTagResult`
 *  * 模块 3 · 仅删本地音频 —— `removeFileList(ids, isDeleteAll=false)`
 *  * 模块 3 · 彻底删除 —— `removeFileList(ids, isDeleteAll=true)`
 * 
 * 用系统 `AlertDialog` 承载，不引入额外布局，便于整段复制。
 */
object NativeRecordActionSheet {
    // ===== operateRecordShareLink.shareStatus =====
    /** 开启分享。  */
    private const val SHARE_STATUS_ON = 1

    /** 关闭分享。  */
    private const val SHARE_STATUS_OFF = 2

    /** 分享类型，接口约定为数组，此处使用链接分享。  */
    private val SHARE_TYPE_LINK = mutableListOf<String?>("link")

    /** 分享链接有效期，7 天。  */
    private val SHARE_EXPIRE_MILLIS = 7L * 24 * 60 * 60 * 1000

    // ===== UpdateRecordTagResultParams.bizType =====
    /** 新增标签。  */
    private const val TAG_BIZ_ADD = 0

    /** 删除标签。  */
    private const val TAG_BIZ_REMOVE = 1

    /** `syncDownloadNoteAudio` 的 fileId 缺省值。  */
    private val FILE_ID_ABSENT = -1L

    /** 录音元信息的云端写入，重命名时与 SDK 的本地写入配对使用。  */
    private val CONTENT_BUSINESS = AudioContentBusiness()

    /**
     * 弹出操作面板。
     * 
     * @param activity         宿主
     * @param recordId         业务录音 ID（分享、标签、下载用）
     * @param recordTransferId 文件 ID（重命名、删除用）
     * @param currentName      当前文件名，作为重命名输入框默认值
     * @param callback         结果回调
     */
    fun show(
        activity: Activity, recordId: String,
        recordTransferId: Long, currentName: String?,
        callback: Callback
    ) {
        val items = arrayOf<String?>(
            activity.getString(R.string.native_action_share_on),
            activity.getString(R.string.native_action_share_off),
            activity.getString(R.string.native_action_download_audio),
            activity.getString(R.string.native_action_export_transcript),
            activity.getString(R.string.native_action_export_translation),
            activity.getString(R.string.native_action_rename),
            activity.getString(R.string.native_action_add_tag),
            activity.getString(R.string.native_action_remove_tag),
            activity.getString(R.string.native_list_delete_audio_only),
            activity.getString(R.string.native_action_delete),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.native_detail_more)
            .setItems(
                items,
                DialogInterface.OnClickListener { dialog: DialogInterface?, which: Int ->
                    when (which) {
                        0 -> operateShare(activity, recordId, SHARE_STATUS_ON, callback)
                        1 -> operateShare(activity, recordId, SHARE_STATUS_OFF, callback)
                        2 -> downloadAudio(activity, recordTransferId, recordId)
                        3 -> callback.onExportTranscript()
                        4 -> callback.onExportTranslation()
                        5 -> rename(activity, recordId, recordTransferId, currentName, callback)
                        6 -> editTag(activity, recordId, TAG_BIZ_ADD, callback)
                        7 -> editTag(activity, recordId, TAG_BIZ_REMOVE, callback)
                        8 -> removeLocalAudio(activity, recordTransferId, callback)
                        9 -> deleteRecord(activity, recordTransferId, currentName, callback)
                        else -> {}
                    }
                })
            .show()
    }

    // ===================== 模块 9 · 分享链接 =====================
    /**
     * 开启或关闭分享链接。
     * 
     * 
     * 开启时返回可分享的链接地址；关闭后原链接立即失效。
     * `password` 传 null 表示不设访问密码；`expireTime` 是绝对到期时间戳。
     * 
     * @param shareStatus [.SHARE_STATUS_ON] 或 [.SHARE_STATUS_OFF]
     */
    private fun operateShare(
        activity: Activity, recordId: String, shareStatus: Int,
        callback: Callback
    ) {
        val expireTime = System.currentTimeMillis() + SHARE_EXPIRE_MILLIS
        ThingAudioDetectManagerNative.getInstance().operateRecordShareLink(
            recordId, ArrayList(SHARE_TYPE_LINK), expireTime, shareStatus, null,
            object : IOperateRecordShareLinkResult {
                override fun onSuccess(link: String) {
                    activity.runOnUiThread {
                        // 开启分享时把链接给用户，关闭时 link 为空
                        if (!TextUtils.isEmpty(link)) {
                            toast(
                                activity, activity.getString(
                                    R.string.native_action_share_link, link
                                )
                            )
                        }
                        callback.onDataChanged()
                    }
                }

                override fun onError(errorCode: String, errorMessage: String) {
                    toastError(activity, errorCode, errorMessage)
                }
            })
    }

    // ===================== 模块 6 · 按需下载音频 =====================
    /**
     * 下载这条录音的云端音频到本地。
     * 
     * 
     * **该接口同步返回且无回调**，所以这里只能提示「已发起」。
     * 真正的下载结束由 `SyncObserver` 的 `DownloadListener.onFinish` 推送，
     * 详情页注册了它并按 `recordId` 过滤出本条后再提示结果，
     * 见 `NativeRecordDetailActivity.AudioDownloadListener`。
     * 单条的同步状态变化另由 `IRecordFileUpdateCallback` 的 `cloudSyncStatus` 反映。
     * 
     * @param recordTransferId 文件 ID；无效时按接口约定传 -1
     */
    private fun downloadAudio(activity: Activity, recordTransferId: Long, recordId: String) {
        val fileId = if (recordTransferId > 0) recordTransferId else FILE_ID_ABSENT
        ThingAudioDetectManagerNative.getInstance().syncDownloadNoteAudio(fileId, recordId)
        toast(activity, activity.getString(R.string.native_action_toast_download_started))
    }

    // ===================== 模块 3 · 元信息更新 =====================
    /**
     * 重命名。**本地与云端要分别写一次**：
     * 
     *  1. SDK 的 `updateRecordTransferResult` 改本地库——界面立刻能看到新名字，
     * 但云端仍是旧值。该接口的可选参数传 null 表示「不修改该字段」，故只填 `name`
     *  1. [AudioContentBusiness.updateFileName] 改云端——其他端与重装后能看到，
     * 但本地库不会自动跟着变
     * 
     * 只写其中一处都会导致两端不一致。本地成功后再写云端，云端失败时提示但不回滚本地，
     * 让使用者能看到「本地已改、云端未同步」这个中间态。
     */
    private fun rename(
        activity: Activity, recordId: String?, recordTransferId: Long,
        currentName: String?, callback: Callback
    ) {
        val inputBinding = DialogNativeInputBinding.inflate(LayoutInflater.from(activity))
        val inputView = inputBinding.root
        val input = inputBinding.nativeDialogInput
        input.setText(if (currentName == null) "" else currentName)
        AlertDialog.Builder(activity)
            .setTitle(R.string.native_action_rename)
            .setView(inputView)
            .setPositiveButton(
                R.string.native_action_confirm,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    val name = input.getText().toString().trim { it <= ' ' }
                    if (TextUtils.isEmpty(name)) return@OnClickListener
                    ThingAudioDetectManagerNative.getInstance().updateRecordTransferResult(
                        recordTransferId, name, null, null, null, null, null, null,
                        object : IResultCallback {
                            override fun onSuccess() {
                                activity.runOnUiThread {
                                    renameOnCloud(activity, recordId, name)
                                    // 交给详情页收尾：同步总结里的 title，再刷新
                                    callback.onRenamed(name)
                                }
                            }

                            override fun onError(code: String?, error: String?) {
                                toastError(activity, code, error)
                            }
                        })
                })
            .setNegativeButton(R.string.native_list_delete_cancel, null)
            .show()
    }

    /**
     * 把新文件名写到云端。
     * 
     * @param recordId 业务录音 ID
     * @param name     新文件名
     */
    private fun renameOnCloud(activity: Activity, recordId: String?, name: String?) {
        val homeId = currentHomeId()
        if (homeId <= 0) {
            toast(activity, activity.getString(R.string.native_action_rename_no_home))
            return
        }
        CONTENT_BUSINESS.updateFileName(
            recordId, name, homeId,
            object : Business.ResultListener<Boolean?> {
                override fun onSuccess(
                    response: BusinessResponse?,
                    result: Boolean?,
                    apiName: String?
                ) {
                    // 云端改名成功，界面已在本地写入时刷新过，此处无需再动
                }

                override fun onFailure(
                    response: BusinessResponse?,
                    result: Boolean?,
                    apiName: String?
                ) {
                    val code = if (response == null) "-" else response.getErrorCode()
                    val msg = if (response == null) "" else response.getErrorMsg()
                    // 云端失败不回滚本地，但要让使用者看见「本地已改、云端未同步」
                    toastError(activity, code, msg)
                }
            })
    }

    /**
     * @return 当前家庭 ID；家庭服务不可用时返回 `0`
     */
    private fun currentHomeId(): Long {
        try {
            val familyService = MicroServiceManager.getInstance()
                .findServiceByInterface<AbsBizBundleFamilyService?>(AbsBizBundleFamilyService::class.java.getName())
            return if (familyService == null) 0L else familyService.getCurrentHomeId()
        } catch (e: Exception) {
            return 0L
        }
    }

    /**
     * 只删本地音频：`removeFileList([id], isDeleteAll=false)`。
     * 
     * 
     * **与彻底删除是同一个接口，靠 `isDeleteAll` 区分**：这里录音记录、转写与总结
     * 全部保留，删掉的只是占空间的音频文件，之后还能用
     * `syncDownloadNoteAudio` 重新下回来。
     * 
     * 
     * 因为可恢复，所以**不做二次确认**——与 [.deleteRecord] 的强确认形成对照。
     * 记录还在，详情页不必关闭，只通知调用方刷新。
     */
    private fun removeLocalAudio(
        activity: Activity, recordTransferId: Long,
        callback: Callback
    ) {
        ThingAudioDetectManagerNative.getInstance().removeFileList(
            mutableListOf<Long?>(recordTransferId), false,
            object : IResultCallback {
                override fun onSuccess() {
                    activity.runOnUiThread {
                        toast(
                            activity, activity.getString(
                                R.string.native_action_toast_local_audio_removed
                            )
                        )
                        callback.onDataChanged()
                    }
                }

                override fun onError(code: String?, error: String?) {
                    toastError(activity, code, error)
                }
            })
    }

    /**
     * 彻底删除这条录音：`removeFileList([id], isDeleteAll=true)`，本地与云端都会删掉，不可恢复。
     * 
     * 
     * 删除后详情已不存在，通过 [Callback.onRecordDeleted] 通知调用方关闭页面。
     */
    private fun deleteRecord(
        activity: Activity, recordTransferId: Long,
        currentName: String?, callback: Callback
    ) {
        val name: String = if (currentName.isNullOrEmpty()) {
            activity.getString(com.tuya.smart.ai_voice.R.string.native_unnamed)
        } else {
            currentName
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.native_list_delete_title)
            .setMessage(activity.getString(R.string.native_list_delete_msg, name))
            .setPositiveButton(
                R.string.native_list_delete_confirm,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    ThingAudioDetectManagerNative.getInstance().removeFileList(
                        mutableListOf<Long?>(recordTransferId), true,
                        object : IResultCallback {
                            override fun onSuccess() {
                                activity.runOnUiThread { callback.onRecordDeleted() }
                            }

                            override fun onError(code: String?, error: String?) {
                                toastError(activity, code, error)
                            }
                        })
                })
            .setNegativeButton(R.string.native_list_delete_cancel, null)
            .show()
    }

    /**
     * 标签新增 / 删除。两种 `bizType` 的 `tags` 含义不同：
     * 
     *  * [.TAG_BIZ_ADD] —— `tags` 是要新增的标签
     *  * [.TAG_BIZ_REMOVE] —— `tags` 是要删除的标签
     * 
     * 接口还支持 `bizType=2` 重排顺序，要求传**完整的新顺序**、漏传即丢弃，
     * 靠输入框难以可靠操作（应配合可拖拽列表），**本 Demo 不演示**。
     * 
     * @param bizType 操作类型
     */
    private fun editTag(
        activity: Activity, recordId: String, bizType: Int,
        callback: Callback
    ) {
        val inputBinding = DialogNativeInputBinding.inflate(LayoutInflater.from(activity))
        val inputView = inputBinding.root
        val input = inputBinding.nativeDialogInput
        input.setHint(R.string.native_action_tag_hint)
        AlertDialog.Builder(activity)
            .setTitle(tagDialogTitle(bizType))
            .setView(inputView)
            .setPositiveButton(
                R.string.native_action_confirm,
                DialogInterface.OnClickListener { d: DialogInterface?, w: Int ->
                    val tag = input.getText().toString().trim { it <= ' ' }
                    if (TextUtils.isEmpty(tag)) return@OnClickListener
                    val param = UpdateRecordTagResultParams(
                        recordId,
                        bizType,
                        ArrayList(
                            tag.split(",".toRegex())
                                .dropLastWhile { it.isEmpty() }))
                    ThingAudioDetectManagerNative.getInstance().updateRecordTagResult(
                        param,
                        resultCallback(activity, callback)
                    )
                })
            .setNegativeButton(R.string.native_list_delete_cancel, null)
            .show()
    }

    /**
     * @param bizType 标签操作类型
     * @return 弹窗标题资源 ID
     */
    private fun tagDialogTitle(bizType: Int): Int {
        return if (bizType == TAG_BIZ_REMOVE)
            R.string.native_action_remove_tag
        else
            R.string.native_action_add_tag
    }

    /**
     * 构造统一的结果回调：成功通知刷新，失败提示使用者。
     */
    private fun resultCallback(activity: Activity, callback: Callback): IResultCallback {
        return object : IResultCallback {
            override fun onSuccess() {
                activity.runOnUiThread { callback.onDataChanged() }
            }

            override fun onError(code: String?, error: String?) {
                toastError(activity, code, error)
            }
        }
    }

    /**
     * 创建对话框用的输入区。
     * 
     * 
     * 用布局而非裸 `new EditText(context)`：后者没有背景与内边距，
     * 空输入时在对话框里几乎看不见，hint 也会跟着主题走色，标签这类**靠 hint 说明输入格式**
     * 的场景会直接变成不可用。取值用 `findViewById(R.id.native_dialog_input)`。
     * 
     * @param activity 宿主
     * @return 输入区根视图
     */
    private fun createInputView(activity: Activity?): View {
        return LayoutInflater.from(activity).inflate(R.layout.dialog_native_input, null)
    }

    /**
     * 提示接口调用失败。SDK 回调可能在子线程，内部已切回主线程。
     * 
     * @param code  错误码
     * @param error 错误消息
     */
    private fun toastError(activity: Activity, code: String?, error: String?) {
        toast(activity, activity.getString(R.string.native_toast_api_failed, code, error))
    }

    private fun toast(activity: Activity, message: String?) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 操作结果回调，供详情页刷新界面。
     */
    interface Callback {
        /** 数据已变更，调用方应重新拉取详情。  */
        fun onDataChanged()

        /**
         * 文件名已改（本地已写入）。
         * 
         * 
         * 单独给一个回调而不是复用 [.onDataChanged]，是因为改名还有一步收尾：
         * 总结 JSON 里的 `title` 是文件名的另一个副本，必须一并更新，
         * 否则下次加载总结会把名字覆盖回旧值。那份 JSON 由详情页持有，只能由它来写。
         * 
         * @param newName 新文件名
         */
        fun onRenamed(newName: String?)

        /** 录音已被彻底删除，详情已不存在，调用方应关闭页面。  */
        fun onRecordDeleted()

        /**
         * 用户点了「导出转写文本」，请调用方自行导出。
         * 
         * 
         * 这一项不像其余操作那样在本类内部闭环，因为导出的数据源是**调用方已加载到内存的转写正文**
         * （文件转写的原始数组 / 实时转写的句子列表），本类只持有 ID，拿不到内容。
         * 与其把内容当参数塞进 [.show]（那样每次弹面板都要先拼一遍整篇文案，
         * 哪怕用户点的是重命名），不如把动作转发回去。
         */
        fun onExportTranscript()

        /**
         * 用户点了「导出翻译」。
         * 
         * 
         * 与 [.onExportTranscript] 分开是因为两者的产出是两份独立文件，
         * 下游拿去做语料或对比时通常只要其中一份。
         */
        fun onExportTranslation()
    }
}
