package com.omarchytv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Waybar, fastfetch and btop-style widgets above app artwork. Geometry scales from
 * 1920×1080 to 4K. The selected theme supplies hues and accents; home surfaces stay dark.
 */
final class HomeV29 extends FrameLayout {

    interface Host {
        void openSettings(View anchor);
        void showWorkspace(int ws);
        void showInputs(View anchor);
        void screenPc(View anchor);
        void openWalker();
        void weatherMenu(View anchor);
        void aiMenu(View anchor);
        void launchApp(AppAdapter source, int position);
        String wifiName();
        // v2.10
        void wifiClick();
        void openBluetooth();
        void openTv();
        void tileMenu(AppAdapter source, int appPos, View anchor);
        void moveApp(int from, int to);
        void finishMove(boolean save);
        List<Source> sources();
        boolean pcPaired();
        void pairPc();
        void sys(String id);
        void savePanelPos(String id, int cx, int cy);
        /** ▶ past the last top-bar icon: select (arm) the floating companion; false when it is not shown. */
        boolean selectCompanion();
    }

    /** v2.10 workspace 2 tile: TV or an HDMI input (CEC name when known). */
    static final class Source {
        final String label, sub;
        final int icon;
        final Runnable open;

        Source(String label, String sub, int icon, Runnable open) {
            this.label = label;
            this.sub = sub;
            this.icon = icon;
            this.open = open;
        }
    }

    private final Activity act;
    private final Host host;
    final float s;
    private final Typeface tf, tfBold;
    private Theme t;
    private LauncherConfig cfg;

    // bar
    private final FrameLayout bar;
    private final TextView clock;
    private final TextView buildStamp;
    private final TextView[] ws = new TextView[5];
    private final View menuBtn;
    private final List<View> barIcons = new ArrayList<>();
    private final TextView tip;
    private int activeWs = 1;
    // terminal
    private final BoxView term;
    private final TextView termInfo, termLogo, termPrompt;
    // widgets
    private final LinearLayout widgets;
    private final BoxView wxBox, pcBox, depBox, t3Box;
    private final LinearLayout pcBody, aiBody, depBody;
    private final LinearLayout t3Body;
    private final int t3RowHeight;
    private com.omarchytv.home.link.Agents t3Agents = new com.omarchytv.home.link.Agents();
    private boolean t3Connected;
    /** Up to 3 panes the TV fetches itself (RemotePanes), in their own row under the widgets. */
    private final LinearLayout remoteRow;
    private final BoxView[] remoteBox = new BoxView[3];
    private final LinearLayout[] remoteBody = new LinearLayout[3];
    /** "barWeather": icon + temperature in the bar, left of the status icons. */
    private final TextView barSpacex;
    private final BoxView grokbotBox;
    private final TextView grokbotEyes, grokbotText;
    private final GrokbotMark grokbotMark;
    private final LinearLayout barWx;
    private final ImageView barWxIcon;
    private final TextView barWxTemp;
    private final TextView wxTemp, wxLine1, wxLine2;
    // apps
    private final BoxView appWin;
    private final TextView appName, appHints;
    private final LinearLayout appHead;
    final RecyclerView row;
    private final TileAdapter rowAdapter;
    private AppAdapter apps;
    private AppAdapter catalog;
    /** Adapter position in the home row (0 = TV tile). */
    int lastApp;
    // v2.10 workspaces 2-5
    private final BoxView tvWin, appsWin, pcWin, sysWin;
    private final LinearLayout tvBody, pcPaneBody, sysBody;
    final RecyclerView grid;
    private final TileAdapter gridAdapter;
    private final TextView gridName, gridHints;
    private final android.util.SparseArray<View> wsFocus = new android.util.SparseArray<>();
    /** Move mode (OK lungo → Sposta): app index being moved, or -1; which list (row / grid). */
    int movingApp = -1;
    private boolean movingInGrid;
    private SpannableStringBuilder promptText;
    private ForegroundColorSpan cursorSpan;
    private String logoFinal = "";
    private final android.os.Handler anim = new android.os.Handler(android.os.Looper.getMainLooper());

    HomeV29(Activity a, Host host) {
        super(a);
        this.act = a;
        this.host = host;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        s = Math.min(dm.heightPixels / 1080f, dm.widthPixels / 1920f);
        tf = CastUi.font(a);
        tfBold = Typeface.create(tf, Typeface.BOLD);
        setClipChildren(false);

        // ---- Waybar: couch-readable targets, square focus marks ----
        bar = new FrameLayout(a);
        addView(bar, new LayoutParams(-1, px(64)));
        LinearLayout left = hbox();
        left.setPadding(px(32), 0, 0, 0);
        bar.addView(left, new LayoutParams(-2, -1, Gravity.START));
        menuBtn = iconButton(R.drawable.ln_menu, 44, 24);
        left.addView(menuBtn);
        menuBtn.setOnClickListener(v -> host.openSettings(v));
        for (int i = 0; i < 5; i++) {
            final int n = i + 1;
            TextView w = text(String.valueOf(n), 24, true);
            w.setGravity(Gravity.CENTER);
            w.setFocusable(true);
            w.setPadding(px(14), 0, px(14), 0);
            w.setOnClickListener(v -> host.showWorkspace(cfg != null ? cfg.workspaceAt(n) : n));   // n = bar position
            w.setBackground(new Underline());
            ws[i] = w;
            left.addView(w, new LinearLayout.LayoutParams(-2, -1));
        }
        buildStamp = text(a.getString(R.string.build_stamp), 16, false);
        buildStamp.setGravity(Gravity.CENTER_VERTICAL);
        buildStamp.setPadding(px(12), 0, 0, 0);
        left.addView(buildStamp, new LinearLayout.LayoutParams(-2, -1));
        clock = text("", 26, true);
        bar.addView(clock, new LayoutParams(-2, -2, Gravity.CENTER));
        LinearLayout right = hbox();
        right.setPadding(0, 0, px(32) - px(13), 0);
        bar.addView(right, new LayoutParams(-2, -1, Gravity.END));
        barSpacex = text("🚀 SpaceX TV", 22, true);
        barSpacex.setFocusable(true);
        barSpacex.setClickable(true);
        barSpacex.setGravity(Gravity.CENTER);   // same row as the icons: centred in a 56 px box like iconButton
        barSpacex.setSingleLine(true);
        barSpacex.setPadding(px(12), 0, px(12), 0);
        barSpacex.setBackground(new Underline());
        barSpacex.setOnClickListener(v -> host.sys("spacex-tv"));
        LinearLayout.LayoutParams sxl = new LinearLayout.LayoutParams(-2, px(56));
        sxl.gravity = Gravity.CENTER_VERTICAL;
        sxl.rightMargin = px(16);
        right.addView(barSpacex, sxl);
        barWx = hbox();
        barWxIcon = new ImageView(a);
        barWx.addView(barWxIcon, new LinearLayout.LayoutParams(px(24), px(24)));
        barWxTemp = text("", 22, false);
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(-2, -2);
        btl.leftMargin = px(8);
        barWx.addView(barWxTemp, btl);
        LinearLayout.LayoutParams bwl = new LinearLayout.LayoutParams(-2, -1);
        bwl.rightMargin = px(20);
        right.addView(barWx, bwl);
        barWx.setVisibility(GONE);
        // v2.10: no gear (the menu is the Omarchy logo on the left).
        int[] icons = {R.drawable.ln_cinemarchy, R.drawable.ln_wifi, R.drawable.ln_bt, R.drawable.ln_vol, R.drawable.ln_input, R.drawable.ln_monitor};
        for (int ic : icons) {
            View b = iconButton(ic, 24 + 26, 24);
            right.addView(b);
            barIcons.add(b);
        }
        barIcons.get(0).setOnClickListener(v -> host.sys("cinemarchy"));
        barIcons.get(1).setOnClickListener(v -> host.wifiClick());
        barIcons.get(2).setOnClickListener(v -> host.openBluetooth());
        barIcons.get(3).setOnClickListener(v -> {
            android.media.AudioManager am = act.getSystemService(android.media.AudioManager.class);
            if (am != null) am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI);
        });
        barIcons.get(4).setOnClickListener(host::showInputs);
        barIcons.get(5).setOnClickListener(host::screenPc);
        tip = text("", 24, false);
        tip.setVisibility(GONE);
        tip.setPadding(px(14), px(8), px(14), px(8));
        tip.setElevation(20f);
        addView(tip, new LayoutParams(-2, -2));
        for (int i = 0; i < barIcons.size(); i++) {
            final int k = i;
            barIcons.get(i).setOnFocusChangeListener((v, f) -> showTip(f ? v : null, k));
        }

        // ---- Fastfetch: keep the wordmark and terminal prompt ----
        term = new BoxView(a, s, tf);
        LayoutParams tl = new LayoutParams(px(640), -2);
        tl.leftMargin = px(32);
        tl.topMargin = px(88);
        addView(term, tl);
        termPrompt = text("", 24, false);
        termLogo = text(logoText(), 8, false);
        termLogo.setLineSpacing(0, 1.15f);
        termLogo.setHorizontallyScrolling(true);
        termInfo = text("", 24, false);
        termInfo.setMaxLines(2);
        termInfo.setLineSpacing(px(4), 1f);
        term.addView(termPrompt);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = px(12);
        ll.bottomMargin = px(12);
        term.addView(termLogo, ll);
        term.addView(termInfo);

        // ---- Widgets: weather beside the AI/PC stack, with a shared 24 px gutter ----
        widgets = new LinearLayout(a);
        widgets.setOrientation(LinearLayout.VERTICAL);
        LayoutParams wl = new LayoutParams(px(1192), -2, Gravity.END | Gravity.TOP);
        wl.rightMargin = px(32);
        wl.topMargin = px(88);
        addView(widgets, wl);
        LinearLayout r1 = hbox(), r2 = hbox();
        r1.setGravity(Gravity.TOP);
        // v2.10 "Disponi riquadri": panels are offset with translations, so their rows must not clip them.
        widgets.setClipChildren(false);
        r1.setClipChildren(false);
        r2.setClipChildren(false);
        setClipChildren(false);
        r1.setBaselineAligned(false);
        widgets.addView(r1, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams r2l = new LinearLayout.LayoutParams(-1, -2);
        r2l.topMargin = px(16);
        widgets.addView(r2, r2l);
        wxBox = widget(r1, act.getString(R.string.v29_weather), 0);
        LinearLayout statsColumn = vbox();
        statsColumn.setClipChildren(false);
        LinearLayout.LayoutParams statsLayout = new LinearLayout.LayoutParams(px(584), -2);
        statsLayout.leftMargin = px(24);
        r1.addView(statsColumn, statsLayout);
        BoxView ai = widget(statsColumn, "ai credits · pc", 0);
        pcBox = widget(statsColumn, "omarchy · pc", 0);
        pcBox.setFocusable(false);
        // Explicit widths keep a lone widget from stretching when other panels are hidden.
        for (View col : new View[]{wxBox, pcBox, ai}) {
            LinearLayout.LayoutParams cl = (LinearLayout.LayoutParams) col.getLayoutParams();
            cl.width = px(584);
            cl.weight = 0f;
        }
        ((LinearLayout.LayoutParams) pcBox.getLayoutParams()).topMargin = px(16);
        wxBox.setMinimumHeight(px(208));
        ai.setMinimumHeight(px(208));
        r2.setGravity(Gravity.END);
        depBox = widget(r2, "deploys · pc", 0);
        depBox.setFocusable(false);
        ((LinearLayout.LayoutParams) depBox.getLayoutParams()).width = px(584);
        ((LinearLayout.LayoutParams) depBox.getLayoutParams()).weight = 0f;
        this.aiBoxRef = ai;
        wxTemp = text("", 50, true);
        wxLine1 = line("");
        wxLine1.setSingleLine(false);
        wxLine1.setMaxLines(2);
        wxLine2 = line("");
        wxBox.addView(wxTemp);
        wxBox.addView(wxLine1);
        wxBox.addView(wxLine2);
        pcBody = vbox();
        pcBox.addView(pcBody, new LinearLayout.LayoutParams(-1, -2));
        aiBody = vbox();
        ai.addView(aiBody, new LinearLayout.LayoutParams(-1, -2));
        depBody = vbox();
        depBox.addView(depBody, new LinearLayout.LayoutParams(-1, -2));
        remoteRow = hbox();
        remoteRow.setGravity(Gravity.TOP);
        remoteRow.setBaselineAligned(false);
        remoteRow.setClipChildren(false);
        LinearLayout.LayoutParams r3l = new LinearLayout.LayoutParams(-1, -2);
        r3l.topMargin = px(16);
        widgets.addView(remoteRow, r3l);
        for (int i = 0; i < remoteBox.length; i++) {
            remoteBox[i] = widget(remoteRow, "", i == 0 ? 0 : 16);
            remoteBox[i].setFocusable(false);   // read-only data: keep the D-pad path as it was
            remoteBody[i] = vbox();
            remoteBox[i].addView(remoteBody[i], new LinearLayout.LayoutParams(-1, -2));
        }
        remoteRow.setVisibility(GONE);
        wxBox.setOnClickListener(host::weatherMenu);
        ai.setOnClickListener(host::aiMenu);

        t3Box = new BoxView(a, s, tf);
        t3Box.setLegend("t3 code", px(14), px(18));
        t3RowHeight = 2 * text("", 24, false).getLineHeight() + px(12);
        t3Box.focusBorder = true;
        t3Box.setFocusable(true);
        t3Box.setClickable(true);
        t3Box.setOnClickListener(v -> host.sys("t3-code"));
        t3Body = vbox();
        t3Box.addView(t3Body);
        LayoutParams t3l = new LayoutParams(px(640), -2);
        t3l.leftMargin = px(32);
        t3l.topMargin = px(360);
        addView(t3Box, t3l);
        t3Box.setVisibility(GONE);
        t3Box.addOnLayoutChangeListener((v, l, top, r, bottom, ol, ot, or, ob) -> {
            if (bottom - top != ob - ot && cfg != null && editPanel == null) post(() -> applyPanelPos(cfg));
        });
        term.addOnLayoutChangeListener((v, l, top, r, bottom, ol, ot, or, ob) -> {
            if (bottom != ob) post(this::renderT3);
        });

        // ---- 3.4 app window: margins 32, padding 20×28 ----
        appWin = new BoxView(a, s, tf);
        LayoutParams al = new LayoutParams(-1, -2, Gravity.BOTTOM);
        al.leftMargin = al.rightMargin = al.bottomMargin = px(32);
        addView(appWin, al);
        appWin.setClipChildren(true);
        LinearLayout head = appHead = hbox();
        appName = text("", 32, true);
        appName.setSingleLine(true);
        appName.setEllipsize(TextUtils.TruncateAt.END);
        appName.setPadding(0, 0, px(24), 0);
        head.addView(appName, new LinearLayout.LayoutParams(0, -2, 1f));
        appHints = text("", 22, false);
        appHints.setSingleLine(true);
        head.addView(appHints, new LinearLayout.LayoutParams(-2, -2));
        appWin.addView(head, new LinearLayout.LayoutParams(-1, -2));
        row = new RecyclerView(a);
        row.setClipChildren(true);
        row.setClipToPadding(true);
        row.setPadding(px(10), px(10), px(10), px(10));
        row.setOverScrollMode(OVER_SCROLL_NEVER);
        row.setItemAnimator(null);
        row.setLayoutManager(new GridLayoutManager(a, rowCols(0)));
        rowAdapter = new TileAdapter(true, rowCols(0), row);   // bind() applies Layout → App size
        row.setAdapter(rowAdapter);
        appWin.addOnLayoutChangeListener((v, l, top, r, bottom, oldL, oldTop, oldR, oldBottom) -> {
            if (top != oldTop && cfg != null && editPanel == null) post(() -> {
                renderT3();
                applyPanelPos(cfg);
            });
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = px(16);
        appWin.addView(row, rl);

        // ---- v2.13 Grok Bot Desktop Companion (io.github.tcballard.grokbot-desktop, MIT) ----
        grokbotBox = new BoxView(a, s, tf);
        grokbotBox.setLegend("grokbot", px(10), px(14));
        grokbotBox.setFocusable(true);
        grokbotBox.setClickable(true);
        grokbotBox.focusBorder = true;
        LinearLayout gbRow = hbox();
        gbRow.setGravity(Gravity.CENTER_VERTICAL);
        grokbotMark = new GrokbotMark(a);
        grokbotMark.setVisibility(GONE);
        grokbotEyes = text("[ ◉ ◉ ]", 20, true);
        grokbotText = text(" companion · ask", 18, false);
        grokbotText.setSingleLine(true);
        grokbotText.setEllipsize(TextUtils.TruncateAt.END);
        grokbotText.setMaxWidth(px(620));
        gbRow.addView(grokbotMark, new LinearLayout.LayoutParams(px(34), px(34)));
        gbRow.addView(grokbotEyes);
        gbRow.addView(grokbotText);
        grokbotBox.addView(gbRow);
        grokbotBox.setOnClickListener(v -> host.sys("grokbot-companion"));
        LayoutParams gbl = new LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
        gbl.rightMargin = px(36);
        gbl.bottomMargin = px(296);
        addView(grokbotBox, gbl);

        // ---- v2.10 workspaces 2-5: same v2.9 windows (square boxes, titles in the border) ----
        tvWin = window("tv");
        tvBody = vbox();
        tvWin.addView(tvBody, new LinearLayout.LayoutParams(-1, -2));
        appsWin = window("app");
        LinearLayout gh = hbox();
        gridName = text("", 32, true);
        gridName.setSingleLine(true);
        gridName.setEllipsize(TextUtils.TruncateAt.END);
        gridName.setPadding(0, 0, px(24), 0);
        gh.addView(gridName, new LinearLayout.LayoutParams(0, -2, 1f));
        gridHints = text("", 22, false);
        gridHints.setSingleLine(true);
        gh.addView(gridHints, new LinearLayout.LayoutParams(-2, -2));
        appsWin.addView(gh, new LinearLayout.LayoutParams(-1, -2));
        grid = new RecyclerView(a);
        grid.setClipChildren(true);
        grid.setClipToPadding(true);
        grid.setPadding(px(8), px(12), px(8), px(12));
        grid.setOverScrollMode(OVER_SCROLL_NEVER);
        grid.setItemAnimator(null);
        grid.setLayoutManager(new GridLayoutManager(a, 7));
        gridAdapter = new TileAdapter(false, 7, grid);
        grid.setAdapter(gridAdapter);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, 0, 1f);
        gl.topMargin = px(8);
        appsWin.addView(grid, gl);
        ((LayoutParams) appsWin.getLayoutParams()).height = -1;
        pcWin = window("pc");
        pcPaneBody = vbox();
        pcWin.addView(pcPaneBody, new LinearLayout.LayoutParams(-1, -2));
        sysWin = window(act.getString(R.string.v29_system));
        sysBody = vbox();
        sysWin.addView(sysBody, new LinearLayout.LayoutParams(-1, -2));
    }

    /** A full-width v2.9 window under the bar for workspaces 2-5 (hidden until its workspace is shown). */
    private BoxView window(String title) {
        BoxView b = new BoxView(act, s, tf);
        b.setLegend(title, px(20), px(28));
        b.setClipChildren(false);
        b.setClipToPadding(false);
        LayoutParams l = new LayoutParams(-1, -2);
        l.leftMargin = l.rightMargin = l.bottomMargin = px(32);
        l.topMargin = px(88);
        b.setVisibility(GONE);
        addView(b, l);
        return b;
    }

    private BoxView aiBoxRef;

    // ---------------------------------------------------------------------------------------------------------

    int px(float v) {
        return Math.round(v * s);
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** Text scale for the large workspace-4 widgets (1 elsewhere). */
    private float big = 1f;

    private TextView text(String v, float sizePx, boolean bold) {
        TextView tv = new TextView(act);
        tv.setText(v);
        tv.setTypeface(bold ? tfBold : tf);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * s * big);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(ink(Theme.cur().fg));
        return tv;
    }

    private TextView line(String v) {
        TextView tv = text(v, 24, false);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = px(6);
        tv.setLayoutParams(l);
        return tv;
    }

    private BoxView widget(LinearLayout parent, String title, int gapLeft) {
        BoxView b = new BoxView(act, s, tf);
        b.setLegend(title, px(18), px(20));
        b.setFocusable(true);
        b.focusBorder = true;
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1f);
        l.leftMargin = px(gapLeft);
        parent.addView(b, l);
        return b;
    }

    private View iconButton(int res, int boxPx, int iconPx) {
        FrameLayout f = new FrameLayout(act);
        f.setFocusable(true);
        f.setClickable(true);
        ImageView iv = new ImageView(act);
        iv.setImageResource(res);
        f.addView(iv, new LayoutParams(px(iconPx), px(iconPx), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(px(boxPx), px(56)));
        f.setBackground(new Underline());
        return f;
    }

    /** Waybar focus/active marker: 3 px accent underline (no rounding). */
    private final class Underline extends Drawable {
        private final Paint p = new Paint();

        @Override
        public void draw(@NonNull android.graphics.Canvas c) {
            int[] st = getState();
            boolean on = false;
            for (int x : st) if (x == android.R.attr.state_focused) on = true;
            if (!on) return;
            android.graphics.Rect b = getBounds();
            p.setColor(Theme.withAlpha(t != null ? t.fg : Color.WHITE, 0.16f));
            c.drawRect(b, p);
            p.setColor(t != null ? ink(t.accent) : Color.WHITE);
            c.drawRect(b.left + px(4), b.bottom - px(5), b.right - px(4), b.bottom, p);
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int a) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private void open(android.content.Intent i) {
        try {
            act.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    private String logoText() {
        try (InputStream in = act.getAssets().open("saver/screensaver-tv.txt")) {
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            String txt = new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8);
            return txt.replaceAll("\\s+$", "");
        } catch (Exception e) {
            return "OMARCHY TV";
        }
    }

    // User opacity is the starting point; text windows raise it only as far as contrast requires.

    static float opacity(LauncherConfig c) {
        return 1f - Math.max(5, Math.min(60, c.transparency)) / 100f;
    }

    static float noBlur() {
        return Build.VERSION.SDK_INT < 31 ? 0.10f : 0f;
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Re-reads config and theme and redraws everything (resume, theme push, settings change). */
    void bind(LauncherConfig c, AppAdapter apps) {
        cfg = c;
        t = Theme.cur();
        this.apps = apps;
        catalog = apps.fullCatalog();
        int winBg = windowBackground();
        bar.setBackgroundColor(winBg);
        clock.setTextColor(ink(t.fgBright));
        buildStamp.setTextColor(ink(t.fgDim));
        for (int i = 0; i < 5; i++) {
            boolean on = c.workspaceAt(i + 1) == activeWs;
            ws[i].setTextColor(ink(on ? t.accent : t.fg));
            ws[i].setTypeface(on ? tfBold : tf);
            ws[i].setPaintFlags(on ? ws[i].getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG : ws[i].getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
            ws[i].setVisibility(c.workspaceAt(i + 1) > 0 ? VISIBLE : GONE);
            ws[i].setText(c.workspaceNamesInBar && c.workspaceAt(i + 1) > 0 ? c.workspaceName(c.workspaceAt(i + 1)) : String.valueOf(i + 1));
        }
        tint(menuBtn, ink(t.fgBright));
        for (View b : barIcons) tint(b, ink(t.fgBright));
        tip.setBackgroundColor(Theme.withAlpha(darkSurface(t.bg), 1f));
        tip.setTextColor(ink(t.fgBright));
        for (BoxView b : new BoxView[]{term, wxBox, pcBox, aiBoxRef, depBox, t3Box, appWin, tvWin, appsWin, pcWin, sysWin, grokbotBox}) b.setColors(winBg, t.muted, ink(t.accent), ink(t.cyan));
        barSpacex.setTextColor(ink(t.accent2));
        barSpacex.setVisibility(c.pluginSpacexTv ? VISIBLE : GONE);
        grokbotEyes.setTextColor(ink(t.accent));
        grokbotText.setTextColor(ink(t.fgBright));
        boolean mirrored = c.grokbotBotName != null && !c.grokbotBotName.isEmpty();
        grokbotMark.setVisibility(VISIBLE);   // v2.13: Grok Bot's ball when nothing is mirrored
        grokbotEyes.setVisibility(GONE);
        grokbotMark.set(mirrored ? c.grokbotBotShape : "orb", mirrored && c.grokbotBotFill != 0 ? c.grokbotBotFill : GrokbotMark.GROKBOT_ORANGE);
        String gbMsg = c.grokbotMessage != null && !c.grokbotMessage.isEmpty() ? c.grokbotMessage : null;
        grokbotText.setText(mirrored ? "  " + c.grokbotBotName + (gbMsg != null ? ": " + gbMsg : " · ask")
                : gbMsg != null ? " " + gbMsg : " companion · ask");
        // The floating face is the one companion: this "companion · ask" tile only stands in when it is off.
        grokbotBox.setVisibility(activeWs == 1 && CompanionPlacement.homeTile(c.pluginGrokbot, c.companionOverlay) ? VISIBLE : GONE);
        for (BoxView b : remoteBox) b.setColors(winBg, t.muted, ink(t.accent), ink(t.cyan));
        // v2.10: every home widget can be switched off (Settings → Widgets); the clock lives only in the bar.
        wxBox.setVisibility(c.wWeather ? VISIBLE : GONE);
        pcBox.setVisibility(c.wPc ? VISIBLE : GONE);
        aiBoxRef.setVisibility(c.wAi ? VISIBLE : GONE);
        term.setVisibility(activeWs == 1 && c.wTerm ? VISIBLE : GONE);
        if (editPanel == null) post(() -> applyPanelPos(c));
        gridName.setTextColor(ink(t.accent));
        gridHints.setTextColor(ink(t.fg));
        gridHints.setText(gridHintsText());
        term.setLegend(null, px(18), px(22));
        appWin.setLegend(null, px(20), px(28));
        // fastfetch
        SpannableStringBuilder p = new SpannableStringBuilder();
        span(p, c.promptUser + "@" + c.promptHost, t.green);
        span(p, ":", t.fg);
        span(p, "~", t.accent);
        span(p, "$ ", t.fg);
        int cs = p.length();
        p.append("█");
        cursorSpan = new ForegroundColorSpan(ink(t.fg));
        p.setSpan(cursorSpan, cs, p.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        promptText = p;
        termPrompt.setText(p, TextView.BufferType.SPANNABLE);
        termLogo.setTextColor(ink(t.accent));
        appName.setTextColor(ink(t.accent));
        appHints.setTextColor(ink(t.fg));
        updateRowHeader();
        rowAdapter.cols = rowCols(c.columns);
        ((GridLayoutManager) row.getLayoutManager()).setSpanCount(rowAdapter.cols);
        int rows = Math.min(2, Math.max(1, (rowAdapter.getItemCount() + rowAdapter.cols - 1) / rowAdapter.cols));
        LinearLayout.LayoutParams rowLayout = (LinearLayout.LayoutParams) row.getLayoutParams();
        rowLayout.height = rows * (rowAdapter.tileH(rowAdapter.cols) + px(20)) + row.getPaddingTop() + row.getPaddingBottom();
        row.setLayoutParams(rowLayout);
        LayoutParams gbl = (LayoutParams) grokbotBox.getLayoutParams();
        gbl.bottomMargin = rowLayout.height + px(140);
        grokbotBox.setLayoutParams(gbl);
        rowAdapter.notifyDataSetChanged();
        gridAdapter.notifyDataSetChanged();
        refresh();
        buildWorkspace();
    }

    /** Home stays dark while keeping the selected theme's hue and accents. */
    private int darkSurface(int color) {
        return Surfaces.luminance(color) > 0.18 ? Theme.mix(color, Color.BLACK, 0.90f) : color;
    }

    private int ink(int color) {
        return ink(color, darkSurface((t != null ? t : Theme.cur()).bg));
    }

    /** Contrast belongs to the surface being drawn, which may differ in a partial custom palette. */
    private int ink(int color, int background) {
        Theme theme = t != null ? t : Theme.cur();
        int fallback = Surfaces.luminance(background) > 0.179 ? Color.BLACK : Color.WHITE;
        int foreground = Theme.readable(theme.fg, fallback, background);
        int readable = Theme.readable(color, foreground, background);
        for (int i = 0; i <= 20; i++) {
            int candidate = Theme.mix(readable, fallback, i / 20f);
            if (Surfaces.contrast(Surfaces.luminance(candidate), Surfaces.luminance(background)) >= 7) return candidate;
        }
        return fallback;
    }

    private int tileBackground() { return darkSurface(t.bgLight); }

    private int windowBackground() {
        int background = darkSurface(t.bg);
        float alpha = Surfaces.wallpaperAlpha(background, Math.max(0.90f, opacity(cfg) + noBlur()),
                ink(t.fg), ink(t.fgBright), ink(t.accent), ink(t.accent2), ink(t.cyan), ink(t.green), ink(t.red), ink(t.yellow));
        return Theme.withAlpha(background, alpha);
    }

    private static void tint(View b, int color) {
        View iv = ((ViewGroup) b).getChildAt(0);
        if (iv instanceof ImageView) ((ImageView) iv).setColorFilter(color);
    }

    private void span(SpannableStringBuilder b, String s, int color) {
        int st = b.length();
        b.append(s);
        b.setSpan(new ForegroundColorSpan(ink(color)), st, b.length(), 0);
    }

    /** Clock, fastfetch line, widgets; cheap (in-memory reads + small cached files). */
    void refresh() {
        if (cfg == null) return;
        Locale loc = !"auto".equals(cfg.language) && !cfg.language.isEmpty() ? new Locale(cfg.language) : Locale.getDefault();
        String d = new SimpleDateFormat("EEE d MMM", loc).format(new Date()).replace(".", "").toLowerCase(loc);
        String h = new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(act) ? "HH:mm" : "h:mm a", loc).format(new Date());
        clock.setText(d + "  " + h);
        long up = SystemClock.elapsedRealtime() / 60_000L;
        SpannableStringBuilder info = new SpannableStringBuilder();
        span(info, act.getString(R.string.v29_theme) + " ", t.accent);
        span(info, t.name + "\n", t.fg);
        span(info, act.getString(R.string.v29_net) + " ", t.accent);
        span(info, host.wifiName() + "  ·  ", t.fg);
        span(info, act.getString(R.string.v29_up) + " ", t.accent);
        span(info, (up / 60 > 0 ? (up / 60) + " h " : "") + (up % 60) + " min", t.fg);
        termInfo.setText(info);
        updateWeather();
        updatePc(pcBody, depBody, depBox, cfg.wDeploys);
        updateAi(aiBody);
        updateRemote();
        JSONObject liveAgents = OmarchyLink.agents();
        updateT3(liveAgents != null ? liveAgents : LauncherConfig.get(act).agentsData, OmarchyLink.connected());
        if (activeWs == 4) buildWorkspace();
    }

    /** One snapshot feeds the Home summary and the existing read-only agent details. */
    void updateT3(JSONObject snapshot, boolean connected) {
        t3Agents = com.omarchytv.home.link.Agents.parse(snapshot);
        t3Connected = connected;
        renderT3();
    }

    private void renderT3() {
        if (cfg == null) return;
        List<com.omarchytv.home.link.Agents.Agent> threads = new ArrayList<>();
        for (com.omarchytv.home.link.Agents.Agent g : t3Agents.list) {
            if ("t3-code".equals(g.agent) && (g.title.isEmpty() || "running".equals(g.status)
                    || "thinking".equals(g.status) || "waiting".equals(g.status))) threads.add(g);
        }
        int top = term.isShown() && term.getBottom() > 0 ? term.getBottom() + px(24) : px(cfg.wTerm ? 360 : 88);
        int bottom = appWin.getTop() > 0 ? appWin.getTop() - px(24) : px(740);
        int slots = Math.min(3, Math.max(0,
                (bottom - top - t3Box.getPaddingTop() - t3Box.getPaddingBottom() + px(12)) / t3RowHeight));
        boolean show = activeWs == 1 && cfg.wT3 && t3Agents.enabled && !threads.isEmpty() && slots > 0;
        if (!show && t3Box.hasFocus()) focusApp(lastApp);
        t3Box.setVisibility(show ? VISIBLE : GONE);
        if (!show) return;
        LayoutParams lp = (LayoutParams) t3Box.getLayoutParams();
        if (lp.topMargin != top) { lp.topMargin = top; t3Box.setLayoutParams(lp); }
        t3Body.removeAllViews();
        long active = threads.stream().filter(g -> !g.title.isEmpty()).count();
        long received = t3Agents.rx > 0 ? t3Agents.rx : com.omarchytv.home.link.Agents.isoMs(t3Agents.at);
        boolean stale = received <= 0 || System.currentTimeMillis() - received > 90000;
        t3Box.setLegend("t3 code" + (active > 0 && t3Connected && !stale ? " · " + active + " · OK" : ""), px(14), px(18));
        if (!t3Connected || stale) {
            TextView off = line(act.getString(t3Connected ? R.string.t3_unavailable : R.string.link_offline));
            off.setTextColor(ink(t.fg));
            t3Body.addView(off);
        } else {
            for (int i = 0; i < Math.min(slots, threads.size()); i++) {
                com.omarchytv.home.link.Agents.Agent g = threads.get(i);
                TextView title = text(g.title.isEmpty() ? act.getString("idle".equals(g.status) ? R.string.t3_empty : R.string.t3_unavailable) : g.title, 24, true);
                title.setTextColor(ink(t.fgBright));
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
                tl.topMargin = px(i == 0 ? 0 : 12);
                t3Body.addView(title, tl);
                if (!g.title.isEmpty()) {
                    String state = "running".equals(g.status) ? act.getString(R.string.t3_working)
                            : "thinking".equals(g.status) ? act.getString(R.string.t3_queued) : AgentUi.word(act, g.status);
                    TextView status = text((g.project.isEmpty() ? "" : g.project + " · ") + state, 24, false);
                    status.setTextColor(ink(AgentUi.color(g.status)));
                    status.setSingleLine(true);
                    status.setEllipsize(TextUtils.TruncateAt.END);
                    t3Body.addView(status);
                }
            }
        }
        t3Box.setContentDescription(act.getString(R.string.t3_widget) + ", " + active + ", " + act.getString(R.string.t3_details));
    }

    /** Fetched panes (RemotePanes): "label … value" rows, value green/red by tone; dim after ttl, gone after 2 x ttl. */
    private void updateRemote() {
        JSONObject cache = RemotePanes.cached(act, cfg.remotePanesUrl);
        JSONArray panes = cache != null ? cache.optJSONArray("panes") : null;
        long now = System.currentTimeMillis() / 1000L;
        int shown = 0;
        for (int i = 0; i < remoteBox.length; i++) {
            JSONObject p = panes != null ? panes.optJSONObject(i) : null;
            long age = p != null ? now - RemotePanes.paneAt(cache, p) : 0;
            boolean show = p != null && activeWs == 1 && age <= 2L * p.optInt("ttl", 180);
            remoteBox[i].setVisibility(show ? VISIBLE : GONE);
            remoteBody[i].removeAllViews();
            if (!show) continue;
            shown++;
            remoteBox[i].setLegend(p.optString("title", p.optString("id")).toLowerCase(Locale.getDefault()), px(14), px(18));
            remoteBody[i].setAlpha(age > p.optInt("ttl", 180) ? 0.55f : 1f);
            JSONArray lines = p.optJSONArray("lines");
            for (int k = 0; lines != null && k < lines.length(); k++) {
                JSONObject l = lines.optJSONObject(k);
                if (l == null) continue;
                LinearLayout r = hbox();
                TextView n = text(l.optString("label"), REMOTE_TEXT, false);
                n.setTextColor(ink(t.fg));
                n.setSingleLine(true);
                n.setEllipsize(TextUtils.TruncateAt.END);
                r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
                String tone = l.optString("tone");
                TextView v = text(l.optString("value"), REMOTE_TEXT, false);
                v.setTextColor(ink("up".equals(tone) ? t.green : "down".equals(tone) ? t.red : t.fgBright));
                v.setSingleLine(true);
                LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
                vl.leftMargin = px(12);
                r.addView(v, vl);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
                ll.topMargin = px(k == 0 ? 0 : 8);
                remoteBody[i].addView(r, ll);
            }
        }
        remoteRow.setVisibility(shown > 0 ? VISIBLE : GONE);
        // With the widgets above switched off, the row moves up to the top (aligned with the fastfetch box).
        View row2 = (View) depBox.getParent();
        row2.setVisibility(depBox.getVisibility());
        boolean above = wxBox.getVisibility() == VISIBLE || pcBox.getVisibility() == VISIBLE
                || aiBoxRef.getVisibility() == VISIBLE || depBox.getVisibility() == VISIBLE;
        LinearLayout.LayoutParams rl = (LinearLayout.LayoutParams) remoteRow.getLayoutParams();
        int top = above ? px(16) : 0;
        if (rl.topMargin != top) {
            rl.topMargin = top;
            remoteRow.setLayoutParams(rl);
        }
    }

    /** Fetched panes share the home widgets’ couch-readable type size. */
    private static final float REMOTE_TEXT = 24;

    private void updateWeather() {
        wxTemp.setTextColor(ink(t.accent));
        wxLine1.setTextColor(ink(t.fg));
        wxLine2.setTextColor(ink(t.fg));
        JSONObject w = Weather.cached(act);
        JSONObject cur = w != null ? w.optJSONObject("current") : null;
        barWx.setVisibility(cfg.barWeather && cur != null ? VISIBLE : GONE);
        if (cfg.barWeather && cur != null) {
            barWxIcon.setImageResource(Weather.icon(cur.optInt("weather_code")));
            barWxIcon.setColorFilter(ink(t.fgBright));
            barWxTemp.setTextColor(ink(t.fgBright));
            barWxTemp.setText(Weather.temp(cur.optDouble("temperature_2m"), cfg.tempF) + (cfg.tempF ? "°F" : "°"));
            barWx.setContentDescription(act.getString(Weather.describe(cur.optInt("weather_code"))));
        }
        if (cur == null) {
            wxTemp.setText("--°");
            wxLine1.setText(Weather.where(act, cfg) == null ? act.getString(R.string.v29_wx_locating) : act.getString(R.string.weather_loading));
            wxLine2.setText("");
            return;
        }
        wxTemp.setText(Weather.temp(cur.optDouble("temperature_2m"), cfg.tempF) + (cfg.tempF ? "°F" : "°"));
        String place = Weather.place(act, cfg);
        String cond = act.getString(Weather.describe(cur.optInt("weather_code"))).toLowerCase(Locale.getDefault());
        wxLine1.setText(place.isEmpty() ? cond : place + "\n" + cond);
        JSONObject daily = w.optJSONObject("daily");
        JSONArray mn = daily != null ? daily.optJSONArray("temperature_2m_min") : null, mx = daily != null ? daily.optJSONArray("temperature_2m_max") : null;
        wxLine2.setText(mn != null && mx != null ? "min " + Weather.temp(mn.optDouble(0), cfg.tempF) + "° · max " + Weather.temp(mx.optDouble(0), cfg.tempF) + "°" : "");
    }

    private TextView offline() {
        TextView v = line(act.getString(R.string.v29_pc_off));
        v.setTextColor(ink(t.fg));
        return v;
    }

    /** "label  [bar]  NN%": bar 12 px (CPU/RAM) or 10 px (AI), fill colour given, track = selection. */
    private View barRow(String label, int labelCols, double frac, int fill, int barH, String right) {
        LinearLayout r = hbox();
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = px(6);
        r.setLayoutParams(l);
        TextView a = text(label, 24, false);
        a.setTextColor(ink(t.fg));
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        float cw = a.getPaint().measureText("M");
        r.addView(a, new LinearLayout.LayoutParams(Math.round(cw * labelCols), -2));
        BarView b = new BarView(act, darkSurface(t.selection), fill, frac);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, px(barH), 1f);
        bl.leftMargin = px(8);
        bl.rightMargin = px(10);
        r.addView(b, bl);
        TextView pct = text(right, 24, true);
        pct.setTextColor(ink(t.fg));
        pct.setGravity(Gravity.END);
        r.addView(pct, new LinearLayout.LayoutParams(Math.round(cw * 4), -2));
        return r;
    }

    private void updatePc(LinearLayout pcBody, LinearLayout depBody, View depBox, boolean depOn) {
        pcBody.removeAllViews();
        depBody.removeAllViews();
        JSONObject st = OmarchyLink.connected() ? com.omarchytv.home.link.LinkServer.stats() : null;
        if (!OmarchyLink.connected()) {
            pcBody.addView(offline());
        } else if (st == null) {
            pcBody.addView(line("…"));
        } else {
            if (st.has("cpu")) pcBody.addView(barRow("cpu", 4, st.optInt("cpu") / 100.0, t.green, 12, st.optInt("cpu") + "%"));
            if (st.has("ram")) pcBody.addView(barRow("ram", 4, st.optInt("ram") / 100.0, t.yellow, 12, st.optInt("ram") + "%"));
            JSONObject np = st.optJSONObject("nowPlaying");
            if (np != null) {
                LinearLayout r = hbox();
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
                l.topMargin = px(8);
                ImageView ic = new ImageView(act);
                ic.setImageResource(R.drawable.ln_music);
                ic.setColorFilter(t.magenta);
                r.addView(ic, new LinearLayout.LayoutParams(px(19), px(19)));
                String a = np.optString("artist", "");
                TextView tx = text((a.isEmpty() ? "" : a + " — ") + np.optString("title"), 24, false);
                tx.setTextColor(ink(t.fg));
                tx.setSingleLine(true);
                tx.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams txl = new LinearLayout.LayoutParams(0, -2, 1f);
                txl.leftMargin = px(8);
                r.addView(tx, txl);
                pcBody.addView(r, l);
            }
        }
        JSONArray d = st != null ? st.optJSONArray("deploys") : null;
        depBox.setVisibility(depOn && d != null && d.length() > 0 ? VISIBLE : GONE);
        for (int i = 0; d != null && i < d.length(); i++) {
            JSONObject x = d.optJSONObject(i);
            String stt = x.optString("status");
            int col = "green".equals(stt) || "ok".equals(stt) ? t.green : "yellow".equals(stt) || "building".equals(stt) ? t.yellow : t.red;
            LinearLayout r = hbox();
            TextView n = text(x.optString("name"), 24, false);
            n.setTextColor(ink(t.fg));
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView v = text(stt, 24, false);
            v.setTextColor(ink(col));
            v.setSingleLine(true);
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
            vl.leftMargin = px(12);
            r.addView(v, vl);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
            l.topMargin = px(i == 0 ? 0 : 6);
            depBody.addView(r, l);
        }
    }

    private void updateAi(LinearLayout aiBody) {
        aiBody.removeAllViews();
        if (!OmarchyLink.connected() && cfg.aiAt > 0 || cfg.ai.isEmpty() && !OmarchyLink.connected()) {
            aiBody.addView(offline());
            return;
        }
        if (cfg.ai.isEmpty()) {
            aiBody.addView(line(act.getString(R.string.ai_empty)));
            return;
        }
        int nameW = 4;
        for (LauncherConfig.AiEntry a : cfg.ai) nameW = Math.max(nameW, Math.min(8, a.name.length()));
        int shown = 0;
        for (LauncherConfig.AiEntry a : cfg.ai) {
            if (shown++ >= 5) break;
            double f = a.fraction();
            if (f >= 0) {
                int col = f > 0.9 ? t.red : f >= 0.7 ? t.yellow : t.green;
                aiBody.addView(barRow(a.name, nameW, f, col, 10, Math.round(f * 100) + "%"));
            } else {
                TextView v = line(a.name + "  " + (a.usedUnknown() ? "?" : MainActivity.amountText(a.used, a.unit)));
                v.setTextColor(ink(t.fg));
                aiBody.addView(v);
            }
        }
    }

    // ---- bar tooltip (network name only in the menu shown while the icon is focused) ----

    private void showTip(View anchor, int k) {
        if (anchor == null) {
            tip.setVisibility(GONE);
            return;
        }
        String[] labels = {"cinemarchy", host.wifiName(), "bluetooth", act.getString(R.string.v29_volume), act.getString(R.string.inputs),
                act.getString(R.string.v29_screen_pc)};
        if (k == 3) {
            android.media.AudioManager am = act.getSystemService(android.media.AudioManager.class);
            if (am != null) labels[3] += " " + Math.round(100f * am.getStreamVolume(3) / Math.max(1, am.getStreamMaxVolume(3))) + "%";
        }
        tip.setText(labels[k]);
        tip.setVisibility(VISIBLE);
        tip.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2], me = new int[2];
        anchor.getLocationInWindow(loc);
        getLocationInWindow(me);
        LayoutParams l = (LayoutParams) tip.getLayoutParams();
        l.topMargin = px(72);
        l.leftMargin = Math.max(px(32), Math.min(getWidth() - px(32) - tip.getMeasuredWidth(), loc[0] - me[0] + anchor.getWidth() / 2 - tip.getMeasuredWidth() / 2));
        tip.setLayoutParams(l);
    }

    // ---- workspaces (v2.10: 1 home · 2 tv · 3 app · 4 pc · 5 sistema, all inside this view) ----

    int workspace() {
        return activeWs;
    }

    /** The bar number of the active workspace ("workspaces": {"order"} decides its position). */
    private TextView activeWsButton() {
        int pos = cfg != null ? cfg.positionOf(activeWs) : activeWs;
        return ws[Math.max(1, pos) - 1];
    }

    void setWorkspace(int n) {
        if (n < 1 || n > 5) return;
        int prev = activeWs;
        View f = act.getCurrentFocus();
        if (f != null && zone(f) >= 1) wsFocus.put(prev, f);
        if (movingApp >= 0) host.finishMove(false);
        activeWs = n;
        boolean home = n == 1;
        term.setVisibility(home && (cfg == null || cfg.wTerm) ? VISIBLE : GONE);
        widgets.setVisibility(home ? VISIBLE : GONE);
        appWin.setVisibility(home ? VISIBLE : GONE);
        grokbotBox.setVisibility(home && (cfg == null ? false : CompanionPlacement.homeTile(cfg.pluginGrokbot, cfg.companionOverlay)) ? VISIBLE : GONE);
        tvWin.setVisibility(n == 2 ? VISIBLE : GONE);
        appsWin.setVisibility(n == 3 ? VISIBLE : GONE);
        pcWin.setVisibility(n == 4 ? VISIBLE : GONE);
        sysWin.setVisibility(n == 5 ? VISIBLE : GONE);
        if (cfg != null) bind(cfg, apps);
        // Horizontal slide, 120 ms (spec: ≤150 ms); no animation on the first show.
        if (prev != n) {
            boolean right = cfg != null ? cfg.positionOf(n) > cfg.positionOf(prev) : n > prev;
            float dx = (right ? 1 : -1) * px(80);
            for (View v : new View[]{term, widgets, appWin, tvWin, appsWin, pcWin, sysWin}) {
                if (v.getVisibility() != VISIBLE) continue;
                v.setTranslationX(dx);
                v.setAlpha(0.4f);
                v.animate().translationX(0).alpha(1f).setDuration(120).start();
            }
        }
        if (home) stopLogo();
        post(this::restoreFocus);
    }

    /** Focus the view this workspace had, else its default (TV tile on home, first tile / row elsewhere). */
    void restoreFocus() {
        View v = wsFocus.get(activeWs);
        if (v != null && v.isAttachedToWindow() && v.isShown() && v.requestFocus()) return;
        focusDefault();
    }

    void focusDefault() {
        switch (activeWs) {
            case 1:
                focusApp(0);
                break;
            case 3:
                focusGrid(0);
                break;
            default: {
                ViewGroup g = activeWs == 2 ? tvBody : activeWs == 4 ? pcPaneBody : sysBody;
                if (!focusFirst(g)) activeWsButton().requestFocus();
            }
        }
    }

    private static boolean focusFirst(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != VISIBLE) continue;
            if (v.isFocusable() && v.requestFocus()) return true;
            if (v instanceof ViewGroup && focusFirst((ViewGroup) v)) return true;
        }
        return false;
    }

    /** Called by MainActivity on resume: home always comes back on the TV tile (spec: default focus). */
    void onHomeShown() {
        wsFocus.remove(1);
        lastApp = 0;
        if (activeWs == 1) {
            focusApp(0);
            startLogo();
        } else restoreFocus();
    }

    /** Blinking prompt cursor on the home (driven by MainActivity's blink tick while resumed). */
    void blink(boolean on) {
        if (cursorSpan == null || promptText == null || activeWs != 1 || t == null) return;
        int st = promptText.getSpanStart(cursorSpan), en = promptText.getSpanEnd(cursorSpan);
        if (st < 0) return;
        promptText.removeSpan(cursorSpan);
        cursorSpan = new ForegroundColorSpan(on ? t.fg : Color.TRANSPARENT);
        promptText.setSpan(cursorSpan, st, en, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        termPrompt.setText(promptText, TextView.BufferType.SPANNABLE);
        if (grokbotBox.getVisibility() == VISIBLE) {
            grokbotEyes.setText(on ? "[ ◉ ◉ ]" : "[ ▬ ▬ ]");
            grokbotEyes.setTextColor(on ? t.accent : t.accent2);
            grokbotMark.blink(on || SystemClock.uptimeMillis() % 4000 > 600);
        }
    }

    private void buildWorkspace() {
        if (t == null) return;
        if (activeWs == 2) buildTv();
        else if (activeWs == 4) buildPc();
        else if (activeWs == 5) buildSys();
    }

    // ---- 2 · tv: TV + connected HDMI inputs as large 16:9 tiles (no channels, no guide) ----

    private void buildTv() {
        View had = act.getCurrentFocus();
        int hadIdx = -1;
        tvBody.removeAllViews();
        List<Source> src = host.sources();
        int cols = 4, gap = px(24);
        int inner = Math.round(1920 * s) - px(64) - px(56);
        int w = (inner - gap * (cols - 1)) / cols;
        LinearLayout r = null;
        for (int i = 0; i < src.size(); i++) {
            if (i % cols == 0) {
                r = hbox();
                r.setClipChildren(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.topMargin = i == 0 ? px(12) : gap;
                tvBody.addView(r, rl);
            }
            Source x = src.get(i);
            FrameLayout f = bigTile(x.icon, x.label, x.sub);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, w * 9 / 16);
            l.rightMargin = (i % cols) == cols - 1 ? 0 : gap;
            r.addView(f, l);
            f.setOnClickListener(v -> x.open.run());
        }
        if (src.isEmpty()) {
            TextView v = line(act.getString(R.string.no_inputs));
            v.setTextColor(ink(t.fg));
            tvBody.addView(v);
        }
    }

    private FrameLayout bigTile(int icon, String label, String sub) {
        FrameLayout f = new FrameLayout(act);
        f.setFocusable(true);
        f.setClickable(true);
        LinearLayout col = vbox();
        col.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(act);
        iv.setImageResource(icon);
        iv.setColorFilter(t.accent);
        col.addView(iv, new LinearLayout.LayoutParams(px(72), px(72)));
        TextView tv = text(label, 30, true);
        tv.setTextColor(ink(t.fgBright, tileBackground()));
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = px(12);
        col.addView(tv, tl);
        if (sub != null && !sub.isEmpty()) {
            TextView sv = text(sub, 20, false);
            sv.setTextColor(ink(t.fg, tileBackground()));
            sv.setSingleLine(true);
            sv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(sv, new LinearLayout.LayoutParams(-2, -2));
        }
        f.addView(col, new LayoutParams(-1, -1));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(tileBackground());
        f.setBackground(bg);
        f.setForeground(new FocusRing());
        styleTile(f, false, false);
        f.setOnFocusChangeListener((v, has) -> styleTile((FrameLayout) v, has, true));
        return f;
    }

    // ---- 4 · pc: pairing prompt, or the omarchy · pc / ai credits / deploys widgets in large ----

    private void buildPc() {
        boolean keep = pcWin.hasFocus();
        pcPaneBody.removeAllViews();
        if (!host.pcPaired()) {
            TextView m = text(act.getString(R.string.v210_pc_link), 34, true);
            m.setTextColor(ink(t.fgBright));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
            ml.topMargin = px(24);
            pcPaneBody.addView(m, ml);
            TextView h = line(act.getString(R.string.v210_pc_link_hint));
            h.setTextColor(ink(t.fg));
            h.setSingleLine(false);
            pcPaneBody.addView(h);
            LinearLayout actions = hbox();
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
            al.topMargin = px(28);
            al.bottomMargin = px(16);
            TextView b = button(act.getString(R.string.v210_pc_pair), () -> host.pairPc());
            TextView bGrok = button(act.getString(R.string.grok_bot_hub), () -> host.sys("grok-bot"));
            TextView bCine = button("Cinemarchy", () -> host.sys("cinemarchy"));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
            gl.leftMargin = px(18);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.leftMargin = px(18);
            actions.addView(b);
            actions.addView(bGrok, gl);
            actions.addView(bCine, cl);
            pcPaneBody.addView(actions, al);
            if (keep) b.requestFocus();
            return;
        }
        big = 1.35f;
        LinearLayout r = hbox();
        r.setGravity(Gravity.TOP);
        r.setBaselineAligned(false);
        pcPaneBody.addView(r, new LinearLayout.LayoutParams(-1, -2));
        BoxView pc = widget(r, "omarchy · pc", 0), ai = widget(r, "ai credits · pc", 24), dep = widget(r, "deploys · pc", 24);
        int winBg = windowBackground();
        for (BoxView b : new BoxView[]{pc, ai, dep}) b.setColors(winBg, t.muted, ink(t.accent), ink(t.cyan));
        LinearLayout pb = vbox(), ab = vbox(), db = vbox();
        pc.addView(pb, new LinearLayout.LayoutParams(-1, -2));
        ai.addView(ab, new LinearLayout.LayoutParams(-1, -2));
        dep.addView(db, new LinearLayout.LayoutParams(-1, -2));
        updatePc(pb, db, dep, true);
        if (dep.getVisibility() != VISIBLE) {
            dep.setVisibility(VISIBLE);
            TextView v = line("—");
            v.setTextColor(ink(t.fg));
            db.addView(v);
        }
        updateAi(ab);
        ai.setOnClickListener(host::aiMenu);
        big = 1f;
        LinearLayout actions = hbox();
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.topMargin = px(20);
        TextView bGrok = button(act.getString(R.string.grok_bot_hub), () -> host.sys("grok-bot"));
        TextView bCine = button("Cinemarchy", () -> host.sys("cinemarchy"));
        TextView bAsk = button(act.getString(R.string.ask_entry), () -> host.sys("ask"));
        TextView bAgents = button("> agents", () -> host.sys("agents"));
        LinearLayout.LayoutParams sl0 = new LinearLayout.LayoutParams(-2, -2);
        sl0.leftMargin = px(16);
        LinearLayout.LayoutParams sl1 = new LinearLayout.LayoutParams(-2, -2);
        sl1.leftMargin = px(16);
        LinearLayout.LayoutParams sl2 = new LinearLayout.LayoutParams(-2, -2);
        sl2.leftMargin = px(16);
        actions.addView(bGrok);
        actions.addView(bCine, sl0);
        actions.addView(bAsk, sl1);
        actions.addView(bAgents, sl2);
        pcPaneBody.addView(actions, al);
        if (keep) pc.requestFocus();
    }

    private TextView button(String label, Runnable r) {
        TextView b = text("[ " + label + " ]", 26, true);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(px(18), px(10), px(18), px(10));
        b.setTextColor(ink(t.fgBright));
        b.setBackground(new Underline());
        b.setOnClickListener(v -> r.run());
        return b;
    }

    // ---- 5 · sistema: Android settings, themes, transparency, screensaver, info and the v2.10 options ----

    private void buildSys() {
        int had = -1;
        View f = act.getCurrentFocus();
        if (f != null && f.getParent() == sysBody) had = sysBody.indexOfChild(f);
        sysBody.removeAllViews();
        String idle = cfg.screensaverIdleSec <= 0 ? act.getString(R.string.ss_never)
                : cfg.screensaverIdleSec % 60 == 0 ? (cfg.screensaverIdleSec / 60) + " min"
                : String.format(Locale.getDefault(), "%.1f min", cfg.screensaverIdleSec / 60f);
        String on = act.getString(R.string.on), off = act.getString(R.string.off);
        sysRow(R.string.v210_sys_android, null, "android");
        sysRow(R.string.v210_sys_themes, t.name, "themes");
        sysRow(R.string.v210_sys_transparency, cfg.transparency + "%", "layout");
        sysRow(R.string.v210_sys_widgets, null, "widgets");
        sysRow(R.string.v210_arrange, null, "arrange");
        sysRow(R.string.menu_screensaver, null, "screensaver");
        sysRow(R.string.v210_ss_after, idle, "idle");
        sysRow(R.string.v210_logo_anim, cfg.logoAnim ? on : off, "logoAnim");
        sysRow(R.string.v210_text_tiles, cfg.textTiles ? on : off, "textTiles");
        sysRow(R.string.cinemarchy_hub, null, "cinemarchy");
        sysRow(R.string.grok_bot_hub, null, "grok-bot");
        sysRow(R.string.set_hidden, String.valueOf(cfg.hidden.size()), "hidden");
        sysRow(R.string.v210_sys_all, null, "menu");
        sysRow(R.string.menu_about, null, "about");
        if (had >= 0 && had < sysBody.getChildCount()) sysBody.getChildAt(had).requestFocus();
    }

    private void sysRow(int label, String value, String id) {
        FrameLayout r = new FrameLayout(act);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(px(16), px(10), px(16), px(10));
        TextView a = text(act.getString(label), 24, false);
        a.setTextColor(ink(t.fgBright));
        r.addView(a, new LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        TextView b = text(value != null ? value : "›", 24, false);
        b.setTextColor(ink(t.accent));
        r.addView(b, new LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        r.setBackground(new Underline());
        r.setOnClickListener(v -> host.sys(id));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(900), -2);
        l.topMargin = px(4);
        sysBody.addView(r, l);
    }

    // ---- v2.10 "Disponi riquadri": move home panels on a 12x8 grid (fractions of the screen: same at 1080p and 4K) ----
    static final int GRID_X = 12, GRID_Y = 8;
    static final String[] PANEL_IDS = {"term", "weather", "pc", "ai", "deploys", "t3"};
    private View editPanel;
    private String editId;
    private int editCx, editCy, editOldCx, editOldCy;

    View panelView(String id) {
        switch (id) {
            case "term": return term;
            case "weather": return wxBox;
            case "pc": return pcBox;
            case "ai": return aiBoxRef;
            case "deploys": return depBox;
            case "t3": return t3Box;
            default: return null;
        }
    }

    private float cellW() { return getWidth() / (float) GRID_X; }
    private float cellH() { return getHeight() / (float) GRID_Y; }

    void applyPanelPos(LauncherConfig c) {
        if (activeWs != 1 || getWidth() == 0) return;
        // Restore the complete arrangement before checking collisions, including saved swaps.
        for (String id : PANEL_IDS) {
            View v = panelView(id);
            int[] p = c.panelPos.get(id);
            v.setTranslationX(p == null ? 0 : p[0] * cellW());
            v.setTranslationY(p == null ? 0 : p[1] * cellH());
        }
        for (String id : PANEL_IDS) {
            View v = panelView(id);
            int[] p = c.panelPos.get(id);
            if (!v.isShown() || (p == null && !"t3".equals(id))) continue;
            int sx = p == null ? 0 : p[0], sy = p == null ? 0 : p[1];
            int cx = sx, cy = sy;
            if (!fits(v, cx, cy)) {
                cx = cy = 0;
                // Prefer the aligned deck when an old offset no longer fits this layout.
                if (!fits(v, 0, 0)) {
                    int distance = Integer.MAX_VALUE;
                    for (int y = -GRID_Y; y <= GRID_Y; y++) {
                        for (int x = -GRID_X; x <= GRID_X; x++) {
                            int d = Math.abs(x - sx) + Math.abs(y - sy);
                            if (d < distance && fits(v, x, y)) { cx = x; cy = y; distance = d; }
                        }
                    }
                }
            }
            v.setTranslationX(cx * cellW());
            v.setTranslationY(cy * cellH());
        }
    }

    /** View rect in this layout's coordinates, with an offset of (cx, cy) cells instead of its translation. */
    private android.graphics.RectF rectAt(View v, float tx, float ty) {
        float x = v.getLeft() + tx, y = v.getTop() + ty;
        for (android.view.ViewParent p = v.getParent(); p instanceof View && p != this; p = p.getParent()) {
            View pv = (View) p;
            x += pv.getLeft() + (pv == widgets ? 0 : pv.getTranslationX());
            y += pv.getTop() + (pv == widgets ? 0 : pv.getTranslationY());
        }
        return new android.graphics.RectF(x, y, x + v.getWidth(), y + v.getHeight());
    }

    /** True if the panel at (cx, cy) stays on screen, below the bar, above the app row and off every other panel. */
    private boolean fits(View v, int cx, int cy) {
        android.graphics.RectF r = rectAt(v, cx * cellW(), cy * cellH());
        if (r.left < 0 || r.right > getWidth() || r.top < barHeight() || r.bottom > getHeight()) return false;
        if (appWin.getVisibility() == VISIBLE && android.graphics.RectF.intersects(r, rectAt(appWin, 0, 0))) return false;
        for (String id : PANEL_IDS) {
            View o = panelView(id);
            if (o == v || o.getVisibility() != VISIBLE) continue;
            if (android.graphics.RectF.intersects(r, rectAt(o, o.getTranslationX(), o.getTranslationY()))) return false;
        }
        return true;
    }

    boolean editPanel(String id) {
        View v = panelView(id);
        if (v == null || v.getVisibility() != VISIBLE || activeWs != 1) return false;
        editPanel = v;
        editId = id;
        editCx = editOldCx = Math.round(v.getTranslationX() / cellW());
        editCy = editOldCy = Math.round(v.getTranslationY() / cellH());
        v.setAlpha(0.8f);
        v.setFocusable(true);
        v.requestFocus();
        return true;
    }

    private boolean panelKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int dx = 0, dy = 0;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = 1; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                endPanelEdit(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                endPanelEdit(false);
                return true;
            default:
                return true;
        }
        // Collision prevention: skip over cells that would overlap, stop at the edge.
        for (int step = 1; step <= Math.max(GRID_X, GRID_Y); step++) {
            int nx = editCx + dx * step, ny = editCy + dy * step;
            android.graphics.RectF r = rectAt(editPanel, nx * cellW(), ny * cellH());
            if (r.left < 0 || r.right > getWidth() || r.top < barHeight() || r.bottom > getHeight()) break;
            if (fits(editPanel, nx, ny)) {
                editCx = nx;
                editCy = ny;
                editPanel.animate().translationX(nx * cellW()).translationY(ny * cellH()).setDuration(80).start();
                break;
            }
        }
        android.util.Log.i("OmarchyTV", "panel " + editId + " -> " + editCx + "," + editCy);
        return true;
    }

    private void endPanelEdit(boolean save) {
        View v = editPanel;
        editPanel = null;
        v.setAlpha(1f);
        if (save) {
            host.savePanelPos(editId, editCx, editCy);
        } else {
            v.setTranslationX(editOldCx * cellW());
            v.setTranslationY(editOldCy * cellH());
        }
        v.setFocusable(v == wxBox || v == aiBoxRef || v == t3Box);
        focusApp(lastApp);
    }

    int barHeight() {
        return px(64);
    }

    // ---- animated OMARCHY TV logo (v2.10): 1-2 s "decrypt" or "print" when the home opens, then still ----

    private static final String GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#$%&*<>[]{}/\\|=+";
    private long logoStart;
    private boolean logoPrint;
    private final java.util.Random rnd = new java.util.Random();
    private float[] logoAt;

    private final Runnable logoTick = new Runnable() {
        @Override
        public void run() {
            float p = Math.min(1f, (SystemClock.uptimeMillis() - logoStart) / 1500f);
            if (p >= 1f || !isShown() || activeWs != 1) {
                termLogo.setText(logoFinal);
                return;
            }
            termLogo.setText(logoPrint ? printFrame(p) : decryptFrame(p));
            anim.postDelayed(this, 40);
        }
    };

    void startLogo() {
        anim.removeCallbacks(logoTick);
        logoFinal = logoText();
        if (cfg == null || !cfg.logoAnim || activeWs != 1) {
            termLogo.setText(logoFinal);
            return;
        }
        logoPrint = rnd.nextBoolean();
        logoAt = new float[logoFinal.length()];
        for (int i = 0; i < logoAt.length; i++) logoAt[i] = 0.15f + rnd.nextFloat() * 0.8f;
        logoStart = SystemClock.uptimeMillis();
        android.util.Log.i("OmarchyTV", "logo: effetto " + (logoPrint ? "print" : "decrypt"));
        anim.post(logoTick);
    }

    /** No timers while the home is not visible. */
    void stopLogo() {
        anim.removeCallbacks(logoTick);
        if (!logoFinal.isEmpty()) termLogo.setText(logoFinal);
    }

    private String decryptFrame(float p) {
        char[] out = logoFinal.toCharArray();
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            if (c == '\n' || c == ' ') continue;
            if (p < logoAt[i]) out[i] = GLYPHS.charAt(rnd.nextInt(GLYPHS.length()));
        }
        return new String(out);
    }

    private String printFrame(float p) {
        String[] lines = logoFinal.split("\n", -1);
        float pos = p * lines.length;
        int full = (int) pos;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) b.append('\n');
            if (i < full) b.append(lines[i]);
            else if (i == full) {
                int n = Math.round((pos - full) * lines[i].length());
                b.append(lines[i], 0, Math.min(n, lines[i].length()));
                if (n < lines[i].length()) b.append('█');
            }
        }
        return b.toString();
    }

    // ---- D-pad (spec §3.5) ----

    private int zone(View f) {
        for (View v = f; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == bar) return 0;
            if (v == widgets) return 1;
            if (v == t3Box) return 1;
            if (v == row) return 2;
            if (v == grid) return 3;
            if (v == tvWin || v == pcWin || v == sysWin) return 4;
            if (v == this) break;
        }
        return -1;
    }

    /** Called before the default dispatch while the home is shown. True = consumed. */
    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (movingApp >= 0) return moveKey(e);
        if (editPanel != null) return panelKey(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (k == KeyEvent.KEYCODE_C) {
            host.sys("cinemarchy");
            return true;
        }
        View f = act.getCurrentFocus();
        int z = zone(f);
        boolean arrow = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (z == -1) {
            if (arrow) {
                focusDefault();
                return true;
            }
            return false;
        }
        if (z == 0) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (activeWs == 1) {
                    if (!focusNearestWidget(f)) focusApp(lastApp);
                } else restoreFocus();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                boolean r = k == KeyEvent.KEYCODE_DPAD_RIGHT;
                List<View> order = new ArrayList<>();
                order.add(menuBtn);
                for (TextView w : ws) order.add(w);
                order.add(barSpacex);
                order.addAll(barIcons);
                // hidden workspaces (and SpaceX TV when off) are GONE: skip them, or focus gets stuck
                int to = BarFocus.next(order, f, r, v -> v.getVisibility() == VISIBLE && v.isFocusable());
                if (to >= 0) order.get(to).requestFocus();
                else {
                    // Off either end the bar loops. ▶ past the last icon still stops at the floating companion first
                    // when it is on screen; with no companion (or going ◀ off the menu logo) it wraps to the other end.
                    boolean companion = BarFocus.pastEnd(order, f, r, v -> v.getVisibility() == VISIBLE && v.isFocusable()) && host.selectCompanion();
                    if (!companion) {
                        int w = BarFocus.wrap(order, f, r, v -> v.getVisibility() == VISIBLE && v.isFocusable());
                        if (w >= 0) order.get(w).requestFocus();
                    }
                }
                return true;
            }
            return false;
        }
        if (z == 2 || z == 3) {
            RecyclerView list = z == 2 ? row : grid;
            int columns = z == 2 ? rowAdapter.cols : 7;
            int count = z == 2 ? rowAdapter.getItemCount() : gridAdapter.getItemCount();
            int pos = list.getChildAdapterPosition(list.findContainingItemView(f));
            if (pos < 0) return arrow;
            int to = -1;
            if (k == KeyEvent.KEYCODE_DPAD_UP) {
                if (pos >= columns) to = pos - columns;
                else if (z == 2) { if (!focusNearestWidget(f)) activeWsButton().requestFocus(); }
                else { wsFocus.put(3, f); activeWsButton().requestFocus(); }
            } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                int next = Math.min(count - 1, pos + columns);
                if (next / columns > pos / columns) to = next;
            } else if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int next = pos + (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (next >= 0 && next < count && next / columns == pos / columns) to = next;
            } else return false;
            if (to >= 0) { if (z == 2) focusApp(to); else focusGrid(to); }
            return true;
        }
        if (z == 4) {   // 2 / 4 / 5: default focus search inside the window; nothing above = the bar
            if (k == KeyEvent.KEYCODE_DPAD_UP) {
                View nx = f.focusSearch(FOCUS_UP);
                if (nx == null || zone(nx) != 4) {
                    wsFocus.put(activeWs, f);
                    activeWsButton().requestFocus();
                    return true;
                }
            }
            if (arrow) {
                int dir = k == KeyEvent.KEYCODE_DPAD_DOWN ? FOCUS_DOWN : k == KeyEvent.KEYCODE_DPAD_LEFT ? FOCUS_LEFT : k == KeyEvent.KEYCODE_DPAD_RIGHT ? FOCUS_RIGHT : FOCUS_UP;
                View nx = f.focusSearch(dir);
                if (nx != null && zone(nx) == 4) nx.requestFocus();
                return true;
            }
            return false;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (z == 1) activeWsButton().requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (z == 1) {
                    focusApp(lastApp);
                    return true;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                View next = f.focusSearch(k == KeyEvent.KEYCODE_DPAD_RIGHT ? FOCUS_RIGHT : FOCUS_LEFT);
                if (next != null && zone(next) == 1) next.requestFocus();
                return true;
            }
            default:
                return false;
        }
    }

    /** Use screen geometry so user-arranged panels keep the same remote path. Read-only panes are skipped. */
    private boolean focusNearestWidget(View from) {
        int[] location = new int[2];
        if (from != null) from.getLocationOnScreen(location);
        float x = location[0] + (from != null ? from.getWidth() / 2f : 0);
        View nearest = null;
        float distance = Float.MAX_VALUE;
        for (View v : new View[]{t3Box, wxBox, aiBoxRef}) {
            if (!v.isShown() || !v.isFocusable()) continue;
            v.getLocationOnScreen(location);
            float d = Math.abs(location[0] + v.getWidth() / 2f - x);
            if (d < distance) { nearest = v; distance = d; }
        }
        return nearest != null && nearest.requestFocus();
    }

    /** The TV tile is the first item of the home row unless "tv.enabled" is false. */
    boolean tvShown() {
        return cfg == null || cfg.tvEnabled;
    }

    int tvOffset() {
        return tvShown() ? 1 : 0;
    }

    void focusApp(int pos) {
        focusIn(row, rowAdapter, pos);
    }

    void focusGrid(int pos) {
        focusIn(grid, gridAdapter, pos);
    }

    private void focusIn(RecyclerView rv, TileAdapter ad, int pos) {
        int n = ad.getItemCount();
        if (n == 0) {
            activeWsButton().requestFocus();
            return;
        }
        final int p = Math.max(0, Math.min(n - 1, pos));
        RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(p);
        if (h != null && fullyVisible(rv, h.itemView)) {
            h.itemView.requestFocus();
            return;
        }
        rv.scrollToPosition(p);
        rv.post(() -> {
            RecyclerView.ViewHolder h2 = rv.findViewHolderForAdapterPosition(p);
            if (h2 != null) {
                h2.itemView.requestFocus();
                int tp = h2.itemView.getTop(), b = h2.itemView.getBottom(), hh = rv.getHeight();
                if (b > hh) rv.scrollBy(0, b - hh + px(12));
                else if (tp < 0) rv.scrollBy(0, tp - px(12));
            }
        });
    }

    private static boolean fullyVisible(RecyclerView rv, View v) {
        return v.getLeft() >= 0 && v.getRight() <= rv.getWidth() && v.getTop() >= 0 && v.getBottom() <= rv.getHeight();
    }

    View focusedTile() {
        View f = act.getCurrentFocus();
        int z = zone(f);
        return z == 2 || z == 3 ? f : null;
    }

    // ---- move mode (OK lungo → Sposta): ◀▶ (▲▼ in the grid) move the app, OK saves, Back cancels ----

    void startMove(int appPos) {
        movingApp = appPos;
        movingInGrid = activeWs == 3;
        updateRowHeader();
        if (movingInGrid) {
            gridName.setText(act.getString(R.string.move_hint));
            focusGrid(appPos);
        } else focusApp(appPos + tvOffset());
    }

    /** The app grid's key hints; the workspace keys run 1..n, n = the workspaces in the bar. */
    private String gridHintsText() {
        return getContext().getString(R.string.v210_grid_hints, cfg != null ? cfg.workspaceOrder.size() : 5);
    }

    void endMove() {
        movingApp = -1;
        updateRowHeader();
        gridHints.setText(gridHintsText());
    }

    private boolean moveKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int n = apps != null ? apps.getItemCount() : 0;
        int to = -1;
        int columns = movingInGrid ? 7 : rowAdapter.cols;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && movingApp > 0) to = movingApp - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && movingApp < n - 1) to = movingApp + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && movingApp - columns >= 0) to = movingApp - columns;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && movingApp + columns < n) to = movingApp + columns;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) host.finishMove(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down) host.finishMove(false);
                return true;
            default:
                return true;
        }
        if (to >= 0) {
            host.moveApp(movingApp, to);
            movingApp = to;
            rowAdapter.notifyDataSetChanged();
            gridAdapter.notifyDataSetChanged();
            if (movingInGrid) focusGrid(to);
            else focusApp(to + tvOffset());
        }
        return true;
    }

    private void updateRowHeader() {
        appHead.setVisibility(movingApp >= 0 ? VISIBLE : GONE);
        if (t == null) return;
        if (movingApp >= 0 && !movingInGrid) {
            appHints.setText(R.string.move_hint);
            return;
        }
        boolean tvFocused = lastApp == 0 && tvShown();
        appHints.setText(tvFocused ? R.string.v210_tv_hints : R.string.v210_app_hints);
        int count = rowAdapter.getItemCount();
        int pos = Math.max(0, Math.min(lastApp, count - 1));
        String name = tvFocused ? "TV" : apps != null && count > 0 ? apps.labelAt(pos - tvOffset()) : "";
        appName.setText(count > 0 ? name + "  ·  " + (pos + 1) + "/" + count : "");
    }

    /** Home row tiles visible at once: Layout → App size (4..7); 0 (no config yet) gives the v2.9 six. */
    static int rowCols(int columns) {
        return columns <= 0 ? 6 : Math.max(4, Math.min(7, columns));
    }

    /** 16:9 tile width that fits cols tiles and cols - 1 gaps in inner px. */
    static int tileW(int inner, int gap, int cols) {
        return (inner - gap * (cols - 1)) / cols;
    }

    // ---- tiles: home row (TV + apps, App size per row, 16:9) and the 7-per-row grid of workspace 3 ----
    // Focus uses scale plus a wide accent border and a contrasting outer outline.

    private final class TileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final boolean tvFirst;
        int cols;
        private final RecyclerView rv;

        TileAdapter(boolean tvFirst, int cols, RecyclerView rv) {
            this.tvFirst = tvFirst;
            this.cols = cols;
            this.rv = rv;
        }

        /** Home row with the TV tile shown ("tv.enabled": false hides it). */
        boolean tvOn() {
            return tvFirst && tvShown();
        }

        int appIndex(int pos) {
            return tvOn() ? pos - 1 : pos;
        }

        private AppAdapter data() { return tvFirst ? apps : catalog; }

        @Override
        public int getItemCount() {
            return (data() != null ? data().getItemCount() : 0) + (tvOn() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            return tvOn() && position == 0 ? 1 : 0;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout f = new FrameLayout(act);
            f.setFocusable(true);
            f.setClickable(true);
            if (viewType == 1) {   // TV tile: line TV icon (accent, ~56 px) over "TV" mono bold 28 px
                LinearLayout col = vbox();
                col.setGravity(Gravity.CENTER);
                ImageView iv = new ImageView(act);
                iv.setImageResource(R.drawable.ln_monitor);
                col.addView(iv, new LinearLayout.LayoutParams(px(56), px(56)));
                TextView label = text("TV", 24, true);
                col.addView(label);
                f.addView(col, new LayoutParams(-1, -1));
            } else {
                ImageView iv = new ImageView(act);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                f.addView(iv, new LayoutParams(-1, -1));
                TextView tv = text("", 24, false);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(px(10), 0, px(10), 0);
                tv.setMaxLines(2);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                f.addView(tv, new LayoutParams(-1, -1));
            }
            f.setForeground(new FocusRing());
            return new RecyclerView.ViewHolder(f) {
            };
        }

        private int inner() {
            return rv.getWidth() > 0 ? rv.getWidth() - rv.getPaddingLeft() - rv.getPaddingRight()
                    : Math.round(1920 * s) - px(64) - px(56) - px(4) - (tvFirst ? px(20) : px(16));
        }

        /** Uncaptioned 16:9 artwork; the selected app is named in the window header. */
        int tileH(int n) {
            int width = tileW(inner() - px(20), px(20), n);
            if (tvFirst) width = Math.min(width, px(360));   // two Large rows must clear the widget deck
            return width * 9 / 16;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            AppAdapter apps = data();
            FrameLayout f = (FrameLayout) h.itemView;
            int inner = inner();
            int gap = px(20);
            int w = tileW(inner - gap, gap, cols);
            if (tvFirst) w = Math.min(w, px(360));
            int bannerHeight = w * 9 / 16;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, bannerHeight);
            lp.leftMargin = (inner / cols - w) / 2;
            lp.rightMargin = inner / cols - w - lp.leftMargin;
            lp.bottomMargin = gap;
            f.setLayoutParams(lp);
            boolean tvTile = tvOn() && pos == 0;
            final int app = appIndex(pos);
            View art = f.getChildAt(0);
            art.setLayoutParams(new LayoutParams(-1, bannerHeight));
            f.setContentDescription(tvTile ? "TV" : apps.labelAt(app));
            if (tvTile) {
                LinearLayout col = (LinearLayout) f.getChildAt(0);
                ((ImageView) col.getChildAt(0)).setColorFilter(t.accent);
                ((TextView) col.getChildAt(1)).setTextColor(ink(t.fgBright, tileBackground()));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(tileBackground());
                f.setBackground(bg);
            } else {
                ImageView iv = (ImageView) f.getChildAt(0);
                TextView tv = (TextView) f.getChildAt(1);
                Drawable banner = cfg.textTiles ? null : apps.bannerAt(app);
                iv.setImageDrawable(banner);
                iv.setVisibility(banner != null ? VISIBLE : GONE);
                iv.setPadding(px(2), px(2), px(2), px(2));
                tv.setText(apps.labelAt(app));
                tv.setVisibility(banner != null ? GONE : VISIBLE);
                tv.setLayoutParams(new LayoutParams(-1, -1));
                tv.setTextColor(ink(cfg.textTiles ? t.fg : t.fgBright, tileBackground()));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(tileBackground());
                f.setBackground(bg);
            }
            f.setAlpha(movingApp >= 0 && app == movingApp && (movingInGrid != tvFirst) ? 0.85f : 1f);
            styleTile(f, f.isFocused(), false);
            f.setOnFocusChangeListener((v, has) -> {
                styleTile((FrameLayout) v, has, true);
                if (has) {
                    int p = rv.getChildAdapterPosition(v);
                    if (p < 0) return;
                    if (tvFirst) {
                        lastApp = p;
                        if (movingApp < 0) {
                            appName.setText(tvOn() && p == 0 ? "TV" : apps.labelAt(appIndex(p)));
                            updateRowHeader();
                        }
                    } else if (movingApp < 0) gridName.setText(apps.labelAt(p));
                }
            });
            f.setOnClickListener(v -> {
                int p = rv.getChildAdapterPosition(v);
                if (p < 0) return;
                if (tvOn() && p == 0) host.openTv();
                else host.launchApp(apps, appIndex(p));
            });
            f.setOnLongClickListener(v -> {
                int p = rv.getChildAdapterPosition(v);
                if (p < 0) return true;
                if (tvOn() && p == 0) host.openWalker();   // the TV tile cannot be moved or hidden
                else host.tileMenu(apps, appIndex(p), v);
                return true;
            });
            if (tvFirst && pos == lastApp && appName.getText().length() == 0) appName.setText(tvTile ? "TV" : apps.labelAt(app));
        }
    }

    private void styleTile(FrameLayout f, boolean focused, boolean animate) {
        Drawable d = f.getForeground();
        if (d instanceof FocusRing) ((FocusRing) d).set(focused);
        View label = f.getChildAt(f.getChildCount() - 1);
        if (label instanceof TextView) {
            ((TextView) label).setTypeface(focused ? tfBold : tf);
            ((TextView) label).setTextColor(ink(focused ? t.fgBright : t.fg, tileBackground()));
        }
        float sc = focused ? 1.05f : 1f;
        f.setZ(focused ? 2f : 0f);
        if (animate) f.animate().scaleX(sc).scaleY(sc).setDuration(110).start();
        else {
            f.setScaleX(sc);
            f.setScaleY(sc);
        }
    }

    /** A contrasting outline keeps the accent focus border visible on both dark and light banners. */
    private final class FocusRing extends Drawable {
        private final Paint p = new Paint();
        private boolean on;

        FocusRing() {
            p.setStyle(Paint.Style.STROKE);
        }

        void set(boolean focused) {
            if (on != focused) {
                on = focused;
                invalidateSelf();
            }
        }

        @Override
        public void draw(@NonNull android.graphics.Canvas c) {
            if (t == null) return;
            android.graphics.Rect b = getBounds();
            if (!on) {
                float w = px(2);
                p.setStrokeWidth(w);
                p.setColor(t.muted);
                c.drawRect(b.left + w / 2, b.top + w / 2, b.right - w / 2, b.bottom - w / 2, p);
                return;
            }
            float o = px(3), a = px(6);
            p.setStrokeWidth(o);
            p.setColor(ink(t.fgBright));
            c.drawRect(b.left + o / 2, b.top + o / 2, b.right - o / 2, b.bottom - o / 2, p);
            p.setStrokeWidth(a);
            p.setColor(t.accent);
            c.drawRect(b.left + o + a / 2, b.top + o + a / 2, b.right - o - a / 2, b.bottom - o - a / 2, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Progress bar: track = selection, fill = given colour, square ends. */
    static final class BarView extends View {
        private final Paint tr = new Paint(), fl = new Paint();
        private final double f;

        BarView(Context c, int track, int fill, double f) {
            super(c);
            tr.setColor(track);
            fl.setColor(fill);
            this.f = Math.max(0, Math.min(1, f));
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), tr);
            c.drawRect(0, 0, (float) (getWidth() * f), getHeight(), fl);
        }
    }
}
