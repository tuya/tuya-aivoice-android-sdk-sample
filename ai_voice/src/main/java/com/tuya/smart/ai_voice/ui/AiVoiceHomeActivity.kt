package com.tuya.smart.ai_voice.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityAiVoiceBinding
import com.tuya.smart.ai_voice.nativeui.widget.SystemBarInsets

class AiVoiceHomeActivity : AppCompatActivity() {
    /** 布局绑定。  */
    private lateinit var binding: ActivityAiVoiceBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiVoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindViews()
        applySystemBarInsets()
        setupViewPager()
        bindTabClicks()
    }

    /**
     * 系统栏避让。
     * 
     * 
     * 这一页不能像 Native 演示页那样把上下 inset 都打在根布局上：根布局是灰色背景，
     * 底部 tab 栏是白色，那样做会在导航栏位置露出一条灰边。
     * 因此顶部 inset 给根布局，底部 inset 给白色 tab 栏，让它的背景铺到屏幕底边。
     */
    private fun applySystemBarInsets() {
        SystemBarInsets.applyPadding(binding.clAiVoiceRoot, true, false)
        SystemBarInsets.applyPadding(binding.aiVoiceTabBar, false, true)
        SystemBarInsets.setLightSystemBars(this)
    }

    private fun bindViews() {
    }

    private fun setupViewPager() {
        binding.aiVoiceViewPager.setAdapter(AiVoicePagerAdapter(this))
        binding.aiVoiceViewPager.setOffscreenPageLimit(1)
        updateTabSelection(0)
        binding.aiVoiceViewPager.registerOnPageChangeCallback(object : OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTabSelection(position)
            }
        })
    }

    private fun bindTabClicks() {
        binding.aiVoiceTabHome.setOnClickListener(View.OnClickListener { v: View? ->
            binding.aiVoiceViewPager.setCurrentItem(
                0,
                false
            )
        })
        binding.aiVoiceTabMine.setOnClickListener(View.OnClickListener { v: View? ->
            binding.aiVoiceViewPager.setCurrentItem(
                1,
                false
            )
        })
    }

    private fun updateTabSelection(position: Int) {
        val isHome = position == 0
        binding.aiVoiceTabHomeIcon.setImageResource(if (isHome) R.drawable.ic_ai_home_selected else R.drawable.ic_ai_home)
        binding.aiVoiceTabMineIcon.setImageResource(if (isHome) R.drawable.ic_ai_user else R.drawable.ic_ai_user_selected)
        val selectedColor = ContextCompat.getColor(this, R.color.ai_voice_tab_selected)
        val unselectedColor = ContextCompat.getColor(this, R.color.ai_voice_tab_unselected)
        binding.aiVoiceTabHomeText.setTextColor(if (isHome) selectedColor else unselectedColor)
        binding.aiVoiceTabMineText.setTextColor(if (isHome) unselectedColor else selectedColor)
    }
}
