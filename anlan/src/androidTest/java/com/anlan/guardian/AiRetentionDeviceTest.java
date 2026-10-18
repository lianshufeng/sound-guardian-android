package com.anlan.guardian;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class AiRetentionDeviceTest {
    private final Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void versionThreeMigrationKeepsOriginalEvent()throws Exception{
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app)){
            try(SQLiteDatabase db=isolated.openOrCreateDatabase("events.db",0,null)){db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY,label TEXT,name TEXT,start INTEGER,end INTEGER,active INTEGER)");db.execSQL("INSERT INTO events VALUES ('old','Cough','咳嗽',1,2,0)");db.setVersion(3);}
            try(EventStore store=new EventStore(isolated)){assertEquals(4,store.getReadableDatabase().getVersion());assertEquals("old",store.recent().get(0).id);try(android.database.Cursor rows=store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM conversations",null)){assertTrue(rows.moveToFirst());assertEquals(0,rows.getInt(0));}}
        }
    }
    @Test public void retentionCapsRecordsContentAgeAndCombinedBudget()throws Exception{
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app);EventStore helper=new EventStore(isolated);ConversationStore store=new ConversationStore(isolated)){
            isolated.getSharedPreferences("care",0).edit().putBoolean("save_replies",true).commit();SQLiteDatabase db=helper.getWritableDatabase();long now=System.currentTimeMillis();
            db.beginTransaction();try{for(int i=0;i<1100;i++){ContentValues row=new ContentValues();row.put("id","session-"+i);row.put("care","care-"+i);row.put("scenario","S02");row.put("event_id","");row.put("state","ENDED");row.put("reason","TEST");row.put("completed",now-i*1000);row.put("turns","[{\"user\":\"合成样本文字\",\"assistant\":\"回应\",\"played\":\"回应\",\"reason\":\"TEST\",\"segments\":[]}]");db.insertOrThrow("conversations",null,row);}db.setTransactionSuccessful();}finally{db.endTransaction();}
            store.clean(now);try(android.database.Cursor rows=db.rawQuery("SELECT COUNT(*),SUM(CASE WHEN turns<>'[]' THEN 1 ELSE 0 END) FROM conversations",null)){rows.moveToFirst();assertTrue(rows.getInt(0)<=1000);assertTrue(rows.getInt(1)<=300);}
            store.clean(now+3*86400000L+1);try(android.database.Cursor rows=db.rawQuery("SELECT COUNT(*) FROM conversations WHERE turns<>'[]'",null)){rows.moveToFirst();assertEquals(0,rows.getInt(0));}
            store.clean(now+3*86400000L+1);try(android.database.Cursor rows=db.rawQuery("SELECT COUNT(*) FROM conversations",null)){rows.moveToFirst();assertEquals(0,rows.getInt(0));}
        }
    }
}
