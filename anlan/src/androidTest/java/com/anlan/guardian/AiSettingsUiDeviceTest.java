package com.anlan.guardian;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 正式菜单、空配置、掩码及保存/清除；只输入可公开的虚拟凭据。 */
@RunWith(AndroidJUnit4.class)
public final class AiSettingsUiDeviceTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private void collect(View view,List<View> children){children.add(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),children);}
    private View label(MainActivity activity,String text){List<View> views=new ArrayList<>();collect(activity.getWindow().getDecorView(),views);for(View view:views)if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;throw new AssertionError("未找到界面元素："+text);}
    @Test public void aiModelMenuHasBlankFieldsAndSavesMaskedToken()throws Exception{
        AiSettings settings=new AiSettings(app);AiSettings.Profile previous=settings.load();settings.clear();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new android.content.Intent(app,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            instrumentation.runOnMainSync(()->{
                ControlPanel panel=new ControlPanel(activity,ignored->{},ignored->{});panel.show();
                List<View> menu=new ArrayList<>();collect(activity.getWindow().getDecorView(),menu);
                assertTrue(menu.indexOf(label(activity,"AI模型"))<menu.indexOf(label(activity,"关于")));
                label(activity,"AI模型").performClick();label(activity,"‹").performClick();
                label(activity,"设置").performClick();menu.clear();collect(activity.getWindow().getDecorView(),menu);
                assertFalse(menu.stream().anyMatch(view->view instanceof TextView&&"AI模型".contentEquals(((TextView)view).getText())));
                panel.show();label(activity,"AI模型").performClick();
                List<View> views=new ArrayList<>();collect(activity.getWindow().getDecorView(),views);List<EditText> fields=new ArrayList<>();for(View view:views)if(view instanceof EditText)fields.add((EditText)view);
                assertEquals(3,fields.size());for(EditText field:fields)assertEquals("",field.getText().toString());
                fields.get(0).setText("https://example.com/v1");fields.get(1).setText("ui-dummy-not-a-real-secret");fields.get(2).setText("ui-test-model");
                for(View view:views)if(view instanceof CheckBox&&"启用 AI 对话".contentEquals(((CheckBox)view).getText()))((CheckBox)view).setChecked(true);
                label(activity,"保存").performClick();assertTrue(settings.load().enabled);assertEquals("ui-dummy-not-a-real-secret",settings.load().token);
                new AiModelPanel(activity,()->{}).show();views.clear();collect(activity.getWindow().getDecorView(),views);boolean masked=false;
                for(View view:views)if(view instanceof EditText){String value=((EditText)view).getText().toString();assertFalse(value.contains("ui-dummy"));if(value.equals("••••••••"))masked=true;}assertTrue(masked);
                label(activity,"清除").performClick();assertFalse(settings.load().enabled);assertEquals("",settings.load().baseUrl);
            });
            android.graphics.Bitmap image=instrumentation.getUiAutomation().takeScreenshot();if(image!=null){try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(app.getCacheDir(),"ai-settings-empty.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
        }finally{settings.save(previous);instrumentation.runOnMainSync(activity::finish);app.stopService(new android.content.Intent(app,GuardianService.class));}
    }
}
