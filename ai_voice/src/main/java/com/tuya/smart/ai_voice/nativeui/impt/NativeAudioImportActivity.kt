package com.tuya.smart.ai_voice.nativeui.impt

import android.content.Intent
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.tuya.smart.ai_voice.R
import com.tuya.smart.ai_voice.databinding.ActivityNativeAudioImportBinding
import com.tuya.smart.ai_voice.nativeui.base.NativeDemoBaseActivity

/**
 * 模块 5 · 音频导入容器页。文件选择器与业务直传分 Tab 展示。
 */
class NativeAudioImportActivity : NativeDemoBaseActivity() {
    private lateinit var binding: ActivityNativeAudioImportBinding

    override val contentLayoutId: Int
        get() = R.layout.activity_native_audio_import

    override val titleResId: Int
        get() = R.string.native_import_title

    override fun onContentViewCreated() {
        binding = ActivityNativeAudioImportBinding.bind(contentView())
        setupTabs()
    }

    override fun registerListeners() {
    }

    override fun unregisterListeners() {
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        manager.onActivityResult(requestCode, resultCode, data)
    }

    private fun setupTabs() {
        val fragments = listOf(
            NativeAudioPickerImportFragment(),
            NativeAudioDirectImportFragment()
        )
        val titles = listOf(
            getString(R.string.native_import_tab_picker),
            getString(R.string.native_import_tab_direct)
        )

        binding.tabImport.addTab(binding.tabImport.newTab().setText(titles[0]))
        binding.tabImport.addTab(binding.tabImport.newTab().setText(titles[1]))
        showFragment(fragments[0])

        binding.tabImport.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showFragment(fragments[tab.position])
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
            }
        })
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fl_import_container, fragment)
            .commit()
    }
}
