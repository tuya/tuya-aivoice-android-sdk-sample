package com.tuya.smart.ai_voice.nativeui.impt

import com.thingclips.smart.earphone.enhance.api.listener.IAudioImportCallBack
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.FragmentNativePickerImportBinding

class NativeAudioPickerImportFragment : NativeAudioImportBaseFragment() {
    private lateinit var binding: FragmentNativePickerImportBinding

    override val layoutId: Int
        get() = R.layout.fragment_native_picker_import

    override fun onViewReady() {
        binding = FragmentNativePickerImportBinding.bind(requireView())
        binding.btnStartImport.setOnClickListener { startImport() }
        binding.btnCancelImport.setOnClickListener { cancelImport() }
        binding.btnRetryImport.setOnClickListener { retryImport() }
        binding.btnCancelRetry.setOnClickListener { cancelRetry() }
    }

    private fun startImport() {
        if (importing) {
            showImportingToast()
            return
        }
        manager.startImport(object : IAudioImportCallBack {
            override fun onSuccess() {
            }

            override fun onError(code: Int?, error: String?) {
                showStartError(code, error)
            }
        })
    }

    private fun cancelImport() {
        manager.cancelImport(simpleCallback("cancelImport"))
    }

    private fun retryImport() {
        manager.retryImport(simpleCallback("retryImport"))
    }

    private fun cancelRetry() {
        manager.cancelRetry(simpleCallback("cancelRetry"))
    }
}
