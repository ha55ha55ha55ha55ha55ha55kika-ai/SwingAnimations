package com.kitkatvisuals.record;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Захват кадра почти без нагрузки на GPU/CPU главного потока:
 * 1) glBlitFramebuffer уменьшает кадр на видеокарте в маленький FBO (несколько долей миллисекунды);
 * 2) glReadPixels пишет в PBO и сразу возвращается (асинхронно, без ожидания видеокарты);
 * 3) результат забирается из PBO только через RING-1 захватов, когда он уже готов, и копируется в буфер из пула.
 * Все вызовы только из потока рендера. Прежние привязки FBO восстанавливаются, чтобы не сбить состояние Minecraft.
 */
public final class GlCapture {
    private static final int RING = 3;

    private final int w;
    private final int h;
    private final int fbo;
    private final int rbo;
    private final int[] pbo = new int[RING];
    private final long[] stamp = new long[RING];
    private final boolean[] has = new boolean[RING];
    private int head;

    public GlCapture(int w, int h) {
        this.w = w;
        this.h = h;
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        rbo = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rbo);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, w, h);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, rbo);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            destroy();
            throw new IllegalStateException("FBO записи не создан (0x" + Integer.toHexString(status) + ")");
        }
        long size = (long) w * h * 4;
        for (int i = 0; i < RING; i++) {
            pbo[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    /** Снимает кадр из srcFbo (sw x sh). Готовый старый кадр отдаёт кодировщику, если есть свободный буфер. */
    public void capture(int srcFbo, int sw, int sh, long tsNanos, RecordEncoder enc) {
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, srcFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GL30.glBlitFramebuffer(0, 0, sw, sh, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);

            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[head]);
            GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
            stamp[head] = tsNanos;
            has[head] = true;

            int old = (head + 1) % RING; // снят RING-1 захватов назад: видеокарта с ним уже закончила
            if (has[old]) {
                has[old] = false;
                int[] dst = enc.acquire();
                if (dst == null) {
                    enc.countDrop(); // кодировщик не успевает: кадр пропускаем, главный поток не ждём
                } else {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[old]);
                    ByteBuffer bb = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, (long) w * h * 4, GL30.GL_MAP_READ_BIT);
                    if (bb != null) {
                        bb.order(ByteOrder.nativeOrder()).asIntBuffer().get(dst, 0, w * h);
                        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                        enc.submit(dst, stamp[old]);
                    } else {
                        enc.release(dst);
                    }
                }
            }
            head = old;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
    }

    public void destroy() {
        for (int i = 0; i < RING; i++) if (pbo[i] != 0) GL15.glDeleteBuffers(pbo[i]);
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
        if (rbo != 0) GL30.glDeleteRenderbuffers(rbo);
    }
}
