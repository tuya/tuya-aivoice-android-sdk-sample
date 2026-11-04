package com.tuya.smart.ai_voice.nativeui.impt

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import com.thingclips.smart.earphone.enhance.api.bean.FileImportedFileInfo
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.FragmentNativeDirectImportBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class NativeAudioDirectImportFragment : NativeAudioImportBaseFragment() {
    private lateinit var binding: FragmentNativeDirectImportBinding
    private var lastClickAt = 0L
    private var copyJob: Job? = null

    override val layoutId: Int
        get() = R.layout.fragment_native_direct_import

    override fun onViewReady() {
        binding = FragmentNativeDirectImportBinding.bind(requireView())
        binding.btnDirectImport.setOnClickListener { importBuiltInAudio() }
        binding.btnCancelImport.setOnClickListener {
            manager.cancelImport(simpleCallback("cancelImport"))
        }
    }

    override fun onImportCompleted(
        successCount: Int,
        importedFiles: List<FileImportedFileInfo>
    ) {
        if (successCount <= 0) return
        if (successCount > 1) {
            showCompletionDialog(
                R.string.native_import_dialog_multiple_title,
                R.string.native_import_dialog_multiple_message
            ) { openRecordList() }
            return
        }
        val recordId = importedFiles.firstOrNull()?.recordId
        if (recordId.isNullOrEmpty()) {
            showToast(getString(R.string.native_import_toast_list_not_ready))
            return
        }
        showCompletionDialog(
            R.string.native_import_dialog_single_title,
            R.string.native_import_dialog_single_message
        ) { openDetail(recordId) }
    }

    private fun importBuiltInAudio() {
        if (importing) {
            showImportingToast()
            return
        }
        val assetNames = requireContext().assets.list(ASSET_DIR)
            ?.filter { it.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS }
            ?.sorted()
            .orEmpty()
        if (assetNames.isEmpty()) {
            showToast(getString(R.string.native_import_toast_no_audio))
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickAt < CLICK_INTERVAL_MS) return
        lastClickAt = now

        copyJob?.cancel()
        copyJob = lifecycleScope.launch {
            val uris = runCatching { copyBuiltInAudio(assetNames) }.getOrNull()
            if (uris.isNullOrEmpty()) {
                showToast(getString(R.string.native_import_toast_copy_failed, "prepare audio"))
            } else {
                manager.importAudioFilesForNative(uris)
            }
        }
    }

    private suspend fun copyBuiltInAudio(assetNames: List<String>): List<Uri> =
        withContext(Dispatchers.IO) {
            val dir = File(requireContext().cacheDir, ASSET_DIR)
            dir.deleteRecursively()
            dir.mkdirs()
            assetNames.map { name ->
                val target = File(dir, name)
                requireContext().assets.open("$ASSET_DIR/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(target)
            }
        }

    companion object {
        private const val ASSET_DIR = "audio_impt"
        private val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "wav")
        private const val CLICK_INTERVAL_MS = 600L
    }
}
