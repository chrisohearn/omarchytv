package com.omarchytv.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1920dp-h1080dp-land-television-mdpi")
public class CinemarchyMediaListTest {
    @Test
    public void onlyCurrentFolderErrorsReplaceItsListing() throws Exception {
        // Create the activity without resuming it or connecting to a PC.
        try (ActivityController<CinemarchyActivity> controller =
                     Robolectric.buildActivity(CinemarchyActivity.class).create().visible()) {
            CinemarchyActivity activity = controller.get();
            ReflectionHelpers.setField(activity, "pcPath", "Video/Current");
            activity.onLinkEvent("media_list", new JSONObject().put("path", "Video/Current")
                    .put("files", new JSONArray().put(new JSONObject().put("name", "Current movie")
                            .put("path", "Video/Current/movie.mkv").put("kind", "video"))));
            assertTrue(hasEntry(activity, "Current movie"));

            activity.onLinkEvent("media_list", error("Video/Previous", "stale folder"));
            assertTrue(hasEntry(activity, "Current movie"));
            assertFalse(hasEntry(activity, "stale folder"));

            activity.onLinkEvent("media_list", error("Video/Current/gone.mkv", "stale play"));
            assertTrue(hasEntry(activity, "Current movie"));
            assertFalse(hasEntry(activity, "stale play"));

            activity.onLinkEvent("media_list", error("Video/Current", "unreadable"));
            assertTrue(hasEntry(activity, "unreadable"));
            assertFalse(hasEntry(activity, "Current movie"));

            // An older Link reports its global opt-out with the root path.
            activity.onLinkEvent("media_list", error("", "disabled"));
            assertTrue(hasEntry(activity, "disabled"));
        }
    }

    private static JSONObject error(String path, String message) throws Exception {
        return new JSONObject().put("path", path).put("error", message);
    }

    private static boolean hasEntry(CinemarchyActivity activity, String title) {
        List<CinemarchyActivity.MediaEntry> entries = ReflectionHelpers.callInstanceMethod(activity, "pcEntries");
        return entries.stream().anyMatch(entry -> entry.title.equals(title));
    }
}
