package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1920dp-h1080dp-land-television-mdpi")
public class CinemarchyNavigationTest {
    @Test
    public void vimKeysCrossVirtualWindowAndReachBothEnds() {
        try (ActivityController<CinemarchyActivity> controller = openList()) {
            CinemarchyActivity activity = controller.get();
            for (int i = 0; i < 60; i++) key(activity, KeyEvent.KEYCODE_J);
            assertSelection(activity, 61);
            key(activity, KeyEvent.KEYCODE_K);
            assertSelection(activity, 60);

            key(activity, KeyEvent.KEYCODE_G);
            assertTitle(activity, "@dhh37 · 100 from the feed");
            activity.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_G, 0, KeyEvent.META_SHIFT_ON));
            assertTitle(activity, "Omarchy Manual (omarchy.org)");
            key(activity, KeyEvent.KEYCODE_G);
            assertTitle(activity, "@dhh37 · 100 from the feed");
        }
    }

    @Test
    public void pagesAndDpadStillCrossVirtualWindow() {
        try (ActivityController<CinemarchyActivity> controller = openList()) {
            CinemarchyActivity activity = controller.get();
            key(activity, KeyEvent.KEYCODE_PAGE_DOWN);
            assertSelection(activity, 21);
            key(activity, KeyEvent.KEYCODE_PAGE_DOWN);
            assertSelection(activity, 41);
            LinearLayout list = ReflectionHelpers.getField(activity, "listContainer");
            assertTrue(list.getChildAt(59).requestFocus());
            key(activity, KeyEvent.KEYCODE_DPAD_DOWN);
            assertSelection(activity, 61);
            key(activity, KeyEvent.KEYCODE_PAGE_UP);
            assertSelection(activity, 41);
            assertTrue(list.getChildAt(0).requestFocus());
            key(activity, KeyEvent.KEYCODE_DPAD_UP);
            assertSelection(activity, 30);
        }
    }

    private static ActivityController<CinemarchyActivity> openList() {
        List<DhhFeed.Video> videos = new ArrayList<>();
        for (int row = 2; row <= 101; row++) {
            videos.add(new DhhFeed.Video("test" + row, "Video " + row, "", "", 0));
        }
        ActivityController<CinemarchyActivity> controller = Robolectric.buildActivity(CinemarchyActivity.class);
        ReflectionHelpers.setField(controller.get(), "dhhFeed", videos);
        // Stop at creation so onResume does not fetch the YouTube feed.
        controller.create().visible();
        assertTitle(controller.get(), "@dhh37 · 100 from the feed");
        return controller;
    }

    private static void key(CinemarchyActivity activity, int code) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private static void assertSelection(CinemarchyActivity activity, int item) {
        assertTitle(activity, "Video " + item);
    }

    private static void assertTitle(CinemarchyActivity activity, String expectedTitle) {
        LinearLayout list = ReflectionHelpers.getField(activity, "listContainer");
        TextView title = ReflectionHelpers.getField(activity, "detailTitle");
        View focused = list.findFocus();
        assertTrue("a list row should have focus", focused != null && focused.getParent() == list);
        assertEquals(expectedTitle, title.getText().toString());
        assertEquals("the rendered list stays bounded", 60, list.getChildCount());
    }
}
