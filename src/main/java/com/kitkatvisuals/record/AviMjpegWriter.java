package com.kitkatvisuals.record;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Минимальный писатель AVI (Motion JPEG, без звука). Кадры дописываются в конец, индекс idx1 и размеры в заголовке
 * обновляются в checkpoint(): даже если игра упадёт, файл откроется (потеряется не больше последних секунд).
 * Смещения заголовка: RIFF-размер 4, avih 24..87 (кадры 48, макс. байт/с 36, буфер 60), strh 108..163 (длина 140, буфер 144),
 * размер списка movi 216, первый кадр 224.
 */
public final class AviMjpegWriter implements Closeable {
    private static final int HEADER = 224;
    private static final int MOVI_FOURCC_POS = 220;

    private final RandomAccessFile raf;
    private final int width;
    private final int height;
    private final int fps;
    private int[] offsets = new int[1024];
    private int[] sizes = new int[1024];
    private int frames;
    private long totalBytes;
    private int maxChunk;
    private long dataEnd = HEADER;

    public AviMjpegWriter(Path file, int width, int height, int fps) throws IOException {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.raf = new RandomAccessFile(file.toFile(), "rw");
        raf.setLength(0);
        raf.write(header());
    }

    private static void fcc(ByteBuffer b, String s) {
        b.put(s.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] header() {
        ByteBuffer b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        fcc(b, "RIFF"); b.putInt(0); fcc(b, "AVI ");
        fcc(b, "LIST"); b.putInt(192); fcc(b, "hdrl");
        fcc(b, "avih"); b.putInt(56);
        b.putInt(1_000_000 / fps).putInt(0).putInt(0).putInt(0x10).putInt(0).putInt(0).putInt(1).putInt(0)
                .putInt(width).putInt(height).putInt(0).putInt(0).putInt(0).putInt(0);
        fcc(b, "LIST"); b.putInt(116); fcc(b, "strl");
        fcc(b, "strh"); b.putInt(56);
        fcc(b, "vids"); fcc(b, "MJPG");
        b.putInt(0).putShort((short) 0).putShort((short) 0).putInt(0).putInt(1).putInt(fps).putInt(0).putInt(0).putInt(0)
                .putInt(-1).putInt(0).putShort((short) 0).putShort((short) 0).putShort((short) width).putShort((short) height);
        fcc(b, "strf"); b.putInt(40);
        b.putInt(40).putInt(width).putInt(height).putShort((short) 1).putShort((short) 24);
        fcc(b, "MJPG");
        b.putInt(width * height * 3).putInt(0).putInt(0).putInt(0).putInt(0);
        fcc(b, "LIST"); b.putInt(4); fcc(b, "movi");
        return b.array();
    }

    public int frames() { return frames; }

    public long bytes() { return dataEnd; }

    /** Добавляет один кадр (готовый JPEG). */
    public void writeFrame(byte[] jpeg, int len) throws IOException {
        if (frames == offsets.length) {
            offsets = java.util.Arrays.copyOf(offsets, frames * 2);
            sizes = java.util.Arrays.copyOf(sizes, frames * 2);
        }
        long chunkPos = dataEnd;
        ByteBuffer h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        fcc(h, "00dc");
        h.putInt(len);
        raf.seek(chunkPos);
        raf.write(h.array());
        raf.write(jpeg, 0, len);
        if ((len & 1) != 0) raf.write(0); // выравнивание чанка до чётного размера
        offsets[frames] = (int) (chunkPos - MOVI_FOURCC_POS);
        sizes[frames] = len;
        frames++;
        totalBytes += len;
        maxChunk = Math.max(maxChunk, len);
        dataEnd = chunkPos + 8 + len + (len & 1);
    }

    /** Пишет индекс и размеры, затем возвращает позицию на конец кадров: запись можно продолжать. */
    public void checkpoint() throws IOException {
        long pos = dataEnd;
        ByteBuffer idx = ByteBuffer.allocate(8 + 16 * frames).order(ByteOrder.LITTLE_ENDIAN);
        fcc(idx, "idx1");
        idx.putInt(16 * frames);
        for (int i = 0; i < frames; i++) {
            fcc(idx, "00dc");
            idx.putInt(0x10).putInt(offsets[i]).putInt(sizes[i]);
        }
        raf.seek(pos);
        raf.write(idx.array());
        long fileEnd = pos + idx.capacity();
        int bytesPerSec = frames == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, totalBytes * fps / frames);
        patch(4, (int) (fileEnd - 8));
        patch(216, (int) (pos - MOVI_FOURCC_POS));
        patch(36, bytesPerSec);
        patch(48, frames);
        patch(60, maxChunk);
        patch(140, frames);
        patch(144, maxChunk);
        raf.seek(pos);
    }

    private void patch(long at, int value) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
        raf.seek(at);
        raf.write(b.array());
    }

    @Override
    public void close() throws IOException {
        try {
            checkpoint();
            raf.setLength(dataEnd + 8 + 16L * frames);
        } finally {
            raf.close();
        }
    }
}
