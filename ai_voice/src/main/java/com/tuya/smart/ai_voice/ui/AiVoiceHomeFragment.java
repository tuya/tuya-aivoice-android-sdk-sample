package com.tuya.smart.ai_voice.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.thingclips.smart.api.MicroContext;
import com.thingclips.smart.api.router.UrlRouter;
import com.thingclips.smart.api.service.MicroServiceManager;
import com.thingclips.smart.commonbiz.bizbundle.family.api.AbsBizBundleFamilyService;
import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.panelcaller.api.AbsPanelCallerService;
import com.thingclips.smart.sdk.bean.DeviceBean;
import com.tuya.smart.ai_voice.R;

import java.util.List;

public class AiVoiceHomeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_ai_voice_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindClickListeners(view);
        setupDeviceList(view);
    }

    private void bindClickListeners(View view) {
        /**
         * AI 笔记
         * 主页：thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex
         * 录音：thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DliveRecording
         * 同声传译：thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DsimultaneousInterpretation
         * 实时转写：thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DrealTimeRecording
         * <p>
         * AI 翻译
         * 主页：thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2Fhome%2Findex
         * 同声传译：thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2Fsimultaneous%2Findex
         * 对话翻译：thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2FFaceToFace%2Findex
         */
        view.findViewById(R.id.ai_voice_card_notes).setOnClickListener(v -> {
            UrlRouter.execute(getActivity(), "thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex");
        });
        view.findViewById(R.id.ai_voice_card_translate).setOnClickListener(v -> {
            UrlRouter.execute(getActivity(), "thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2Fhome%2Findex");
        });
        view.findViewById(R.id.ai_voice_add).setOnClickListener(v -> {
            //配网
            Intent i = new Intent();
            i.setClassName(requireContext(), "com.tuya.smart.bizbundle.activator.demo.DeviceActivatorActivity");
            startActivity(i);
        });
        view.findViewById(R.id.ai_voice_action_record).setOnClickListener(v -> {
            UrlRouter.execute(getActivity(), "thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DliveRecording");

        });
        view.findViewById(R.id.ai_voice_action_simultaneous).setOnClickListener(v -> {
            UrlRouter.execute(getActivity(), "thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DsimultaneousInterpretation");

        });
        view.findViewById(R.id.ai_voice_action_transcribe).setOnClickListener(v -> {
            UrlRouter.execute(getActivity(), "thingSmart://miniApp?url=godzilla%3A%2F%2Ftyylldwlb8411tg8u2%2Fpages%2Fhome%2Findex%3FmodeKey%3DrealTimeRecording");
        });
        view.findViewById(R.id.ai_voice_action_simultaneous_translate).setOnClickListener(v -> {
            UrlRouter.execute(getActivity(), "thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2Fsimultaneous%2Findex");
        });
        view.findViewById(R.id.ai_voice_action_dialog_translate).setOnClickListener(v -> {
            UrlRouter.execute(getActivity(), "thingSmart://miniApp?url=godzilla%3A%2F%2Fty0u9m1s5ea1k71m2h%2Fpages%2FFaceToFace%2Findex");
        });
    }

    public List<DeviceBean> getDeviceList() {
        try {
            AbsBizBundleFamilyService familyService = MicroServiceManager.getInstance().findServiceByInterface(AbsBizBundleFamilyService.class.getName());
            return ThingHomeSdk.newHomeInstance(familyService.getCurrentHomeId()).getHomeBean().getDeviceList();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void setupDeviceList(View view) {
        RecyclerView recyclerView = view.findViewById(R.id.ai_voice_device_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        AiVoiceDeviceAdapter adapter = new AiVoiceDeviceAdapter();
        adapter.setOnDeviceClickListener(bean -> {
            AbsPanelCallerService service = MicroContext.getServiceManager().findServiceByInterface(AbsPanelCallerService.class.getName());
            service.goPanelWithCheckAndTip(requireActivity(), bean.devId);
        });
        recyclerView.setAdapter(adapter);
        adapter.submitList(getDeviceList());
    }
}
