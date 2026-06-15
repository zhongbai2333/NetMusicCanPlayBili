package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * NV12 纹理上传 PBO。
 *
 * <p>
 * 首版采用 orphan + map/unmap + {@code glTexSubImage2D(offset=0)}。
 * </p>
 */
final class Nv12PboUploader implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int GL_PIXEL_UNPACK_BUFFER = 0x88EC;
    private static final int GL_PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;
    private static final int GL_UNPACK_SKIP_PIXELS = 0x0CF4;
    private static final int GL_UNPACK_SKIP_ROWS = 0x0CF3;
    private static final int GL_UNPACK_IMAGE_HEIGHT = 0x806E;
    private static final int GL_UNPACK_SKIP_IMAGES = 0x806D;
    private static final int MAP_FLAGS = GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_INVALIDATE_BUFFER_BIT;

    private final String label;
    private int pbo;
    private int capacity;
    private boolean loggedFirstUpload;
    private boolean disabled;

    Nv12PboUploader(String label) {
        this.label = label;
    }

    boolean uploadRed8(GpuTexture texture, byte[] data, int offset, int width, int height) {
        int bytes = width * height;
        if (data == null || data.length < offset + bytes) {
            return false;
        }
        return upload(texture, width, height, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, bytes, mapped -> {
            mapped.put(data, offset, bytes);
        });
    }

    boolean uploadRed8(GpuTexture texture, ByteBuffer data, int offset, int width, int height) {
        int bytes = width * height;
        if (data == null || data.limit() < offset + bytes) {
            return false;
        }
        return upload(texture, width, height, GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, bytes, mapped -> {
            ByteBuffer src = data.duplicate();
            src.position(offset);
            src.limit(offset + bytes);
            mapped.put(src);
        });
    }

    boolean uploadNv12UvAsRgba8(GpuTexture texture, byte[] nv12, int offset, int width, int height) {
        int pixels = width * height;
        int sourceBytes = pixels * 2;
        int uploadBytes = pixels * 4;
        if (nv12 == null || nv12.length < offset + sourceBytes) {
            return false;
        }
        return upload(texture, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, uploadBytes, mapped -> {
            int src = offset;
            for (int i = 0; i < pixels; i++) {
                mapped.put(nv12[src++]);
                mapped.put(nv12[src++]);
                mapped.put((byte) 0);
                mapped.put((byte) 255);
            }
        });
    }

    boolean uploadNv12UvAsRg8(GpuTexture texture, byte[] nv12, int offset, int width, int height) {
        int pixels = width * height;
        int bytes = pixels * 2;
        if (nv12 == null || nv12.length < offset + bytes) {
            return false;
        }
        return upload(texture, width, height, GL30C.GL_RG, GL11C.GL_UNSIGNED_BYTE, bytes, mapped -> {
            mapped.put(nv12, offset, bytes);
        });
    }

    boolean uploadNv12UvAsRg8(GpuTexture texture, ByteBuffer nv12, int offset, int width, int height) {
        int pixels = width * height;
        int bytes = pixels * 2;
        if (nv12 == null || nv12.limit() < offset + bytes) {
            return false;
        }
        return upload(texture, width, height, GL30C.GL_RG, GL11C.GL_UNSIGNED_BYTE, bytes, mapped -> {
            ByteBuffer src = nv12.duplicate();
            src.position(offset);
            src.limit(offset + bytes);
            mapped.put(src);
        });
    }

    private boolean upload(GpuTexture texture, int width, int height, int format, int type, int bytes,
            BufferWriter writer) {
        if (!(texture instanceof GlTexture glTexture) || texture.isClosed()) {
            return false;
        }
        if (bytes <= 0 || disabled) {
            return false;
        }
        int previousPbo = GL11C.glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int previousUnpackAlignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        int previousUnpackRowLength = GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH);
        int previousUnpackSkipPixels = GL11C.glGetInteger(GL_UNPACK_SKIP_PIXELS);
        int previousUnpackSkipRows = GL11C.glGetInteger(GL_UNPACK_SKIP_ROWS);
        int previousUnpackImageHeight = GL11C.glGetInteger(GL_UNPACK_IMAGE_HEIGHT);
        int previousUnpackSkipImages = GL11C.glGetInteger(GL_UNPACK_SKIP_IMAGES);
        boolean mappedBuffer = false;
        try {
            clearGlErrors();
            ensurePbo(bytes);
            GL15C.glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) {
                disabled = true;
                LOGGER.warn("NV12/PBO: {} 绑定 PBO 失败 {}，本纹理后续回退普通上传", label, glErrorName(error));
                return false;
            }
            GL15C.glBufferData(GL_PIXEL_UNPACK_BUFFER, bytes, GL15C.GL_STREAM_DRAW);
            ByteBuffer mapped = GL30C.glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0L, bytes, MAP_FLAGS);
            if (mapped == null) {
                return false;
            }
            mappedBuffer = true;
            writer.write(mapped);
            GL15C.glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
            mappedBuffer = false;

            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, glTexture.glId());
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            GL11C.glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, 0L);
            error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) {
                disabled = true;
                LOGGER.warn("NV12/PBO: {} 触发 OpenGL 错误 {}，本纹理后续禁用 PBO 并回退普通上传",
                        label, glErrorName(error));
                return false;
            }
            if (!loggedFirstUpload) {
                loggedFirstUpload = true;
                LOGGER.info("NV12/PBO: 首次通过 PBO 上传 {}: {}x{}, format={}, bytes={}, pbo={}",
                        label, width, height, glFormatName(format), bytes, pbo);
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("NV12/PBO: {} 上传失败，将回退普通 writeToTexture: {}", label, e.toString());
            return false;
        } finally {
            if (mappedBuffer) {
                try {
                    GL15C.glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
                } catch (RuntimeException ignored) {
                }
            }
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, previousUnpackRowLength);
            GL11C.glPixelStorei(GL_UNPACK_SKIP_PIXELS, previousUnpackSkipPixels);
            GL11C.glPixelStorei(GL_UNPACK_SKIP_ROWS, previousUnpackSkipRows);
            GL11C.glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, previousUnpackImageHeight);
            GL11C.glPixelStorei(GL_UNPACK_SKIP_IMAGES, previousUnpackSkipImages);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            GL15C.glBindBuffer(GL_PIXEL_UNPACK_BUFFER, previousPbo);
        }
    }

    private static String glFormatName(int format) {
        if (format == GL11C.GL_RED) {
            return "RED";
        }
        if (format == GL30C.GL_RG) {
            return "RG";
        }
        if (format == GL11C.GL_RGBA) {
            return "RGBA";
        }
        return "0x" + Integer.toHexString(format);
    }

    private static void clearGlErrors() {
        for (int i = 0; i < 8 && GL11C.glGetError() != GL11C.GL_NO_ERROR; i++) {
            // 先清空陈旧错误，确保本次上传可以做出可靠判断
        }
    }

    private static String glErrorName(int error) {
        return switch (error) {
            case GL11C.GL_INVALID_ENUM -> "GL_INVALID_ENUM";
            case GL11C.GL_INVALID_VALUE -> "GL_INVALID_VALUE";
            case GL11C.GL_INVALID_OPERATION -> "GL_INVALID_OPERATION";
            case GL11C.GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY";
            default -> "0x" + Integer.toHexString(error);
        };
    }

    private void ensurePbo(int bytes) {
        if (pbo == 0) {
            pbo = GL15C.glGenBuffers();
        }
        if (capacity < bytes) {
            capacity = bytes;
        }
    }

    @Override
    public void close() {
        if (pbo != 0) {
            GL15C.glDeleteBuffers(pbo);
            pbo = 0;
            capacity = 0;
        }
    }

    @FunctionalInterface
    private interface BufferWriter {
        void write(ByteBuffer mapped);
    }
}