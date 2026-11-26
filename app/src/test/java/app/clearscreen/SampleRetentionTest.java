package app.clearscreen;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class SampleRetentionTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();

    private Context context(){
        return new ContextWrapper(RuntimeEnvironment.getApplication()){
            @Override public File getNoBackupFilesDir(){return temporary.getRoot();}
        };
    }
    private RuleSample sample(){
        android.view.accessibility.AccessibilityNodeInfo node=RuleWorkbenchTest.node("exit","跳过广告",true);
        return RuleSample.capture(RuleWorkbenchTest.PKG,"Home",node.getWindowId(),Collections.singletonList(node));
    }
    @Test public void importedMissingChildrenCannotBeReportedAsACompleteNegative() throws Exception {
        org.json.JSONObject json=sample().export();
        org.json.JSONObject first=json.getJSONArray("nodes").getJSONObject(0);
        first.getJSONArray("children").put(-1);
        first.put("text","私人正文").put("textKnown",true);
        RuleSample imported=RuleSample.read(json);
        assertFalse(imported.complete);
        assertFalse(imported.items.get(0).knownText);
        assertFalse(imported.export().toString().contains("私人正文"));
    }
    @Test public void cleanupOnlyDeletesExplicitlyExpiredFiles() throws Exception {
        Context context=context();
        File legacy=SampleLibrary.save(context,sample());
        File week=SampleLibrary.save(context,sample(),7);
        File month=SampleLibrary.save(context,sample(),30);
        assertEquals(0,SampleLibrary.expiry(legacy));
        long at=SampleLibrary.expiry(week);
        assertEquals(3,SampleLibrary.inventory(context,at-1).files.size());
        assertEquals(2,SampleLibrary.inventory(context,at).files.size());
        assertFalse(week.exists());
        assertTrue(legacy.exists());
        assertTrue(month.exists());
    }
    @Test public void overQuotaNewSaveFailsWithoutEvictingExistingCollections() throws Exception {
        Context context=context();
        File first=SampleLibrary.save(context,sample());
        try(RandomAccessFile stream=new RandomAccessFile(first,"rw")){stream.setLength(SampleLibrary.MAX_TOTAL_BYTES);}
        assertThrows(IOException.class,()->SampleLibrary.save(context,sample(),7));
        assertEquals(SampleLibrary.MAX_TOTAL_BYTES,first.length());
        try(RandomAccessFile stream=new RandomAccessFile(first,"rw")){stream.setLength(0);}
        for(int i=1;i<SampleLibrary.MAX_FILES;i++)
            Files.createFile(new File(SampleLibrary.folder(context),"legacy-"+i+".json").toPath());
        assertThrows(IOException.class,()->SampleLibrary.save(context,sample(),7));
        assertEquals(SampleLibrary.MAX_FILES,SampleLibrary.inventory(context,System.currentTimeMillis()).files.size());
    }
}
