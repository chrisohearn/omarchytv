package com.omarchytv.home;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.Shadows;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Real Android views, synthetic data, no network, device, launcher services, or app launches. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1920dp-h1080dp-land-television-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeUiTest {
    private ActivityController<UiActivity> controller;
    private Activity activity;
    private HomeV29 home;
    private FrameLayout scene;
    private AppAdapter apps;
    private String firstAppName = "Cinema";
    private int appCount = 12;
    private int viewportWidth = 1920, viewportHeight = 1080;
    private AppAdapter launchedApps;
    private int launchedPosition = -1;
    private final List<String> calls = new ArrayList<>();

    // ShadowActivity stores a manual focus field. Read the actual Android view hierarchy instead.
    public static class UiActivity extends Activity {
        @Override public View getCurrentFocus() { return getWindow().getDecorView().findFocus(); }
    }

    @After public void close() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        Theme.resolve(null, new JSONObject().put("name", "tokyo-night"));
    }

    private JSONObject config(String theme, int columns) throws Exception {
        JSONArray ai = new JSONArray();
        for (String name : new String[]{"Pro", "Studio", "Claude S", "Claude W", "Dev"}) {
            ai.put(new JSONObject().put("name", name).put("used", 52).put("limit", 100));
        }
        return new JSONObject().put("theme", new JSONObject().put("name", theme))
                .put("geometry", new JSONObject().put("appSize", LauncherConfig.APP_SIZES[columns - 4]).put("transparency", 60))
                .put("weather", new JSONObject().put("lat", 0).put("lon", 0).put("place", "Sample City"))
                .put("ai", ai).put("tv", new JSONObject().put("enabled", false));
    }

    private void create(JSONObject json) throws Exception {
        controller = Robolectric.buildActivity(UiActivity.class).setup().visible();
        activity = controller.get();
        Files.write(Weather.cacheFile(activity).toPath(), ("{\"current\":{\"temperature_2m\":18,\"weather_code\":3},"
                + "\"daily\":{\"temperature_2m_min\":[12],\"temperature_2m_max\":[20]}}")
                .getBytes(StandardCharsets.UTF_8));
        HomeV29.Host host = (HomeV29.Host) Proxy.newProxyInstance(HomeV29.Host.class.getClassLoader(),
                new Class[]{HomeV29.Host.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    if (method.getName().equals("sys")) calls.add("sys:" + args[0]);
                    if (method.getName().equals("launchApp") && args[0] instanceof AppAdapter) {
                        launchedApps = (AppAdapter) args[0];
                        launchedPosition = (Integer) args[1];
                    }
                    if (method.getName().equals("wifiName")) return "Ethernet";
                    if (method.getName().equals("sources")) return Collections.emptyList();
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        home = new HomeV29(activity, host);
        scene = new FrameLayout(activity);
        try (java.io.InputStream in = activity.getAssets().open("wallpapers/tokyo-night/01-liftoff-sunset.webp")) {
            scene.setBackground(Drawable.createFromStream(in, null));
        }
        scene.addView(home, new FrameLayout.LayoutParams(-1, -1));
        activity.setContentView(scene);
        apps = new AppAdapter(activity, null, 6) {
            private final String[] names = {firstAppName, "Music", "Photos", "Browser", "Radio", "Library", "Streams", "Gallery", "Podcasts", "Games", "Search", "Downloads"};
            @Override public int getItemCount() { return appCount; }
            @Override String labelAt(int pos) { return names[pos % names.length]; }
            @Override Drawable bannerAt(int pos) { return banner(labelAt(pos), pos); }
            @Override AppAdapter fullCatalog() { return this; }
        };
        home.bind(new LauncherConfig(activity, json, 1), apps);
        layout();
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        settle();
        home.focusApp(0);
        settle();
        assertSame("initial app focus", home.row, activity.getCurrentFocus().getParent());
    }

    private Drawable banner(String name, int position) {
        return new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas canvas) {
                Rect b = getBounds();
                paint.setColor(new int[]{0xFF171C30, 0xFF173C37, 0xFF472D40}[position % 3]);
                canvas.drawRect(b, paint);
                paint.setColor(Color.WHITE);
                paint.setTextSize(30);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(name, b.centerX(), b.centerY() + 10, paint);
            }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        };
    }

    @Test @Config(qualifiers = "w3840dp-h2160dp-land-television-mdpi")
    public void t3WidgetShowsActiveThreadsAndHasRemoteDetailsWithoutControls() throws Exception {
        appCount = 6;
        viewportWidth = 3840; viewportHeight = 2160;
        create(config("tokyo-night", 6));
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 5; i++) rows.put(new JSONObject().put("id", "t3-code:" + i).put("agent", "t3-code")
                .put("title", "Task " + i).put("project", "Sample project").put("status", i == 0 ? "waiting" : "running")
                .put("canAsk", false));
        rows.put(new JSONObject().put("id", "other").put("agent", "codex").put("title", "Other provider"));
        rows.put(new JSONObject().put("id", "old").put("agent", "t3-code").put("title", "Completed task").put("status", "done"));
        JSONObject data = new JSONObject().put("enabled", true).put("rx", System.currentTimeMillis()).put("agents", rows);
        home.updateT3(data, true);
        settle();
        View pane = home.panelView("t3");
        assertTrue(pane.isShown());
        assertTrue(texts(pane).stream().anyMatch(v -> v.getText().toString().equals("Task 0")));
        assertFalse(texts(pane).stream().anyMatch(v -> v.getText().toString().equals("Task 4")));
        assertFalse(texts(pane).stream().anyMatch(v -> v.getText().toString().equals("Other provider")));
        assertFalse(texts(pane).stream().anyMatch(v -> v.getText().toString().equals("Completed task")));
        assertTrue(pane.getBottom() + pane.getTranslationY() < home.row.getTop() + ((View) home.row.getParent()).getTop());
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertSame(pane, activity.getCurrentFocus());
        pane.performClick();
        assertTrue(calls.contains("sys:t3-code"));
        capture("four-k-t3-active-threads");
        for (int columns = 4; columns <= 7; columns++) {
            home.bind(new LauncherConfig(activity, config("tokyo-night", columns), 1), apps);
            home.updateT3(data, true); settle();
            assertTrue("T3 visible at " + columns + " columns", pane.isShown());
            View appWindow = (View) home.row.getParent();
            assertTrue("T3 clears app window at " + columns + " columns", pane.getY() + pane.getHeight() <= appWindow.getY());
        }
        home.updateT3(data, false); settle();
        assertTrue(texts(pane).stream().anyMatch(v -> v.getText().toString().equals(activity.getString(R.string.link_offline))));
        assertFalse(texts(pane).stream().anyMatch(v -> v.getText().toString().equals("Task 0")));
        data.put("rx", 1);
        home.updateT3(data, true); settle();
        assertTrue(texts(pane).stream().anyMatch(v -> v.getText().toString().equals(activity.getString(R.string.t3_unavailable))));
        home.bind(new LauncherConfig(activity, config("tokyo-night", 6).put("homeWidgets", new JSONObject().put("t3", false)), 1), apps);
        home.updateT3(data, true); settle();
        assertFalse(pane.isShown());
        ActivityController<AgentActivity> details = Robolectric.buildActivity(AgentActivity.class,
                new Intent(activity, AgentActivity.class).putExtra(AgentActivity.EXTRA_T3, true)).setup();
        try {
            assertEquals(View.GONE, details.get().findViewById(R.id.agentInput).getVisibility());
            assertEquals(View.GONE, details.get().findViewById(R.id.agentMic).getVisibility());
            TextView hint = details.get().findViewById(R.id.agentHint);
            assertEquals(details.get().getString(R.string.t3_detail_hint), hint.getText().toString());
            assertTrue(hint.getTextSize() >= 48);
        } finally { details.pause().stop().destroy(); }
    }

    private void layout() {
        scene.measure(View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY));
        scene.layout(0, 0, viewportWidth, viewportHeight);
    }

    private void settle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        layout();
    }

    private void key(int code) {
        KeyEvent e = new KeyEvent(KeyEvent.ACTION_DOWN, code);
        if (!home.onKey(e)) activity.dispatchKeyEvent(e);
        settle();
    }

    private List<TextView> texts(View view) {
        List<TextView> result = new ArrayList<>();
        if (view instanceof TextView && view.isShown()) result.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(texts(group.getChildAt(i)));
        }
        return result;
    }

    private TextView text(String value) {
        for (TextView t : texts(home)) if (t.getText().toString().equals(value)) return t;
        throw new AssertionError("Missing visible text: " + value);
    }

    private void capture(String name) throws Exception {
        File directory = new File("build/ui-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888);
        scene.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        bitmap.recycle();
    }

    @Test @Config(qualifiers = "w3840dp-h2160dp-land-television-mdpi")
    public void weatherArtworkTracksConditionsAndKeepsLongPlacesBounded() throws Exception {
        appCount = 6;
        viewportWidth = 3840; viewportHeight = 2160;
        String place = "A very long weather location that must stay inside its card";
        JSONObject json = config("tokyo-night", 6).put("tempUnit", "F");
        json.getJSONObject("weather").put("place", place);
        create(json);
        TextView temperature = text("64°F"), location = text(place);
        ViewGroup hero = (ViewGroup) temperature.getParent().getParent();
        ImageView icon = (ImageView) hero.getChildAt(1);
        assertEquals(1, location.getLineCount());
        assertEquals(android.text.TextUtils.TruncateAt.END, location.getEllipsize());
        assertFalse(icon.isFocusable());
        assertTrue(icon.getWidth() >= 240 && icon.getHeight() >= 200);
        int[] tempAt = new int[2], iconAt = new int[2];
        temperature.getLocationOnScreen(tempAt);
        icon.getLocationOnScreen(iconAt);
        assertTrue(iconAt[0] >= tempAt[0] + temperature.getWidth() + 40);
        JSONObject weather = Weather.cached(activity);
        for (int code : new int[]{3, 61, 71, 95, 0}) {
            weather.getJSONObject("current").put("weather_code", code);
            Files.write(Weather.cacheFile(activity).toPath(), weather.toString().getBytes(StandardCharsets.UTF_8));
            home.refresh(); settle();
            assertTrue(icon.isShown());
            assertEquals(Weather.icon(code), Shadows.shadowOf(icon.getDrawable()).getCreatedFromResId());
            assertNotNull(text(activity.getString(Weather.describe(code))));
            assertNotNull(text("min 54° · max 68°"));
            if (code == 3) capture("four-k-weather-conditions");
        }
        home.bind(new LauncherConfig(activity, json.put("tempUnit", "C"), 2), apps);
        settle();
        assertNotNull(text("18°"));
        Files.delete(Weather.cacheFile(activity).toPath());
        home.refresh(); settle();
        assertFalse(icon.isShown());
        assertNull(icon.getDrawable());
        assertNotNull(text("--°"));
        assertNotNull(text(activity.getString(R.string.weather_loading)));
        capture("four-k-weather-loading");
    }

    @Test public void remoteSkipsInformationAndReturnsToTheSelectedApp() throws Exception {
        create(config("tokyo-night", 6));
        assertFalse(home.panelView("pc").isFocusable());
        assertFalse(home.panelView("deploys").isFocusable());
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        View selected = activity.getCurrentFocus();
        assertEquals(1, home.lastApp);
        assertEquals("Music", selected.getContentDescription());
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertSame(home.panelView("weather"), activity.getCurrentFocus());
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertSame(home.panelView("ai"), activity.getCurrentFocus());
        activity.getCurrentFocus().performClick();
        assertTrue(calls.contains("aiMenu"));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertSame(selected, activity.getCurrentFocus());
        selected.performClick();
        selected.performLongClick();
        assertTrue(calls.contains("launchApp"));
        assertTrue(calls.contains("tileMenu"));
        key(KeyEvent.KEYCODE_DPAD_UP);
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("1", ((TextView) activity.getCurrentFocus()).getText().toString());
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertSame(home.panelView("weather"), activity.getCurrentFocus());
        capture("dark-widget-focus");
    }

    @Test public void hiddenWidgetsDoNotTrapFocusAndPanelEditingRestoresReadOnlyState() throws Exception {
        JSONObject json = config("tokyo-night", 6).put("homeWidgets",
                new JSONObject().put("weather", false).put("ai", false));
        create(json);
        View selected = activity.getCurrentFocus();
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals("1", ((TextView) activity.getCurrentFocus()).getText().toString());
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertSame(selected, activity.getCurrentFocus());
        assertTrue(home.editPanel("pc"));
        key(KeyEvent.KEYCODE_BACK);
        assertFalse(home.panelView("pc").isFocusable());
    }

    @Test public void everyAppSizeKeepsTheFocusedTileInsideTheWindow() throws Exception {
        create(config("tokyo-night", 4));
        for (int cols = 4; cols <= 7; cols++) {
            home.bind(new LauncherConfig(activity, config("tokyo-night", cols), cols),
                    apps);
            settle();
            int[] window = new int[2];
            ((View) home.row.getParent()).getLocationOnScreen(window);
            for (String id : new String[]{"term", "weather", "pc", "ai"}) {
                View panel = home.panelView(id);
                int[] at = new int[2];
                panel.getLocationOnScreen(at);
                assertTrue(id + " clears " + cols + " app columns", at[1] + panel.getHeight() <= window[1]);
            }
            for (int pos : new int[]{0, cols - 1, 11}) {
                home.focusApp(pos);
                settle();
                View tile = activity.getCurrentFocus();
                assertSame(home.row, tile.getParent());
                int[] at = new int[2];
                tile.getLocationOnScreen(at);
                float left = at[0] - tile.getWidth() * (tile.getScaleX() - 1) / 2;
                assertTrue("focus ring left " + cols, left >= 32);
                assertTrue("focus ring right " + cols, left + tile.getWidth() * tile.getScaleX() <= 1888);
            }
        }
        home.bind(new LauncherConfig(activity, config("tokyo-night", 6), 20), apps);
        settle();
        home.focusApp(0);
        settle();
        capture("dark-app-focus");
    }

    @Test public void twoRowsUseTheHomeWindowAndKeepSpatialRemoteNavigation() throws Exception {
        create(config("tokyo-night", 6));
        View first = home.row.findViewHolderForAdapterPosition(0).itemView;
        View secondRow = home.row.findViewHolderForAdapterPosition(6).itemView;
        assertTrue(secondRow.getTop() > first.getBottom());
        assertTrue(secondRow.getBottom() <= home.row.getHeight());
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(6, home.lastApp);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(7, home.lastApp);
        home.focusApp(11);
        settle();
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(11, home.lastApp);
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(5, home.lastApp);
        home.focusApp(5);
        settle();
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(5, home.lastApp);
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertSame(home.panelView("ai"), activity.getCurrentFocus());
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(5, home.lastApp);
        JSONObject withTv = config("tokyo-night", 6).put("tv", new JSONObject().put("enabled", true));
        home.bind(new LauncherConfig(activity, withTv, 30), apps);
        settle();
        home.focusApp(0);
        settle();
        View tv = activity.getCurrentFocus();
        tv.performClick();
        tv.performLongClick();
        assertTrue(calls.contains("openTv"));
        assertTrue(calls.contains("openWalker"));
    }

    @Test public void enlargedPanelsRevalidateOldOffsetsWithoutChangingSavedConfiguration() throws Exception {
        JSONObject json = config("tokyo-night", 4).put("panelPos", new JSONObject()
                .put("weather", new JSONArray().put(0).put(5)));
        create(json);
        View weather = home.panelView("weather");
        int[] location = new int[2], appsLocation = new int[2];
        weather.getLocationOnScreen(location);
        ((View) home.row.getParent()).getLocationOnScreen(appsLocation);
        assertTrue(location[1] >= home.barHeight());
        assertTrue(location[1] + weather.getHeight() <= appsLocation[1]);
        assertTrue(location[0] >= 0 && location[0] + weather.getWidth() <= 1920);
        assertEquals(5, json.getJSONObject("panelPos").getJSONArray("weather").getInt(1));
        assertFalse(calls.contains("savePanelPos"));
    }

    @Test public void darkTextTilesUseTheirOwnBackgroundWithPartialCustomPalettes() throws Exception {
        JSONObject json = config("tokyo-night", 6).put("textTiles", true);
        json.getJSONObject("theme").put("colors", new JSONObject()
                .put("background", "#f0f0f0").put("foreground", "#eeeeee").put("accent", "#dddddd"));
        create(json);
        TextView label = text("Cinema");
        View tile = (View) label.getParent();
        Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        scene.draw(new Canvas(bitmap));
        int[] location = new int[2];
        tile.getLocationOnScreen(location);
        int background = bitmap.getPixel(location[0] + 20, location[1] + 20);
        assertTrue(Surfaces.luminance(background) < 0.08);
        assertTrue(Surfaces.contrast(Surfaces.luminance(label.getCurrentTextColor()), Surfaces.luminance(background)) >= 4.5);
        bitmap.recycle();
    }

    @Test public void customPaletteAndSavedPositionsKeepReadableActionableWidgets() throws Exception {
        firstAppName = "A very long application title that must never cover the remote instructions";
        JSONObject json = config("tokyo-night", 6);
        json.getJSONObject("theme").put("colors", new JSONObject()
                .put("background", "#f0f0f0").put("foreground", "#eeeeee").put("accent", "#dddddd"));
        json.put("panelPos", new JSONObject().put("ai", new JSONArray().put(0).put(1))
                .put("pc", new JSONArray().put(0).put(1)));
        create(json);
        assertEquals(0f, home.panelView("ai").getTranslationX(), 0.1f);
        assertEquals(135f, home.panelView("ai").getTranslationY(), 0.1f);
        assertEquals(firstAppName, activity.getCurrentFocus().getContentDescription());
        key(KeyEvent.KEYCODE_DPAD_UP);
        assertSame(home.panelView("weather"), activity.getCurrentFocus());
        Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        scene.draw(new Canvas(bitmap));
        for (String id : new String[]{"weather", "pc", "ai"}) {
            View panel = home.panelView(id);
            int[] location = new int[2];
            panel.getLocationOnScreen(location);
            int background = bitmap.getPixel(location[0] + 10, location[1] + panel.getHeight() - 10);
            assertTrue("dark " + id, Surfaces.luminance(background) < 0.08);
            for (TextView text : texts(panel)) {
                assertTrue(id + " text " + text.getText() + " color=" + Integer.toHexString(text.getCurrentTextColor())
                        + " background=" + Integer.toHexString(background), Surfaces.contrast(
                        Surfaces.luminance(text.getCurrentTextColor()), Surfaces.luminance(background)) >= 4.5);
            }
        }
        bitmap.recycle();
    }

    @Test public void lightThemeAccentsKeepDarkPanelsAndHeadersNeverOverlap() throws Exception {
        create(config("catppuccin-latte", 6));
        View weather = home.panelView("weather"), pc = home.panelView("pc"), ai = home.panelView("ai");
        assertEquals(weather.getWidth(), pc.getWidth());
        assertEquals(weather.getWidth(), ai.getWidth());
        int[] wxAt = new int[2], pcAt = new int[2], aiAt = new int[2];
        weather.getLocationOnScreen(wxAt);
        pc.getLocationOnScreen(pcAt);
        ai.getLocationOnScreen(aiAt);
        assertEquals("AI and PC align", aiAt[0], pcAt[0]);
        assertEquals("top cards align", wxAt[1], aiAt[1]);
        assertTrue("PC sits below AI", pcAt[1] >= aiAt[1] + ai.getHeight() + 16);
        assertTrue("PC fits its content", pc.getHeight() < weather.getHeight());
        for (View panel : new View[]{weather, pc, ai}) {
            for (TextView t : texts(panel)) assertTrue(t.getTextSize() >= 24);
        }
        assertFalse(texts(home).stream().anyMatch(v -> v.getText().toString().contains("1/12")));
        assertTrue(ai.getBottom() < home.row.getTop() + ((View) home.row.getParent()).getTop());
        capture("light-theme-dark-panels");
    }

    @Test public void sixAppsUseOneRowAndOutsidePcOffsetFallsBackToTheAlignedDeck() throws Exception {
        appCount = 6;
        JSONObject json = config("tokyo-night", 6).put("panelPos", new JSONObject()
                .put("pc", new JSONArray().put(-12).put(2)));
        create(json);
        assertTrue("one app row", home.row.getHeight() < 300);
        assertEquals(0f, home.panelView("pc").getTranslationX(), 0.1f);
        assertEquals(0f, home.panelView("pc").getTranslationY(), 0.1f);
        assertEquals(-12, json.getJSONObject("panelPos").getJSONArray("pc").getInt(0));
        key(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals("no second row", 0, home.lastApp);
        capture("six-app-home");
    }

    @Test public void scrollingNeverPaintsAppsOverTheHeaderOrWidgets() throws Exception {
        appCount = 24;
        create(config("tokyo-night", 6));
        int[] at = new int[2];
        home.row.getLocationOnScreen(at);
        Bitmap before = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        scene.draw(new Canvas(before));
        home.focusApp(12);
        settle();
        Bitmap after = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        scene.draw(new Canvas(after));
        for (int y = at[1] - 120; y < at[1] - 50; y++) {
            for (int x = at[0] + 40; x < at[0] + 200; x++) {
                assertEquals("app content escaped above the viewport", before.getPixel(x, y), after.getPixel(x, y));
            }
        }
        assertEquals(12, home.lastApp);
        before.recycle();
        after.recycle();
        capture("bounded-app-scroll");
    }

    @Test public void completeSavedArrangementIsRestoredBeforeCollisionChecks() throws Exception {
        appCount = 6;
        JSONObject json = config("tokyo-night", 6).put("panelPos", new JSONObject()
                .put("weather", new JSONArray().put(3).put(0))
                .put("ai", new JSONArray().put(0).put(2))
                .put("pc", new JSONArray().put(-7).put(1)));
        create(json);
        assertEquals(480f, home.panelView("weather").getTranslationX(), 0.1f);
        assertEquals(0f, home.panelView("weather").getTranslationY(), 0.1f);
        assertEquals(270f, home.panelView("ai").getTranslationY(), 0.1f);
        assertEquals(-1120f, home.panelView("pc").getTranslationX(), 0.1f);
        assertEquals(135f, home.panelView("pc").getTranslationY(), 0.1f);
        assertFalse(calls.contains("savePanelPos"));
    }

    @Test @Config(qualifiers = "w3840dp-h2160dp-land-television-mdpi")
    public void fourKHomeUsesUncaptionedArtworkWithoutANameCounterHeader() throws Exception {
        appCount = 6;
        viewportWidth = 3840;
        viewportHeight = 2160;
        create(config("tokyo-night", 6).put("workspaces", new JSONObject().put("order", new JSONArray()
                .put("home").put("apps").put("pc").put("system"))));
        View tile = activity.getCurrentFocus();
        assertEquals(tile.getWidth() * 9 / 16, tile.getHeight());
        assertTrue("no words below banner", texts(tile).isEmpty());
        assertEquals("Cinema", tile.getContentDescription());
        assertFalse(texts(home).stream().anyMatch(v -> v.getText().toString().contains("1/6")));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(1, home.lastApp);
        assertEquals("Music", activity.getCurrentFocus().getContentDescription());
        capture("four-k-six-app-home");
    }

    @Test public void appsDesktopIncludesHomeHiddenAppsAndLaunchesFromTheCatalog() throws Exception {
        JSONObject json = config("tokyo-night", 6)
                .put("webapps", new JSONArray())
                .put("dock", new JSONArray().put("sample.visible"))
                .put("hidden", new JSONArray().put("sample.hidden"))
                .put("workspaces", new JSONObject().put("order", new JSONArray()
                        .put("home").put("apps").put("pc").put("system")));
        create(json);
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (String pkg : new String[]{"sample.visible", "sample.hidden"}) {
            ResolveInfo info = new ResolveInfo();
            info.activityInfo = new ActivityInfo();
            info.activityInfo.packageName = pkg;
            info.activityInfo.name = pkg + ".Main";
            info.activityInfo.applicationInfo = new ApplicationInfo();
            info.activityInfo.applicationInfo.packageName = pkg;
            info.nonLocalizedLabel = pkg;
            Shadows.shadowOf(activity.getPackageManager()).addResolveInfoForIntent(probe, info);
        }
        Files.write(LauncherConfig.file(activity).toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        AppAdapter discovered = new AppAdapter(activity, null, 6);
        discovered.reload();
        assertEquals(1, discovered.getItemCount());
        LauncherConfig cfg = LauncherConfig.get(activity);
        home.bind(cfg, discovered);
        settle();
        assertEquals(1, home.row.getAdapter().getItemCount());
        home.setWorkspace(cfg.workspaceAt(2));
        settle();
        assertEquals(2, home.grid.getAdapter().getItemCount());
        assertFalse(cfg.workspaceShown(2));
        home.focusGrid(1);
        settle();
        activity.getCurrentFocus().performClick();
        assertNotNull(launchedApps);
        assertEquals("sample.hidden", launchedApps.packageAt(launchedPosition));
        assertTrue(LauncherConfig.get(activity).isHidden("sample.hidden"));
        capture("full-apps-desktop");
    }
}
