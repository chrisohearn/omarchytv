package com.omarchytv.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * v2.13 Grok Bot Companion (TV) session: a chat panel opened from the floating companion (the Blue key, OK on
 * it after long-press Menu, or the home tile). Type with the on-screen or a Bluetooth/USB keyboard, or speak through
 * the system recognizer (the remote's mic on Android TV). With @tcballard's Grok Bot Desktop Companion on
 * a linked PC (omarchy-link) the question goes to it. A named PC companion stays selected while offline.
 * Without a configured PC companion, questions go to OmarchyTV's own Ask (the AI provider
 * chosen there: Grok Bot, Claude Code, Codex, Hermes…). "open Netflix" / "apri YouTube" opens an installed app
 * first (CompanionCommands). Links in a reply become buttons: YouTube ones play in the YouTube app, others open
 * in the WebApp, only after OK on the button. Each bot keeps its last 30 lines. Built against @tcballard's
 * Grok Bot Desktop Companion v0.1.0 (MIT, commit a2efb00); the code here is mine.
 */
public class CompanionChatActivity extends Activity implements OmarchyLink.Listener {
    private static final int REQ_VOICE = 41;
    private LinearLayout log;
    private ScrollView scroll;
    private EditText input;
    private TextView mic;
    private SpeechRecognizer speech;
    private TextToSpeech tts;
    private TextView voiceOutput;
    private boolean ttsReady, ttsFailed, voiceFailureShown;
    private String queuedSpeech, speakingId;
    private boolean voiceOnResume;
    private boolean resumed;
    private final Runnable speechTimeout = () -> {
        if (speech == null) return;
        stopVoice();
        line("OmarchyTV", "Speech recognition timed out. Press Speak to try again.", false);
    };
    private GrokbotMark mark;
    private String pendingId;
    private String botName = "Grok Bot";
    private static final int HISTORY = 30;
    private static final long REQUEST_MS = 190_000;
    private static final String REQUEST = "pending:id", REQUEST_BOT = "pending:bot",
            REQUEST_AT = "pending:at", REQUEST_TEXT = "pending:text", RECORDED = "reply:id";
    private final Runnable requestTimeout = this::restoreConversation;
    private Theme t;
    private Typeface mono;
    /** True while the chat is on screen: replies then land here, not in the unread count. */
    static volatile boolean visible;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = Theme.cur();
        try {
            mono = CastUi.font(this);
        } catch (Exception e) {
            mono = Typeface.MONOSPACE;
        }
        LauncherConfig cfg = LauncherConfig.get(this);
        if (cfg.grokbotBotName != null && !cfg.grokbotBotName.isEmpty()) botName = cfg.grokbotBotName;

        FrameLayout shade = new FrameLayout(this);
        shade.setBackgroundColor(0xB0000000);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.withAlpha(t.bg, 0.97f));
        bg.setStroke(dp(2), t.activeBorder);
        bg.setCornerRadius(dp(6));
        panel.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        mark = new GrokbotMark(this);
        CompanionOverlay.paint(mark, cfg);
        head.addView(mark, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = text(botName + " · Companion (TV)", 16, t.fgBright, true);
        title.setPadding(dp(12), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        voiceOutput = button(voiceLabel());
        voiceOutput.setOnClickListener(v -> {
            GrokbotDirectActivity.prefs(this).edit().putBoolean("speak", !GrokbotDirectActivity.speak(this)).apply();
            stopSpeaking();
        });
        head.addView(voiceOutput);
        panel.addView(head);
        TextView via = text(routeNote(), 11, t.fgDim, false);
        via.setPadding(0, dp(6), 0, dp(10));
        panel.addView(via);

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        log = new LinearLayout(this);
        log.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(log);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Ask " + botName + "…  (OK / Enter to send)");
        input.setTextColor(t.fgBright);
        input.setHintTextColor(t.fgDim);
        input.setTypeface(mono);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_ACTION_DONE || enter) {
                send(input.getText().toString());
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mic = button("🎤 Speak");
        mic.setOnClickListener(v -> voice());
        row.addView(mic);
        TextView sendBtn = button("Send ›");
        sendBtn.setOnClickListener(v -> send(input.getText().toString()));
        row.addView(sendBtn);
        panel.addView(row);
        TextView clear = button("Clear");
        clear.setOnClickListener(v -> {
            prefs().edit().remove(historyKey()).apply();
            restoreConversation();
        });
        row.addView(clear);
        TextView foot = text("Press the remote microphone to speak. Replies use the TV voice; select Voice to mute.", 10, t.fgDim, false);
        foot.setPadding(0, dp(8), 0, 0);
        panel.addView(foot);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(720), dp(460), Gravity.CENTER);
        shade.addView(panel, lp);
        setContentView(shade);
        initSpeechOutput();

        input.setText(prefs().getString("draft:" + botName, ""));
        String q = getIntent().getStringExtra("q");
        if (q != null && !q.trim().isEmpty()) send(q);
        voiceOnResume = getIntent().getBooleanExtra("voice", false);
        mic.requestFocus(); // soft keyboard opens only when the user selects the text field
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        int key = e.getKeyCode();
        if (key == KeyEvent.KEYCODE_SEARCH || key == KeyEvent.KEYCODE_VOICE_ASSIST || key == KeyEvent.KEYCODE_ASSIST) {
            if (e.getAction() == KeyEvent.ACTION_UP) voice();
            return true;
        }
        // Physical/Bluetooth keyboard: typing while focus is elsewhere goes straight into the field.
        if (input != null && !input.hasFocus() && e.getAction() == android.view.KeyEvent.ACTION_DOWN
                && e.isPrintingKey() && e.getUnicodeChar() != 0) {
            input.requestFocus();
            input.append(String.valueOf((char) e.getUnicodeChar()));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private String route() {
        LauncherConfig cfg = LauncherConfig.get(this);
        if ("ask".equals(cfg.companionAgent)) return "ask";   // Settings → Agent: OmarchyTV's Ask (Claude, Codex, Hermes…)
        return CompanionPlacement.route(cfg.grokbotBotName, OmarchyLink.hasPc(), true);
    }

    private String routeNote() {
        String destination = route();
        if ("companion".equals(destination)) return "Answers come from " + botName + " through your PC companion (omarchy-link).";
        if ("offline".equals(destination)) return botName + " needs the PC connection. Questions stay here until it reconnects.";
        return "Questions go to OmarchyTV's Ask.";
    }

    private void send(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        if (speech != null) stopVoice();
        if (q.length() > 240) q = q.substring(0, 240);
        String destination = route();
        if ("companion".equals(destination) && activeRequest(this) != null) {
            input.setText(q);
            prefs().edit().putString("draft:" + botName, q).apply();
            line("OmarchyTV", "Still waiting for the previous reply. Your new question is kept in the input field.", false);
            return;
        }
        input.setText("");
        prefs().edit().remove("draft:" + botName).apply();
        line("You", q, true);
        if (launchByName(CompanionCommands.launchQuery(q))) return;
        if ("companion".equals(destination)) {
            pendingId = UUID.randomUUID().toString();
            prefs().edit().putString(REQUEST, pendingId).putString(REQUEST_BOT, botName)
                    .putString(REQUEST_TEXT, q).putLong(REQUEST_AT, android.os.SystemClock.elapsedRealtime()).apply();
            restoreConversation();
            mark.pose("think");
            OmarchyLink.grokbotAsk(pendingId, q, null);
        } else if ("offline".equals(destination)) {
            input.setText(q);
            prefs().edit().putString("draft:" + botName, q).apply();
            line("OmarchyTV", botName + " is offline. Reconnect the PC and send again.", false);
        } else {
            line("OmarchyTV", "Opening Ask with your question…", false);
            startActivity(new Intent(this, AskActivity.class).putExtra("cmd", q));
        }
    }

    private void voice() {
        if (!resumed || isFinishing()) return;
        stopSpeaking();
        if (speech != null) {
            stopVoice();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_VOICE);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            line("OmarchyTV", "No speech recognizer on this TV. Type your question instead.", false);
            return;
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, MainActivity.speechLanguage(this))
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName())
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask " + botName);
        try {
            // Call the system service from our process. Some TV recognizer activities call their own
            // service in-process, which Android 11 rejects in its calling-permission check.
            SpeechRecognizer current = SpeechRecognizer.createSpeechRecognizer(this);
            speech = current;
            current.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    if (speech == current) mic.setText("Listening… OK to cancel");
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    if (speech == current) mic.setText("Recognizing… OK to cancel");
                }
                @Override public void onError(int error) {
                    if (speech != current) return;
                    stopVoice();
                    String message = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            ? "Microphone access was denied. Check OmarchyTV's microphone permission."
                            : error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ? "No speech heard. Press Speak to try again."
                            : "Speech recognition failed (" + error + "). Press Speak to try again.";
                    line("OmarchyTV", message, false);
                }
                @Override public void onResults(Bundle results) {
                    if (speech != current) return;
                    List<String> words = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    stopVoice();
                    if (!resumed) return;
                    if (words != null && !words.isEmpty() && words.get(0) != null && !words.get(0).trim().isEmpty()) send(words.get(0));
                    else line("OmarchyTV", "No speech heard. Press Speak to try again.", false);
                }
                @Override public void onPartialResults(Bundle results) { }
                @Override public void onEvent(int type, Bundle params) { }
            });
            mic.setText("Starting microphone… OK to cancel");
            mic.postDelayed(speechTimeout, 30_000);
            current.startListening(i);
        } catch (RuntimeException e) {
            stopVoice();
            line("OmarchyTV", "Could not start speech recognition. Type your question instead.", false);
        }
    }

    private void stopVoice() {
        mic.removeCallbacks(speechTimeout);
        SpeechRecognizer current = speech;
        speech = null; // Ignore callbacks caused by cancellation or destruction.
        if (current != null) {
            try { current.cancel(); } finally { current.destroy(); }
        }
        mic.setText("🎤 Speak");
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != REQ_VOICE) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            if (resumed) mic.post(this::voice);
            else voiceOnResume = true;
        } else {
            line("OmarchyTV", "Microphone access was not allowed. You can still type your question.", false);
        }
    }

    @Override
    public void onLinkEvent(String type, JSONObject d) {
        if (!"grokbot_answer".equals(type) && !"grokbot_error".equals(type)) return;
        runOnUiThread(() -> {
            if (pendingId == null || !pendingId.equals(d.optString("id"))) return;
            pendingId = null;
            restoreConversation();
            if (input.getText().length() == 0) input.setText(prefs().getString("draft:" + botName, ""));
            mark.pose("grokbot_answer".equals(type) ? "happy" : "alert");
            mark.postDelayed(() -> mark.pose("idle"), 2500);
            if ("grokbot_answer".equals(type)) {
                String text = d.optString("text", "");
                linkButtons(d.optJSONArray("links"), text);
                String mode = d.optString("mode");
                if (!"sent".equals(mode) && !"ipc".equals(mode)) speakReply(text);
                mark.blink(false);
                mark.postDelayed(() -> mark.blink(true), 160);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        visible = true;
        voiceOutput.setText(voiceLabel());
        restoreConversation();
        markRead(this, LauncherConfig.get(this));
        OmarchyLink.addListener(this);
        if (voiceOnResume) {
            voiceOnResume = false;
            mic.post(this::voice);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        visible = false;
        stopVoice();
        stopSpeaking();
        mic.removeCallbacks(requestTimeout);
        prefs().edit().putString("draft:" + botName, input.getText().toString()).apply();
        OmarchyLink.removeListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopVoice();
        stopSpeaking();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }

    private String voiceLabel() {
        return "Voice: " + (GrokbotDirectActivity.speak(this) ? "on" : "off");
    }

    private void initSpeechOutput() {
        tts = new TextToSpeech(this, status -> {
            if (tts == null || isFinishing() || isDestroyed()) return;
            int language = status == TextToSpeech.SUCCESS
                    ? tts.setLanguage(Locale.forLanguageTag(MainActivity.speechLanguage(this)))
                    : TextToSpeech.LANG_NOT_SUPPORTED;
            // Use an installed voice so reading a private companion reply adds no cloud speech provider.
            ttsReady = false;
            if (language >= TextToSpeech.LANG_AVAILABLE && tts.getVoices() != null) {
                String wanted = Locale.forLanguageTag(MainActivity.speechLanguage(this)).getLanguage();
                for (android.speech.tts.Voice voice : tts.getVoices()) {
                    if (!voice.isNetworkConnectionRequired() && wanted.equals(voice.getLocale().getLanguage())
                            && tts.setVoice(voice) == TextToSpeech.SUCCESS) {
                        ttsReady = true;
                        break;
                    }
                }
            }
            ttsFailed = !ttsReady;
            if (queuedSpeech != null) speakReply(queuedSpeech);
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                runOnUiThread(() -> {
                    if (id.equals(speakingId) && resumed) voiceOutput.setText("Speaking… Voice to mute");
                });
                android.util.Log.i("CompanionVoice", "Started reply " + id);
            }
            @Override public void onDone(String id) { completed(id, false); }
            @Override public void onError(String id) { completed(id, true); }
            private void completed(String id, boolean failed) {
                android.util.Log.i("CompanionVoice", (failed ? "Failed reply " : "Finished reply ") + id);
                runOnUiThread(() -> {
                    if (!id.equals(speakingId)) return;
                    speakingId = null;
                    voiceOutput.setText(voiceLabel());
                    if (failed) voiceUnavailable();
                });
            }
        });
    }

    private void speakReply(String text) {
        if (!resumed || !GrokbotDirectActivity.speak(this) || text == null || text.isEmpty()) return;
        queuedSpeech = text;
        if (ttsFailed) {
            queuedSpeech = null;
            voiceUnavailable();
        } else if (ttsReady && tts != null) {
            queuedSpeech = null;
            speakingId = UUID.randomUUID().toString();
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, speakingId) == TextToSpeech.ERROR) {
                speakingId = null;
                voiceUnavailable();
            }
        }
    }

    private void stopSpeaking() {
        queuedSpeech = speakingId = null;
        if (tts != null) tts.stop();
        if (voiceOutput != null) voiceOutput.setText(voiceLabel());
    }

    private void voiceUnavailable() {
        if (!resumed || voiceFailureShown) return;
        voiceFailureShown = true;
        line("OmarchyTV", "TV voice is unavailable. Check Android Settings → Text-to-speech; your reply stays in the chat.", false);
    }

    private void line(String who, String msg, boolean me) {
        line(who, msg, me, true);
    }

    private void line(String who, String msg, boolean me, boolean keep) {
        TextView v = text(who + ": " + msg, 13, me ? t.cyan : t.fgBright, false);
        v.setPadding(0, dp(3), 0, dp(3));
        log.addView(v);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        if (keep) remember(who, msg, me);
    }

    // ---- local app launching ("open Netflix", "apri YouTube") ----

    /** Opens the installed app that best matches the spoken name; false when none does (the agent answers). */
    private boolean launchByName(String query) {
        if (query == null || query.isEmpty()) return false;
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> r = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            if (r != null) found.addAll(r);
        }
        List<String[]> names = new ArrayList<>();
        List<ResolveInfo> infos = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            names.add(new String[]{String.valueOf(ri.loadLabel(pm)), ri.activityInfo.packageName});
            infos.add(ri);
        }
        int k = CompanionCommands.matchApp(query, names);
        if (k < 0) return false;
        ResolveInfo ri = infos.get(k);
        Intent i = pm.getLeanbackLaunchIntentForPackage(ri.activityInfo.packageName);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            line(botName, "Opening " + names.get(k)[0] + "…", false);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ---- links in a reply: a button each, opened only with OK on it ----

    private void linkButtons(JSONArray fromPc, String text) {
        List<String> urls = new ArrayList<>();
        if (fromPc != null) {
            for (int i = 0; i < fromPc.length() && urls.size() < 6; i++) {
                JSONObject l = fromPc.optJSONObject(i);
                String u = l != null ? l.optString("url", "") : "";
                if (u.startsWith("https://") || u.startsWith("http://")) urls.add(u);
            }
        }
        if (urls.isEmpty()) urls = CompanionCommands.links(text);
        for (String u : urls) {
            final String url = u;
            final String id = CompanionCommands.youtubeId(url);
            String shown = url.replaceFirst("^https?://", "");
            if (shown.length() > 48) shown = shown.substring(0, 48) + "…";
            TextView b = button((DhhFeed.validId(id) ? "▶ Play on YouTube · " : "🔗 Open · ") + shown);
            b.setOnClickListener(v -> {
                if (DhhFeed.validId(id)) YoutubeEmbedActivity.openInYoutubeApp(this, id, null);
                else WebAppActivity.openUrl(this, "Link", url);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            log.addView(b, lp);
            if (urls.indexOf(u) == 0) b.requestFocus();
        }
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ---- per-bot history (the last 30 lines) ----

    private SharedPreferences prefs() {
        return prefs(this);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("grokbot_companion", MODE_PRIVATE);
    }

    private String historyKey() {
        return "chat:" + botName;
    }

    /** Shared across recreated chat screens; elapsed time also detects a rebooted, stale request. */
    private static String activeRequest(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString(REQUEST, null);
        if (id == null) return null;
        long age = android.os.SystemClock.elapsedRealtime() - p.getLong(REQUEST_AT, 0);
        if (age >= 0 && age < REQUEST_MS) return id;
        String bot = p.getString(REQUEST_BOT, "Grok Bot");
        remember(p, "chat:" + bot, "OmarchyTV", "The reply timed out. Check ChatGPT before sending again; no automatic resend.", false);
        p.edit().remove(REQUEST).putString(RECORDED, id).apply();
        return null;
    }

    /** Called by the Link runtime before UI dispatch, including when no chat screen is listening. */
    static void recordReply(Context c, String type, JSONObject data) {
        if (!"grokbot_answer".equals(type) && !"grokbot_error".equals(type)) return;
        String id = activeRequest(c);
        if (id == null || !id.equals(data.optString("id"))) return;
        SharedPreferences p = prefs(c);
        String bot = p.getString(REQUEST_BOT, "Grok Bot");
        String who = "OmarchyTV", body;
        if ("grokbot_answer".equals(type)) {
            who = data.optString("bot", bot);
            String text = data.optString("text", ""), mode = data.optString("mode");
            body = "sent".equals(mode) ? "Sent ✓ The answer appears on the PC companion (its webhook replies there)."
                    : "ipc".equals(mode) || text.isEmpty()
                    ? "Sent to the companion on the PC; the reply is in its desktop bubble (IPC-only mode)." : text;
        } else {
            String code = data.optString("error", "failed");
            body = "not_connected".equals(code) ? "No PC connected over omarchy-link."
                    : "busy".equals(code) ? "The companion is busy. Your question is kept for a manual retry."
                    : data.optString("message", code);
            if (p.getString("draft:" + bot, "").isEmpty())
                p.edit().putString("draft:" + bot, p.getString(REQUEST_TEXT, "")).apply();
        }
        remember(p, "chat:" + bot, who, body, false);
        SharedPreferences.Editor edit = p.edit().remove(REQUEST).putString(RECORDED, id);
        if (!visible) edit.putInt("unread:" + bot, Math.min(99, p.getInt("unread:" + bot, 0) + 1));
        edit.apply();
    }

    static boolean recordedReply(Context c, String id) {
        return id != null && !id.isEmpty() && id.equals(prefs(c).getString(RECORDED, ""));
    }

    private void restoreConversation() {
        mic.removeCallbacks(requestTimeout);
        String active = activeRequest(this);
        pendingId = botName.equals(prefs().getString(REQUEST_BOT, "")) ? active : null;
        log.removeAllViews();
        restoreHistory();
        if (pendingId != null) {
            line(botName, "Waiting for the reply…", false, false);
            long age = android.os.SystemClock.elapsedRealtime() - prefs().getLong(REQUEST_AT, 0);
            mic.postDelayed(requestTimeout, Math.max(1, REQUEST_MS - age));
        }
    }

    /** The bot name the chat files history and unread under. */
    static String botName(LauncherConfig cfg) {
        return cfg.grokbotBotName != null && !cfg.grokbotBotName.isEmpty() ? cfg.grokbotBotName : "Grok Bot";
    }

    private void remember(String who, String msg, boolean me) {
        remember(prefs(), historyKey(), who, msg, me);
    }

    private static void remember(SharedPreferences p, String key, String who, String msg, boolean me) {
        try {
            JSONArray a = new JSONArray(p.getString(key, "[]"));
            a.put(new JSONObject().put("who", who).put("msg", msg.length() > 4000 ? msg.substring(0, 4000) : msg).put("me", me));
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, a.length() - HISTORY); i < a.length(); i++) keep.put(a.get(i));
            p.edit().putString(key, keep.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---- unread (the dot on the floating companion) ----

    /** A reply that arrived while the chat was closed: kept in the bot's history and counted as unread. */
    static void deliver(Context c, LauncherConfig cfg, String who, String msg) {
        if (msg == null || msg.isEmpty()) return;
        String bot = botName(cfg);
        SharedPreferences p = prefs(c);
        remember(p, "chat:" + bot, who == null || who.isEmpty() ? bot : who, msg, false);
        p.edit().putInt("unread:" + bot, Math.min(99, p.getInt("unread:" + bot, 0) + 1)).apply();
    }

    /** A new desktop bubble line from the PC companion counts once as unread. */
    static void noteBubble(Context c, LauncherConfig cfg) {
        String m = cfg.grokbotMessage;
        if (visible || m == null || m.isEmpty()) return;
        String bot = botName(cfg);
        SharedPreferences p = prefs(c);
        if (m.equals(p.getString("seen:" + bot, ""))) return;
        p.edit().putString("seen:" + bot, m).putInt("unread:" + bot, Math.min(99, p.getInt("unread:" + bot, 0) + 1)).apply();
    }

    static int unread(Context c, LauncherConfig cfg) {
        return prefs(c).getInt("unread:" + botName(cfg), 0);
    }

    static void markRead(Context c, LauncherConfig cfg) {
        String bot = botName(cfg);
        String m = cfg.grokbotMessage == null ? "" : cfg.grokbotMessage;
        prefs(c).edit().putInt("unread:" + bot, 0).putString("seen:" + bot, m).apply();
    }

    private void restoreHistory() {
        try {
            JSONArray a = new JSONArray(prefs().getString(historyKey(), "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                line(o.optString("who"), o.optString("msg"), o.optBoolean("me"), false);
            }
        } catch (Exception ignored) {
        }
    }

    private TextView button(String s) {
        TextView v = text(s, 13, t.fg, true);
        v.setFocusable(true);
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        v.setLayoutParams(lp);
        style(v, false);
        v.setOnFocusChangeListener((x, f) -> style((TextView) x, f));
        return v;
    }

    private void style(TextView v, boolean f) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(f ? t.selection : Theme.withAlpha(t.bgDarker, 0.8f));
        g.setStroke(dp(f ? 2 : 1), f ? t.activeBorder : t.inactiveBorder);
        v.setBackground(g);
        v.setTextColor(f ? t.fgBright : t.fg);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(mono, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
