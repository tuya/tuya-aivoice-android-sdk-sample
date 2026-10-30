package com.tuya.smart.ai_voice.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class AiVoicePagerAdapter(fragmentActivity: FragmentActivity) :
    FragmentStateAdapter(fragmentActivity) {
    override fun createFragment(position: Int): Fragment {
        if (position == 1) {
            return AiVoiceMineFragment()
        }
        return AiVoiceHomeFragment()
    }

    override fun getItemCount(): Int {
        return 2
    }
}
