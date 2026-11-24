package com.kitkatvisuals.record;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Фоновый кодировщик: JPEG + запись AVI в отдельном потоке с низким приоритетом. Очередь ограничена, при
 * перегрузке кадры отбрасываются (счётчик drops), поэтому поток рендера никогда не ждёт кодирование.
 * Время кадров сохраняется: если кадры пропущены, предыдущий кадр дублируется, и видео идёт в реальной скорости.
 */
public final class RecordEncoder {
    public record Result(Path file, int frames, long bytes, double seconds, String error) { }

    private record Frame(int[] px, long ts) { }

    private static final int QUEUE = 4;

    private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(QUEUE);
    private final BlockingQueue<int[]> pool = new ArrayBlockingQueue<>(QUEUE + 2);
    private final AtomicInteger drops = new AtomicInteger();
    private final Path file;
    private final int w;
    private final int h;
    private final int fps;
    private final long frameNs;
    private final ImageWriter writer;
    private final ImageWriteParam param;
    private final BufferedImage img;
    private final Consumer<Result> onDone;
    private final long minFreeBytes;
    private final Thread thread;
    private volatile boolean stopping;
    private volatile double seconds;

    public RecordEncoder(Path file, int w, int h, int fps, int quality, long minFreeBytes, Consumer<Result> onDone) {
        this.file = file;
        this.w = w;
        this.h = h;
        this.fps = fps;
        this.frameNs = 1_000_000_000L / fps;
        this.onDone = onDone;
        this.minFreeBytes = minFreeBytes;
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        if (!it.hasNext()) throw new IllegalStateException("в этой Java нет JPEG-кодировщика (ImageIO)");
        this.writer = it.next();
        this.param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(Math.max(0.1f, Math.min(1f, quality / 100f)));
        this.img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < QUEUE + 2; i++) pool.add(new int[w * h]);
        this.thread = new Thread(this::run, "KitKat-Record-Encoder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        thread.start();
    }

    /** Свободный буфер для кадра или null, если кодировщик не успевает. */
    public int[] acquire() { return pool.poll(); }

    public void release(int[] buf) { pool.offer(buf); }

    public void countDrop() { drops.incrementAndGet(); }

    public int drops() { return drops.get(); }

    public double seconds() { return seconds; }

    public void submit(int[] px, long tsNanos) {
        if (!queue.offer(new Frame(px, tsNanos))) {
            pool.offer(px);
            drops.incrementAndGet();
        }
    }

    /** Просит закончить: оставшиеся в очереди кадры будут дописаны, файл закроется в потоке кодировщика. */
    public void stop() { stopping = true; }

    public void join(long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        String error = null;
        AviMjpegWriter avi = null;
        long nextIndex = 0;
        long lastCheckpoint = System.nanoTime();
        byte[] last = null;
        int lastLen = 0;
        try {
            avi = new AviMjpegWriter(file, w, h, fps);
            while (true) {
                Frame f = queue.poll(100, TimeUnit.MILLISECONDS);
                if (f == null) {
                    if (stopping) break;
                    continue;
                }
                long target = Math.round(f.ts() / (double) frameNs);
                if (target >= nextIndex) {
                    ByteArrayOutputStream bos = encode(f.px());
                    byte[] jpeg = bos.toByteArray();
                    long gap = Math.min(target - nextIndex, fps * 10L);
                    for (long i = 0; i < gap && last != null; i++) avi.writeFrame(last, lastLen); // пропущенные кадры = повтор предыдущего
                    avi.writeFrame(jpeg, jpeg.length);
                    nextIndex = target + 1;
                    last = jpeg;
                    lastLen = jpeg.length;
                    seconds = nextIndex / (double) fps;
                }
                pool.offer(f.px());
                long now = System.nanoTime();
                if (now - lastCheckpoint > 10_000_000_000L) {
                    avi.checkpoint();
                    lastCheckpoint = now;
                    if (file.toFile().getParentFile().getUsableSpace() < minFreeBytes) {
                        error = "мало места на диске, запись остановлена";
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            error = t.toString();
        } finally {
            Result res;
            long bytes = 0;
            int frames = 0;
            try {
                if (avi != null) {
                    frames = avi.frames();
                    avi.close();
                }
                bytes = file.toFile().length();
            } catch (IOException e) {
                if (error == null) error = e.toString();
            }
            writer.dispose();
            res = new Result(file, frames, bytes, frames / (double) fps, error);
            try {
                onDone.accept(res);
            } catch (Throwable ignored) {
                // сообщение пользователю не должно ронять поток
            }
        }
    }

    private ByteArrayOutputStream encode(int[] src) throws IOException {
        int[] dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < h; y++) System.arraycopy(src, (h - 1 - y) * w, dst, y * w, w); // OpenGL хранит строки снизу вверх
        ByteArrayOutputStream bos = new ByteArrayOutputStream(w * h / 8);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(img, null, null), param);
            out.flush();
        }
        return bos;
    }
}
