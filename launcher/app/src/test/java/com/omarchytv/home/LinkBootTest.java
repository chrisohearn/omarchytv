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

/** Boot / update start of Omarchy Link as a foreground service, only with a paired PC. No network, device or launcher UI. */
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

    private static void writeLink(Context c, boolean paired) throws Exception {
        String pcs = paired ? "{\"pc1\":{\"name\":\"Omarchy\",\"token\":\"AAAA\",\"pairedAt\":1}}" : "{}";
        Files.write(new File(c.getFilesDir(), "link.json").toPath(),
                ("{\"tvId\":\"00000000000000000000000000000000\",\"pcs\":" + pcs + "}").getBytes(StandardCharsets.UTF_8));
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

        final List<Intent> stopped = new ArrayList<>();

        @Override
        public boolean stopService(Intent i) {
            stopped.add(i);
            return true;
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
        writeLink(app, true);
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
        writeLink(app, true);
        Recorder c = new Recorder(app);
        new HomeCheck().onReceive(c, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertEquals(1, c.foreground.size());
        assertEquals(OmarchyLink.KeepAlive.class.getName(), c.foreground.get(0).getComponent().getClassName());
    }

    @Test
    public void bootStartsNothingWithoutAPairedPc() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        writeConfig(app, "{}");
        writeLink(app, false);
        Recorder c = new Recorder(app);
        new HomeCheck().onReceive(c, bootIntent());
        assertTrue("TVs that never paired keep no resident service", c.foreground.isEmpty());
        assertTrue(c.plain.isEmpty());
    }

    @Test
    public void bootStartsNothingWhenLinkDisabled() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        writeConfig(app, "{\"link\":{\"enabled\":false}}");
        writeLink(app, true);
        Recorder c = new Recorder(app);
        new HomeCheck().onReceive(c, bootIntent());
        assertTrue(c.foreground.isEmpty());
        assertTrue(c.plain.isEmpty());
    }

    @Test
    public void launcherStopsKeepAliveWhenLinkWasSwitchedOff() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        writeConfig(app, "{\"link\":{\"enabled\":false}}");
        writeLink(app, true);
        Recorder c = new Recorder(app);
        OmarchyLink.start(c);
        assertTrue(c.foreground.isEmpty());
        assertEquals(1, c.stopped.size());
        assertEquals(OmarchyLink.KeepAlive.class.getName(), c.stopped.get(0).getComponent().getClassName());
    }

    @Test
    public void stickyRestartWithNullIntentStillCallsStartForeground() throws Exception {
        writeConfig(RuntimeEnvironment.getApplication(), "{}");
        writeLink(RuntimeEnvironment.getApplication(), true);
        OmarchyLink.KeepAlive service = Robolectric.buildService(OmarchyLink.KeepAlive.class).create().get();
        int result = service.onStartCommand(null, 0, 1);
        assertEquals(Service.START_STICKY, result);
        assertNotNull(Shadows.shadowOf(service).getLastForegroundNotification());
        assertFalse(Shadows.shadowOf(service).isForegroundStopped());
    }

    @Test
    public void disabledLinkStopsForegroundAndIsNotSticky() throws Exception {
        writeConfig(RuntimeEnvironment.getApplication(), "{\"link\":{\"enabled\":false}}");
        writeLink(RuntimeEnvironment.getApplication(), true);
        OmarchyLink.KeepAlive service = Robolectric.buildService(OmarchyLink.KeepAlive.class).create().get();
        int result = service.onStartCommand(null, 0, 1);
        assertEquals(Service.START_NOT_STICKY, result);
        // startForeground was called (the id is kept), then the notification was removed again
        assertEquals(LinkBoot.NOTIFICATION_ID, Shadows.shadowOf(service).getLastForegroundNotificationId());
        assertTrue(Shadows.shadowOf(service).isForegroundStopped());
    }

    @Test
    public void unpairedKeepAliveStopsForegroundAndIsNotSticky() throws Exception {
        writeConfig(RuntimeEnvironment.getApplication(), "{}");
        writeLink(RuntimeEnvironment.getApplication(), false);
        OmarchyLink.KeepAlive service = Robolectric.buildService(OmarchyLink.KeepAlive.class).create().get();
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1));
        assertTrue(Shadows.shadowOf(service).isForegroundStopped());
    }
}
