package com.tuya.smart.ai_voice.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class AiVoicePagerAdapter extends FragmentStateAdapter {

    public AiVoicePagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 1) {
            return new AiVoiceMineFragment();
        }
        return new AiVoiceHomeFragment();
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}
