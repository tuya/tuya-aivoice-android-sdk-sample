package com.tuya.smart.ai_voice.ui;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.tuya.smart.ai_voice.R;

public class AiVoiceHomeActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private ImageView tabHomeIcon;
    private ImageView tabMineIcon;
    private TextView tabHomeText;
    private TextView tabMineText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_voice);
        bindViews();
        setupViewPager();
        bindTabClicks();
    }

    private void bindViews() {
        viewPager = findViewById(R.id.ai_voice_view_pager);
        tabHomeIcon = findViewById(R.id.ai_voice_tab_home_icon);
        tabMineIcon = findViewById(R.id.ai_voice_tab_mine_icon);
        tabHomeText = findViewById(R.id.ai_voice_tab_home_text);
        tabMineText = findViewById(R.id.ai_voice_tab_mine_text);
    }

    private void setupViewPager() {
        viewPager.setAdapter(new AiVoicePagerAdapter(this));
        viewPager.setOffscreenPageLimit(1);
        updateTabSelection(0);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateTabSelection(position);
            }
        });
    }

    private void bindTabClicks() {
        findViewById(R.id.ai_voice_tab_home).setOnClickListener(v -> viewPager.setCurrentItem(0, false));
        findViewById(R.id.ai_voice_tab_mine).setOnClickListener(v -> viewPager.setCurrentItem(1, false));
    }

    private void updateTabSelection(int position) {
        boolean isHome = position == 0;
        tabHomeIcon.setImageResource(isHome ? R.drawable.ic_ai_home_selected : R.drawable.ic_ai_home);
        tabMineIcon.setImageResource(isHome ? R.drawable.ic_ai_user : R.drawable.ic_ai_user_selected);
        int selectedColor = ContextCompat.getColor(this, R.color.ai_voice_tab_selected);
        int unselectedColor = ContextCompat.getColor(this, R.color.ai_voice_tab_unselected);
        tabHomeText.setTextColor(isHome ? selectedColor : unselectedColor);
        tabMineText.setTextColor(isHome ? unselectedColor : selectedColor);
    }
}
