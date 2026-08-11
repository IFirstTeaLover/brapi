package dev.bsprout.brapi.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves emoji codepoints to GPU textures for BFont.
**/

public class BFontEmojiResolver {
    private static Logger logger = BTexture.getLogger();
    private static final String TWEMOJI_VERSION = "17.0.3";
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);
    private static final int SYSTEM_FALLBACK_SIZE = 64;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static Font systemEmojiFont;
    private static boolean systemFontLookupDone = false;

    private record GpuHandle(GpuTexture texture, GpuTextureView view) {
        void close() {
            view.close();
            texture.close();
        }
    }

    private enum Status { PENDING, READY, FAILED }

    private static final class Entry {
        volatile Status status = Status.PENDING;
        volatile GpuHandle real;
        volatile GpuHandle fallback;
    }

    private final ConcurrentMap<Integer, Entry> cache = new ConcurrentHashMap<>();

    public GpuTextureView getEmojiTextureView(int codePoint) {
        Entry entry = cache.computeIfAbsent(codePoint, this::startResolving);
        GpuHandle handle = entry.real != null ? entry.real : entry.fallback;
        return handle != null ? handle.view() : null;
    }

    public boolean hasEmoji(int codePoint) {
        Entry entry = cache.get(codePoint);
        return entry != null && (entry.real != null || entry.fallback != null);
    }

    private Entry startResolving(int codePoint) {
        Entry entry = new Entry();

        GpuHandle packHandle = tryLoadResourcePack(codePoint);
        if (packHandle != null) {
            entry.real = packHandle;
            entry.status = Status.READY;
            return entry;
        }

        GpuHandle diskHandle = tryLoadDiskCache(codePoint);
        if (diskHandle != null) {
            entry.real = diskHandle;
            entry.status = Status.READY;
            return entry;
        }

        entry.fallback = tryRasterizeSystemFont(codePoint);
        fetchTwemojiAsync(codePoint, entry);

        return entry;
    }

    // Resource pack override

    private static GpuHandle tryLoadResourcePack(int codePoint) {
        String hex = String.format("%04x", codePoint);
        var path = Identifier.fromNamespaceAndPath("brapi", "textures/font/emoji/" + hex + ".png");

        var resourceOpt = Minecraft.getInstance().getResourceManager().getResource(path);
        if (resourceOpt.isEmpty()) return null;

        try (InputStream in = resourceOpt.get().open()) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            return uploadToGpu(img, "emoji_pack_" + hex);
        } catch (Exception e) {
            logger.error("[EmojiResolver] Failed to load pack override for " + hex + ":");
            e.printStackTrace();
            return null;
        }
    }

    // Try to load from disk
    private static GpuHandle tryLoadDiskCache(int codePoint) {
        String hex = Integer.toHexString(codePoint).toLowerCase();
        Path emojiPath = cacheDir().resolve(hex + ".png");
        if (!Files.exists(emojiPath)) return null;


        logger.info("[EmojiResolver] " + hex + " found in disk cache, using that instead of Twemoji");
        try (InputStream in = Files.newInputStream(emojiPath)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            return uploadToGpu(img, "emoji_cached_" + hex);
        } catch (Exception e) {
            logger.error("[EmojiResolver] Corrupt disk cache entry for " + hex + ", deleting:");
            e.printStackTrace();
            try { Files.deleteIfExists(emojiPath); } catch (Exception ignored) {}
            return null;
        }
    }

    private static Path cacheDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(".brapi/emoji_cache");
    }

    // System font placeholder
    private static GpuHandle tryRasterizeSystemFont(int codePoint) {
        Font emojiFont = getSystemEmojiFont();
        if (emojiFont == null || !emojiFont.canDisplay(codePoint)) return null;

        try {
            BufferedImage img = new BufferedImage(SYSTEM_FALLBACK_SIZE, SYSTEM_FALLBACK_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(emojiFont.deriveFont(48.0f));

            String emojiStr = new String(Character.toChars(codePoint));
            FontMetrics fm = g.getFontMetrics();
            int x = (SYSTEM_FALLBACK_SIZE - fm.stringWidth(emojiStr)) / 2;
            int y = ((SYSTEM_FALLBACK_SIZE - fm.getHeight()) / 2) + fm.getAscent();
            g.drawString(emojiStr, x, y);
            g.dispose();

            return uploadToGpu(img, "emoji_sysfallback_" + Integer.toHexString(codePoint));
        } catch (Exception e) {
            return null;
        }
    }

    private static Font getSystemEmojiFont() {
        if (systemFontLookupDone) return systemEmojiFont;
        systemFontLookupDone = true;

        String[] candidates = {"Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji", "Symbola"};
        for (String name : candidates) {
            Font font = new Font(name, Font.PLAIN, 32);
            if (!font.getFamily().equalsIgnoreCase("Dialog")) {
                systemEmojiFont = font;
                return systemEmojiFont;
            }
        }
        return null;
    }

    // Twemoji fetch
    private void fetchTwemojiAsync(int codePoint, Entry entry) {
        String hex = Integer.toHexString(codePoint).toLowerCase();
        Path emojiPath = cacheDir().resolve(hex + ".png");

        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(cacheDir());

                String url = getTwemojiUrl(codePoint);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(FETCH_TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
                logger.info("[EmojiResolver] Requesting Twemoji: " + hex + ".png");

                if (response.statusCode() != 200 || response.body().length == 0) {
                    entry.status = Status.FAILED;
                    logger.error("[EmojiResolver] FAILED To Load Emoji!");
                    return;
                }

                Files.write(emojiPath, response.body());
                logger.info("[EmojiResolver] SUCCESSFULLY Downloaded and saved emoji!");

                Minecraft.getInstance().execute(() -> {
                    try (InputStream in = Files.newInputStream(emojiPath)) {
                        BufferedImage img = ImageIO.read(in);
                        if (img == null) {
                            entry.status = Status.FAILED;
                            return;
                        }
                        GpuHandle newHandle = uploadToGpu(img, "emoji_web_" + hex);

                        entry.real = newHandle;
                        entry.status = Status.READY;

                        GpuHandle oldFallback = entry.fallback;
                        entry.fallback = null;
                        if (oldFallback != null) oldFallback.close();
                    } catch (Exception e) {
                        entry.status = Status.FAILED;
                    }
                });
            } catch (Exception e) {
                entry.status = Status.FAILED;
            }
        });
    }

    public static String getTwemojiUrl(int codePoint) {
        String hex = Integer.toHexString(codePoint).toLowerCase();
        return "https://cdn.jsdelivr.net/gh/jdecked/twemoji@" + TWEMOJI_VERSION + "/assets/72x72/" + hex + ".png";
    }

    private static GpuHandle uploadToGpu(BufferedImage img, String debugName) {
        int w = img.getWidth();
        int h = img.getHeight();

        ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);
        try {
            int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
            for (int argb : pixels) {
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                buffer.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
            }
            buffer.flip();

            GpuTexture texture = RenderSystem.getDevice().createTexture(
                    debugName,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    TextureFormat.RGBA8,
                    w, h, 1, 1
            );

            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(texture, buffer, com.mojang.blaze3d.platform.NativeImage.Format.RGBA,
                            0, 0, 0, 0, w, h);

            GpuTextureView view = RenderSystem.getDevice().createTextureView(texture);
            return new GpuHandle(texture, view);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    public void close() {
        for (Entry entry : cache.values()) {
            if (entry.real != null) entry.real.close();
            if (entry.fallback != null) entry.fallback.close();
        }
        cache.clear();
    }
}