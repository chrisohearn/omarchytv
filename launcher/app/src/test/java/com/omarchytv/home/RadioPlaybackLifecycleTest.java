package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import androidx.media3.common.FlagSet;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.omarchytv.home.lights.Lights;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = RadioPlaybackLifecycleTest.NoBackgroundWork.class)
public class RadioPlaybackLifecycleTest {
    private static final RadioCatalog.Track TRACK = new RadioCatalog.Track("song", "Synthetic", "",
            "file:///synthetic.mp3", "", false, "", 0, "", "", "", "");

    private final List<SyntheticPlayer> players = new ArrayList<>();
    private Application app;
    private RadioPlayer radio;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        radio = new RadioPlayer(app, owner -> {
            SyntheticPlayer player = new SyntheticPlayer();
            players.add(player);
            return player.exo;
        });
        ReflectionHelpers.setStaticField(RadioPlayer.class, "inst", radio);
        radio.prefs().edit().putString("animated", "off").apply();
    }

    @After
    public void stop() {
        radio.stop();
        ReflectionHelpers.setStaticField(RadioPlayer.class, "inst", null);
    }

    @Test
    public void pauseStopsBothTicksAndEarlyResumeKeepsPlayer() {
        try (ActivityController<RadioActivity> activity = openRadio()) {
            SyntheticPlayer first = play();
            assertTrue(tickQueued(radio));
            assertTrue(tickQueued(activity.get()));

            radio.toggle();
            assertFalse(tickQueued(radio));
            assertFalse(tickQueued(activity.get()));
            idle(29);
            assertFalse(first.released);
            assertNull(shadowOf(app).getNextStoppedService());

            radio.toggle();
            idle(2);
            assertEquals(1, players.size());
            assertTrue(tickQueued(radio));
            assertTrue(tickQueued(activity.get()));
            assertTrue(RadioPlayer.active());
        }
    }

    @Test
    public void pausedPlayerReleasesAndReopeningCanResumeAtTheSamePosition() {
        SyntheticPlayer first = play();
        first.position = 21_234;
        radio.pause();
        idle(30);
        assertTrue(first.released);
        assertStoppedService();
        assertFalse(tickQueued(radio));
        assertEquals(21_234, radio.position());
        assertEquals(120_000, radio.duration());
        assertNotNull(radio.token());

        try (ActivityController<RadioActivity> activity = openRadio()) {
            assertFalse(tickQueued(activity.get()));
            assertEquals(1, players.size());
            assertNull(shadowOf(app).getNextStartedService());
            assertTrue(activity.get().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)));
            assertEquals(2, players.size());
            assertEquals(21_234, players.get(1).position);
            assertStartedService();
            assertTrue(tickQueued(activity.get()));
        }
    }

    @Test
    public void focusSuppressionAllowsQuickRecoveryOrReleasesAfterGrace() {
        SyntheticPlayer first = play();
        first.focusLost(true);
        assertFalse(tickQueued(radio));
        first.focusLost(false);
        idle(31);
        assertFalse(first.released);
        assertTrue(tickQueued(radio));

        first.position = 12_000;
        first.focusLost(true);
        idle(30);
        assertTrue(first.released);
        assertStoppedService();
        // The media button path works without reopening the activity.
        assertTrue(radio.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY));
        assertEquals(2, players.size());
        assertEquals(12_000, players.get(1).position);
        assertStartedService();
    }

    @Test
    public void leavingActivityKeepsActivePlaybackAndStopReleasesImmediately() {
        SyntheticPlayer first;
        try (ActivityController<RadioActivity> activity = openRadio()) {
            first = play();
        }
        idle(31);
        assertFalse(first.released);
        assertTrue(tickQueued(radio));
        assertNull(shadowOf(app).getNextStoppedService());

        radio.stop();
        assertTrue(first.released);
        assertStoppedService();
        assertFalse(tickQueued(radio));
        assertNull(radio.token());
        idle(31);
        assertEquals(1, players.size());
    }

    @Test
    public void pauseDuringBufferingStillReleases() {
        SyntheticPlayer first = play();
        first.state = Player.STATE_BUFFERING;
        first.fire(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED);
        idle(31);
        assertFalse(first.released);
        assertFalse(tickQueued(radio));

        radio.pause();
        idle(30);
        assertTrue(first.released);
        assertStoppedService();
        assertSame(TRACK, radio.current());
    }

    private ActivityController<RadioActivity> openRadio() {
        // Verify the worker shadow before creating an activity that would fetch the remote catalogue.
        NoBackgroundWork.calls = 0;
        Lights.run(() -> {});
        assertEquals(1, NoBackgroundWork.calls);
        return Robolectric.buildActivity(RadioActivity.class).setup();
    }

    private SyntheticPlayer play() {
        radio.play(List.of(TRACK), 0);
        assertStartedService();
        return players.get(0);
    }

    private void assertStartedService() {
        assertRadioService(shadowOf(app).getNextStartedService());
    }

    private void assertStoppedService() {
        assertRadioService(shadowOf(app).getNextStoppedService());
    }

    private static void assertRadioService(Intent intent) {
        assertNotNull(intent);
        assertEquals(RadioService.class.getName(), intent.getComponent().getClassName());
    }

    private static void idle(long seconds) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds));
    }

    private static boolean tickQueued(Object owner) {
        Handler handler = ReflectionHelpers.getField(owner, "h");
        Runnable tick = ReflectionHelpers.getField(owner, "tick");
        return handler.hasCallbacks(tick);
    }

    @Implements(Lights.class)
    public static class NoBackgroundWork {
        static int calls;

        @Implementation
        public static void run(Runnable ignored) {
            calls++;
        }
    }

    private static final class SyntheticPlayer {
        final ExoPlayer exo;
        Player.Listener listener;
        int state = Player.STATE_IDLE;
        int suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE;
        boolean playWhenReady, released;
        long position;

        SyntheticPlayer() {
            exo = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(), new Class<?>[]{ExoPlayer.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addListener" -> { listener = (Player.Listener) args[0]; yield null; }
                        case "setMediaItem", "seekTo" -> { position = (long) args[args.length - 1]; yield null; }
                        case "prepare" -> { state = Player.STATE_READY; yield null; }
                        case "setPlayWhenReady" -> {
                            playWhenReady = (boolean) args[0];
                            fire(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_IS_PLAYING_CHANGED);
                            yield null;
                        }
                        case "getPlaybackState" -> state;
                        case "getPlayWhenReady" -> playWhenReady;
                        case "getPlaybackSuppressionReason" -> suppression;
                        case "isPlaying" -> playWhenReady && state == Player.STATE_READY
                                && suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE;
                        case "getCurrentPosition" -> position;
                        case "getDuration" -> 120_000L;
                        case "release" -> { released = true; yield null; }
                        default -> throw new AssertionError("Unexpected player call " + method.getName());
                    });
        }

        void focusLost(boolean lost) {
            suppression = lost ? Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                    : Player.PLAYBACK_SUPPRESSION_REASON_NONE;
            fire(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED, Player.EVENT_IS_PLAYING_CHANGED);
        }

        void fire(int... flags) {
            listener.onEvents(exo, new Player.Events(new FlagSet.Builder().addAll(flags).build()));
        }
    }
}
