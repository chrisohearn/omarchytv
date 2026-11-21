package com.omarchytv.home;

import static org.junit.Assert.assertEquals;

import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = CastPlayerLifecycleTest.ShadowLink.class)
public class CastPlayerLifecycleTest {
    @Implements(OmarchyLink.class)
    public static class ShadowLink {
        static final List<String> events = new ArrayList<>();

        @Implementation
        protected static void castState(String id, String state, double pos, double dur, String error) {
            events.add(state + " " + id);
        }

        // Record calls to the old API when running this regression against the affected launcher.
        @Implementation
        protected static void screenMirror(boolean start) {
            events.add(start ? "mirror start" : "unscoped mirror stop");
        }
    }

    @Before
    public void clearEvents() {
        ShadowLink.events.clear();
    }

    private ActivityController<CastPlayerActivity> liveSurface(String id) {
        // Omitting a media URL lets us exercise the actual activity lifecycle without opening a player.
        Intent intent = new Intent().putExtra(CastRouter.EXTRA_ID, id).putExtra(CastRouter.EXTRA_LIVE, true);
        return Robolectric.buildActivity(CastPlayerActivity.class, intent).create();
    }

    @Test
    public void delayedDestroyAfterBackReportsOnlyTheOwnedCast() {
        ActivityController<CastPlayerActivity> a = liveSurface("A");
        a.get().onBackPressed();
        ActivityController<CastPlayerActivity> b = liveSurface("B");
        a.destroy();
        assertEquals(List.of("stopped A"), ShadowLink.events);
        b.get().onBackPressed();
        b.destroy();
        assertEquals(List.of("stopped A", "stopped B"), ShadowLink.events);
    }

    @Test
    public void replacementLeavesTheServerToReportTheOldCast() {
        ActivityController<CastPlayerActivity> a = liveSurface("A");
        a.get().castReplaced();
        a.destroy();
        assertEquals(List.of(), ShadowLink.events);
    }
}
