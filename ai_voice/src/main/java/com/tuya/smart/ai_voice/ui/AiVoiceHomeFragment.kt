package com.tuya.smart.ai_voice.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.thingclips.smart.api.MicroContext
import com.thingclips.smart.api.router.UrlRouter
import com.thingclips.smart.api.service.MicroServiceManager
import com.thingclips.smart.commonbiz.bizbundle.family.api.AbsBizBundleFamilyService
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.panelcaller.api.AbsPanelCallerService
import com.thingclips.smart.sdk.bean.DeviceBean
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.FragmentAiVoiceHomeBinding
import com.tuya.smart.ai_voice.nativeui.NativeEntryActivity
import com.tuya.smart.ai_voice.ui.AiVoiceDeviceAdapter.OnDeviceClickListener

class AiVoiceHomeFragment : Fragment() {
    /** 布局绑定。  */
    private lateinit var binding: FragmentAiVoiceHomeBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_voice_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentAiVoiceHomeBinding.bind(view)
        bindClickListeners(view)
        setupDeviceList(view)
    }

    private fun bindClickListeners(view: View) {
        /**
         * AI 笔记
         * 主页：thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex
         * 录音：thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DliveRecording
         * 同声传译：thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DsimultaneousInterpretation
         * 实时转写：thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DrealTimeRecording
         * 
         * 
         * AI 翻译
         * 主页：thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2Fhome%2Findex
         * 同声传译：thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2Fsimultaneous%2Findex
         * 对话翻译：thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2FFaceToFace%2Findex
         */
        binding.aiVoiceCardNotes
            .setOnClickListener(View.OnClickListener { v: View? ->
                UrlRouter.execute(
                    getActivity(),
                    "thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex"
                )
            })
        binding.aiVoiceCardTranslate
            .setOnClickListener(View.OnClickListener { v: View? ->
                UrlRouter.execute(
                    getActivity(),
                    "thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2Fhome%2Findex"
                )
            })
        binding.aiVoiceAdd
            .setOnClickListener(View.OnClickListener { v: View? ->
                //配网
                val i = Intent()
                i.setClassName(
                    requireContext(),
                    "com.tuya.smart.bizbundle.activator.demo.DeviceActivatorActivity"
                )
                startActivity(i)
            })
        binding.aiVoiceActionRecord
            .setOnClickListener(View.OnClickListener { v: View? ->
                UrlRouter.execute(
                    getActivity(),
                    "thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DliveRecording"
                )
            })
        binding.aiVoiceActionSimultaneous
            .setOnClickListener(View.OnClickListener { v: View? ->
                UrlRouter.execute(
                    getActivity(),
                    "thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DsimultaneousInterpretation"
                )
            })
        binding.aiVoiceActionTranscribe
            .setOnClickListener(View.OnClickListener { v: View? ->
                UrlRouter.execute(
                    getActivity(),
                    "thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DrealTimeRecording"
                )
            })
        binding.aiVoiceActionSimultaneousTranslate.setOnClickListener(
            View.OnClickListener { v: View? ->
                UrlRouter.execute(
                    getActivity(),
                    "thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2Fsimultaneous%2Findex"
                )
            })
        binding.aiVoiceActionDialogTranslate
            .setOnClickListener(View.OnClickListener { v: View? ->
                UrlRouter.execute(
                    getActivity(),
                    "thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2FFaceToFace%2Findex"
                )
            })
        // Native 能力：全部演示页收敛到总入口，宿主 Demo 与 aar 接入方走同一条路径
        binding.aiVoiceNativeEntry
            .setOnClickListener(View.OnClickListener { v: View? ->
                NativeEntryActivity.start(requireContext())
            })
    }

    val deviceList: MutableList<DeviceBean?>?
        get() {
            try {
                val familyService = MicroServiceManager.getInstance()
                    .findServiceByInterface<AbsBizBundleFamilyService>(AbsBizBundleFamilyService::class.java.getName())
                return ThingHomeSdk.newHomeInstance(familyService.getCurrentHomeId()).getHomeBean()
                    .getDeviceList()
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

    private fun setupDeviceList(view: View) {
        val recyclerView = binding.aiVoiceDeviceList
        recyclerView.setLayoutManager(LinearLayoutManager(requireContext()))
        val adapter = AiVoiceDeviceAdapter()
        adapter.setOnDeviceClickListener(object : AiVoiceDeviceAdapter.OnDeviceClickListener {
            override fun onDeviceClick(device: DeviceBean?) {
                val service = MicroContext.getServiceManager()
                    .findServiceByInterface<AbsPanelCallerService>(AbsPanelCallerService::class.java.getName())
                device?.let { service.goPanelWithCheckAndTip(requireActivity(), it.devId) }
            }
        })
        recyclerView.setAdapter(adapter)
        adapter.submitList(this.deviceList)
    }
}
