package com.tuya.smart.ai_voice.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.thingclips.sdk.os.ThingOSUser;
import com.thingclips.smart.android.user.bean.User;
import com.thingclips.stencil.utils.LoginHelper;
import com.tuya.smart.ai_voice.R;

public class AiVoiceMineFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_ai_voice_mine, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);
        bindClickListeners(view);
    }

    private void bindViews(View view) {
        TextView nameView = view.findViewById(R.id.ai_voice_user_name);
        TextView phoneView = view.findViewById(R.id.ai_voice_user_phone);
        User user = ThingOSUser.getUserInstance().getUser();
        nameView.setText(user.getUsername());
        phoneView.setText(user.getMobile());
    }

    private void bindClickListeners(View view) {
        view.findViewById(R.id.ai_voice_logout).setOnClickListener(v -> {
            LoginHelper.onLogout(requireContext());
        });
    }
}
