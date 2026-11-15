package com.swinganimations.music;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Плеер MP3-потоков. Декодирует JLayer'ом и выводит звук через Java Sound в отдельном потоке,
 * поэтому не мешает игре и не зависит от OpenAL Minecraft. Очередь, пауза, громкость, автопереход к следующему треку.
 * Весь публичный API потокобезопасен; состояния читаются из потока рендера без блокировок.
 */
public final class MusicPlayer {
    public enum State { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    private static final AtomicInteger GEN = new AtomicInteger();
    private static volatile State state = State.IDLE;
    private static volatile List<Track> queue = List.of();
    private static volatile int index = -1;
    private static volatile long positionMs;
    private static volatile String error = "";
    private static volatile float volume = 0.6f;
    private static volatile boolean paused;
    private static volatile InputStream currentIn;

    private MusicPlayer() {}

    // ---------------- состояние ----------------

    public static State state() { return state; }

    public static Track current() {
        List<Track> q = queue;
        int i = index;
        return i >= 0 && i < q.size() ? q.get(i) : null;
    }

    public static int index() { return index; }

    public static long positionMs() { return positionMs; }

    public static String error() { return error; }

    public static float volume() { return volume; }

    public static boolean isActive() {
        State s = state;
        return s == State.LOADING || s == State.PLAYING || s == State.PAUSED;
    }

    public static void setVolume(float v) { volume = Math.max(0f, Math.min(1f, v)); }

    /** Время в формате m:ss. */
    public static String fmt(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return (s / 60L) + ":" + (s % 60L < 10L ? "0" : "") + (s % 60L);
    }

    // ---------------- управление ----------------

    /** Запускает очередь с позиции start. По окончании трека автоматически играет следующий. */
    public static synchronized void play(List<Track> list, int start) {
        queue = List.copyOf(list);
        startIndex(start);
    }

    public static synchronized void next() {
        if (queue.isEmpty()) return;
        startIndex(index + 1 < queue.size() ? index + 1 : 0);
    }

    public static synchronized void prev() {
        if (queue.isEmpty()) return;
        if (positionMs > 3000L && index >= 0) startIndex(index);
        else startIndex(Math.max(0, index - 1));
    }

    public static synchronized void togglePause() {
        State s = state;
        if (s == State.PLAYING) {
            paused = true;
            state = State.PAUSED;
        } else if (s == State.PAUSED) {
            paused = false;
            state = State.PLAYING;
        } else if (!queue.isEmpty() && index >= 0) {
            startIndex(index); // после остановки или ошибки: запускаем текущий трек заново
        }
    }

    public static synchronized void stop() {
        GEN.incrementAndGet();
        closeCurrent();
        paused = false;
        positionMs = 0L;
        state = State.IDLE;
    }

    private static void startIndex(int i) {
        List<Track> q = queue;
        if (i < 0 || i >= q.size()) {
            stop();
            return;
        }
        int gen = GEN.incrementAndGet();
        closeCurrent();
        index = i;
        positionMs = 0L;
        paused = false;
        error = "";
        state = State.LOADING;
        Track t = q.get(i);
        Thread th = new Thread(() -> run(t, gen), "SwingMusic-Player");
        th.setDaemon(true);
        th.start();
    }

    private static void closeCurrent() {
        InputStream in = currentIn;
        currentIn = null;
        if (in != null) {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    // ---------------- поток воспроизведения ----------------

    private static void run(Track t, int gen) {
        SourceDataLine line = null;
        InputStream in = null;
        try {
            in = new BufferedInputStream(Http.open(t.streamUrl()), 64 * 1024);
            currentIn = in;
            if (gen != GEN.get()) return;
            Bitstream bs = new Bitstream(in);
            Decoder dec = new Decoder();
            byte[] out = new byte[8192];
            Header h;
            while (gen == GEN.get() && (h = bs.readFrame()) != null) {
                SampleBuffer sb = (SampleBuffer) dec.decodeFrame(h, bs);
                if (line == null) {
                    AudioFormat fmt = new AudioFormat(dec.getOutputFrequency(), 16, dec.getOutputChannels(), true, false);
                    line = AudioSystem.getSourceDataLine(fmt);
                    line.open(fmt, Math.max(8192, (int) (fmt.getFrameRate() * fmt.getFrameSize() * 0.25f)));
                    line.start();
                }
                short[] pcm = sb.getBuffer();
                int n = sb.getBufferLength();
                if (out.length < n * 2) out = new byte[n * 2];
                float g = volume * volume;
                for (int i = 0; i < n; i++) {
                    int v = Math.round(pcm[i] * g);
                    if (v > 32767) v = 32767;
                    else if (v < -32768) v = -32768;
                    out[i * 2] = (byte) v;
                    out[i * 2 + 1] = (byte) (v >> 8);
                }
                if (paused) {
                    line.stop();
                    while (paused && gen == GEN.get()) Thread.sleep(40L);
                    if (gen != GEN.get()) break;
                    line.start();
                }
                line.write(out, 0, n * 2);
                positionMs += (long) h.ms_per_frame();
                if (state == State.LOADING && gen == GEN.get()) state = State.PLAYING;
                bs.closeFrame();
            }
            if (line != null && gen == GEN.get()) line.drain();
            if (gen == GEN.get()) finished(gen);
        } catch (Throwable e) {
            if (gen == GEN.get()) {
                String m = e.getMessage();
                error = e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
                state = State.ERROR;
            }
        } finally {
            if (line != null) {
                try { line.stop(); line.close(); } catch (Throwable ignored) { }
            }
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
            if (currentIn == in) currentIn = null;
        }
    }

    /** Трек доиграл до конца: идём дальше по очереди, на последнем останавливаемся. */
    private static synchronized void finished(int gen) {
        if (gen != GEN.get()) return;
        if (index + 1 < queue.size()) startIndex(index + 1);
        else {
            state = State.IDLE;
            positionMs = 0L;
        }
    }
}
