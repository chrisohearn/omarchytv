package com.omarchytv.home;

import static org.junit.Assert.*;

import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Boot start of Omarchy Link as a foreground service. No network, device or launcher UI. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LinkBootTest {
    private static long stamp = 1_000_000L;

    private static void writeConfig(Context c, String json) throws Exception {
        File f = LauncherConfig.file(c);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        f.setLastModified(stamp += 5_000L);   // LauncherConfig caches by modification time
    }

    /** Records which start call the receiver used; nothing is actually started. */
    private static final class Recorder extends ContextWrapper {
        final List<Intent> foreground = new ArrayList<>(), plain = new ArrayList<>();

        Recorder(Context base) {
            super(base);
        }

        @Override
        public android.content.ComponentName startForegroundService(Intent i) {
            foreground.add(i);
            return i.getComponent();
        }

        @Override
        public android.content.ComponentName startService(Intent i) {
            plain.add(i);
            return i.getComponent();
        }

        @Override
        public Context getApplicationContext() {
            return getBaseContext().getApplicationContext();
        }
    }

    private static Intent bootIntent() {
        return new Intent(Intent.ACTION_BOOT_COMPLETED);
    }

    @Test
    public void bootStartsKeepAliveAsForegroundServiceWhenLinkEnabled() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        writeConfig(app, "{}");
        Recorder c = new Recorder(app);
        new HomeCheck().onReceive(c, bootIntent());
        assertEquals(1, c.foreground.size());
        assertEquals(OmarchyLink.KeepAlive.class.getName(), c.foreground.get(0).getComponent().getClassName());
        assertTrue("no plain start at API 26+", c.plain.isEmpty());
    }

    @Test
    public void packageReplacedStartsKeepAliveAgain() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        writeConfig(app, "{}");
        Recorder c = new Recorder(app);
        new HomeCheck().onReceive(c, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertEquals(1, c.foreground.size());
        assertEquals(OmarchyLink.KeepAlive.class.getName(), c.foreground.get(0).getComponent().getClassName());
    }

    @Test
    public void bootStartsNothingWhenLinkDisabled() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        writeConfig(app, "{\"link\":{\"enabled\":false}}");
        Recorder c = new Recorder(app);
        new HomeCheck().onReceive(c, bootIntent());
        assertTrue(c.foreground.isEmpty());
        assertTrue(c.plain.isEmpty());
    }

    @Test
    public void stickyRestartWithNullIntentStillCallsStartForeground() throws Exception {
        writeConfig(RuntimeEnvironment.getApplication(), "{}");
        OmarchyLink.KeepAlive service = Robolectric.buildService(OmarchyLink.KeepAlive.class).create().get();
        int result = service.onStartCommand(null, 0, 1);
        assertEquals(Service.START_STICKY, result);
        assertNotNull(Shadows.shadowOf(service).getLastForegroundNotification());
        assertFalse(Shadows.shadowOf(service).isForegroundStopped());
    }

    @Test
    public void disabledLinkStopsForegroundAndIsNotSticky() throws Exception {
        writeConfig(RuntimeEnvironment.getApplication(), "{\"link\":{\"enabled\":false}}");
        OmarchyLink.KeepAlive service = Robolectric.buildService(OmarchyLink.KeepAlive.class).create().get();
        int result = service.onStartCommand(null, 0, 1);
        assertEquals(Service.START_NOT_STICKY, result);
        // startForeground was called (the id is kept), then the notification was removed again
        assertEquals(LinkBoot.NOTIFICATION_ID, Shadows.shadowOf(service).getLastForegroundNotificationId());
        assertTrue(Shadows.shadowOf(service).isForegroundStopped());
    }
}
