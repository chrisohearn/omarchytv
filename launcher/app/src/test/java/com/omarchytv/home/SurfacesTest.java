package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SurfacesTest {

    private static final int ACCENT = 0x7AA2F7;

    private static double on(int text, int behind) {
        return Surfaces.contrast(Surfaces.luminance(text), Surfaces.luminance(behind));
    }

    @Test
    public void textOnOpaqueBgPasses() {
        assertTrue(on(Surfaces.TEXT_DIM, Surfaces.bg()) >= 4.5);
        assertTrue(on(Surfaces.TEXT_FG, Surfaces.bg()) >= 4.5);
    }

    @Test
    public void wallpaperUntouchedByDefault() {
        assertEquals(0, Surfaces.dimLevel("off"));
        assertEquals(0, Surfaces.dimLevel("bogus"));
        assertEquals(0f, Surfaces.globalDim(Surfaces.dimLevel("off")), 0f);
        assertTrue(Surfaces.globalDim(Surfaces.dimLevel("light")) <= 0.10f);
        assertEquals(0.75f, Surfaces.surfaceAlpha(Surfaces.DEFAULT), 1e-6);   // v2.9 spec: default opacity 0.75
    }

    @Test
    public void panesStayReadableOnTheBrightestWallpaper() {
        for (int t : Surfaces.STEPS) {
            float a = Surfaces.plateAlpha(0xFFFFFF, t, Surfaces.TEXT_DIM, Surfaces.TEXT_FG, ACCENT);
            int behind = Surfaces.over(Surfaces.bg(), a, 0xFFFFFF);
            for (int c : new int[]{Surfaces.TEXT_DIM, Surfaces.TEXT_FG, ACCENT}) {
                assertTrue("t=" + t + " c=" + Integer.toHexString(c), on(c, behind) >= Surfaces.MIN_CONTRAST);
            }
        }
    }

    @Test
    public void panesKeepTheUserTransparencyOnDarkWallpaper() {
        for (int t : Surfaces.STEPS) {
            assertEquals(Surfaces.surfaceAlpha(t), Surfaces.plateAlpha(0x1A1B26, t, Surfaces.TEXT_DIM, ACCENT), 1e-6);
        }
    }

    @Test
    public void menusPassOverWhiteBanners() {
        for (int t : Surfaces.STEPS) {
            int behind = Surfaces.over(Surfaces.bg(), (Surfaces.menu(t) >>> 24) / 255f, 0xFFFFFF);
            assertTrue(on(Surfaces.TEXT_DIM, behind) >= 4.5);
        }
    }

    @Test
    public void homeSurfacesProtectLightAndDarkThemesAcrossWallpaperExtremes() throws Exception {
        org.json.JSONObject themes = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/assets/omarchy_themes.json")), java.nio.charset.StandardCharsets.UTF_8))
                .getJSONObject("themes");
        for (java.util.Iterator<String> it = themes.keys(); it.hasNext();) {
            String name = it.next();
            org.json.JSONObject palette = themes.getJSONObject(name);
            int bg = Theme.parse(palette.getString("background"), 0);
            int fg = Theme.parse(palette.getString("foreground"), 0);
            for (int transparency : Surfaces.STEPS) {
                float alpha = Surfaces.wallpaperAlpha(bg, Surfaces.surfaceAlpha(transparency), fg);
                for (int wall : new int[]{0x000000, 0xFFFFFF, 0xFF49A8, 0x888888}) {
                    assertTrue(name + " over " + Integer.toHexString(wall),
                            on(fg, Surfaces.over(bg, alpha, wall)) >= 4.5);
                }
                assertTrue(alpha >= Surfaces.surfaceAlpha(transparency));
            }
        }
        assertEquals(0.95f, Surfaces.wallpaperAlpha(0x000000, 0.95f, 0xFFFFFF), 0.004f);
    }
}
