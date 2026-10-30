package com.tuya.smart.ai_voice.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.thingclips.sdk.os.ThingOSUser
import com.thingclips.stencil.utils.LoginHelper
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.FragmentAiVoiceMineBinding

class AiVoiceMineFragment : Fragment() {
    /** 布局绑定。  */
    private lateinit var binding: FragmentAiVoiceMineBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_voice_mine, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentAiVoiceMineBinding.bind(view)
        bindViews(view)
        bindClickListeners(view)
    }

    private fun bindViews(view: View) {
        val nameView = binding.aiVoiceUserName
        val phoneView = binding.aiVoiceUserPhone
        val user = ThingOSUser.getUserInstance().getUser()
        nameView.setText(user?.getUsername() ?: "")
        phoneView.setText(user?.getMobile() ?: "")
    }

    private fun bindClickListeners(view: View) {
        binding.aiVoiceLogout
            .setOnClickListener(View.OnClickListener { v: View? ->
                LoginHelper.onLogout(requireContext())
            })
    }
}
