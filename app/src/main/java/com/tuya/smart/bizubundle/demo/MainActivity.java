package com.tuya.smart.bizubundle.demo;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.thingclips.smart.api.service.MicroServiceManager;
import com.thingclips.smart.bizbundle.initializer.BizBundleInitializer;
import com.thingclips.smart.commonbiz.bizbundle.family.api.AbsBizBundleFamilyService;
import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.home.sdk.bean.HomeBean;
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback;
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback;
import com.thingclips.smart.utils.ProgressUtil;
import com.thingclips.smart.utils.ToastUtil;
import com.tuya.smart.ai_voice.ui.AiVoiceHomeActivity;

import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BizBundleInitializer.onLogin();
        getHomeList();
    }

    private void getHomeList() {
        ThingHomeSdk.getHomeManagerInstance().queryHomeList(new IThingGetHomeListCallback() {
            @Override
            public void onSuccess(List<HomeBean> list) {
                if (!list.isEmpty()) {
                    setCurrentFamily(list.get(0));
                } else {
                    ToastUtil.showToast(MainActivity.this, "home list is null,plz create home");
                    ThingHomeSdk.getHomeManagerInstance().createHome("My Home", 0, 0, "My Home Location", Arrays.asList("home"), new IThingHomeResultCallback() {
                        @Override
                        public void onSuccess(HomeBean bean) {
                            // do something
                            ToastUtil.showToast(MainActivity.this, "Add family success");
                            getHomeList();
                        }

                        @Override
                        public void onError(String errorCode, String errorMsg) {
                            // do something
                            ToastUtil.showToast(MainActivity.this, "errorCode: " + errorCode + "\nerrorMsg: " + errorMsg);
                        }
                    });

                }
                ProgressUtil.hideLoading();
            }

            @Override
            public void onError(String s, String s1) {
                ProgressUtil.hideLoading();
                Toast.makeText(MainActivity.this, s + "\n" + s1, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void requestHomeDetail(long id) {
        ThingHomeSdk.newHomeInstance(id).getHomeDetail(new IThingHomeResultCallback() {
            @Override
            public void onSuccess(HomeBean bean) {
                startActivity(new Intent(MainActivity.this, AiVoiceHomeActivity.class));
                finish();
            }

            @Override
            public void onError(String errorCode, String errorMsg) {

            }
        });
    }


    /**
     * 业务包接入后必须实现家庭服务(商城业务包可以不接入)
     * you should implementation AbsBizBundleFamilyService(mall bizbundle can not implementation)
     */
    public void setCurrentFamily(HomeBean homeBean) {
        AbsBizBundleFamilyService familyService = MicroServiceManager.getInstance().findServiceByInterface(AbsBizBundleFamilyService.class.getName());
        familyService.shiftCurrentFamily(homeBean.getHomeId(), homeBean.getName());
        requestHomeDetail(homeBean.getHomeId());
    }

}