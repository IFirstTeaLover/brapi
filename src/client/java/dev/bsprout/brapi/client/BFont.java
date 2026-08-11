package dev.bsprout.brapi.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;

public class BFont {
    private static final int ATLAS_SIZE = 2048;
    private static final int SDF_PIXEL_DIST = 8;
    private static final int GLYPH_SIZE = 64;

    private static final int[] BASE_RANGE = {32, 126};

    private static final Map<String, int[][]> LANGUAGE_RANGES = Map.ofEntries(
            Map.entry("ru", new int[][]{{0x0400, 0x04FF}}),
            Map.entry("uk", new int[][]{{0x0400, 0x04FF}}),
            Map.entry("be", new int[][]{{0x0400, 0x04FF}}),
            Map.entry("bg", new int[][]{{0x0400, 0x04FF}}),
            Map.entry("sr", new int[][]{{0x0400, 0x04FF}}),
            Map.entry("mk", new int[][]{{0x0400, 0x04FF}}),
            Map.entry("el", new int[][]{{0x0370, 0x03FF}}),
            Map.entry("he", new int[][]{{0x0590, 0x05FF}}),
            Map.entry("ar", new int[][]{{0x0600, 0x06FF}}),
            Map.entry("th", new int[][]{{0x0E00, 0x0E7F}}),
            Map.entry("vi", new int[][]{{0x1E00, 0x1EFF}, {0x0100, 0x017F}})
    );

    public float ascent;
    private BFontEmojiResolver emojiResolver;

    public record FormattedQuads(float[][] quads, int[] colors) {}
    public record Glyph(float u0, float v0, float u1, float v1,
                        int width, int height, int xOffset, int yOffset, float advance,
                        boolean hasGlyph) {}
    public record EmojiQuad(GpuTextureView textureView, float x0, float y0, float x1, float y1) {}
    public record TextRenderBatch(List<float[]> fontQuads, List<Integer> fontColors, List<EmojiQuad> emojiQuads) {}

    private final Map<Integer, Glyph> glyphMap = new HashMap<>();
    private GpuTexture atlasTexture;
    public GpuTextureView atlasView;
    private final float bakeSize = GLYPH_SIZE;

    private ByteBuffer ttfBuf;
    private STBTTFontinfo fontInfo;
    private float scale;

    private int penX = 2, penY = 2, rowH = 0;

    public BFont(Identifier fontPath) {
        this(fontPath, null);
    }

    public BFont(Identifier fontPath, BFontEmojiResolver emojiResolver) {
        this.emojiResolver = emojiResolver;

        try {
            byte[] ttfBytes;
            try (InputStream stream = Minecraft.getInstance().getResourceManager().open(fontPath)) {
                ttfBytes = stream.readAllBytes();
            }

            ttfBuf = MemoryUtil.memAlloc(ttfBytes.length);
            ttfBuf.put(ttfBytes).flip();

            fontInfo = STBTTFontinfo.malloc();
            if (!STBTruetype.stbtt_InitFont(fontInfo, ttfBuf, 0)) {
                throw new IllegalStateException("Failed to initialize TTF font: " + fontPath);
            }

            scale = STBTruetype.stbtt_ScaleForPixelHeight(fontInfo, GLYPH_SIZE);

            int[] asc = new int[1], desc = new int[1], gap = new int[1];
            STBTruetype.stbtt_GetFontVMetrics(fontInfo, asc, desc, gap);
            this.ascent = asc[0] * scale;

            atlasTexture = RenderSystem.getDevice().createTexture(
                    "BFont SDF atlas",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    TextureFormat.RED8,
                    ATLAS_SIZE, ATLAS_SIZE, 1, 1
            );
            ByteBuffer blank = MemoryUtil.memCalloc(ATLAS_SIZE * ATLAS_SIZE);
            try {
                RenderSystem.getDevice().createCommandEncoder()
                        .writeToTexture(atlasTexture, blank, NativeImage.Format.LUMINANCE,
                                0, 0, 0, 0, ATLAS_SIZE, ATLAS_SIZE);
            } finally {
                MemoryUtil.memFree(blank);
            }
            atlasView = RenderSystem.getDevice().createTextureView(atlasTexture);

            for (int cp = BASE_RANGE[0]; cp <= BASE_RANGE[1]; cp++) {
                bakeGlyph(cp);
            }
            for (int[] range : rangesForActiveLanguages()) {
                for (int cp = range[0]; cp <= range[1]; cp++) {
                    bakeGlyph(cp);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to load font: " + fontPath, e);
        }
    }

    private static List<int[]> rangesForActiveLanguages() {
        List<int[]> ranges = new ArrayList<>();
        Set<String> codes = new LinkedHashSet<>();

        try {
            String mcLang = Minecraft.getInstance().options.languageCode;
            if (mcLang.contains("_")) {
                codes.add(mcLang.substring(0, mcLang.indexOf('_')));
            }
        } catch (Exception ignored) {
        }

        String sysLang = Locale.getDefault().getLanguage();
        codes.add(sysLang);

        for (String code : codes) {
            int[][] r = LANGUAGE_RANGES.get(code);
            if (r != null) ranges.addAll(Arrays.asList(r));
        }
        return ranges;
    }

    private Glyph bakeGlyph(int codepoint) {
        Glyph cached = glyphMap.get(codepoint);
        if (cached != null) return cached;

        boolean fontHasGlyph = STBTruetype.stbtt_FindGlyphIndex(fontInfo, codepoint) != 0;

        int[] w = new int[1], h = new int[1], xoff = new int[1], yoff = new int[1];
        ByteBuffer sdf = STBTruetype.stbtt_GetCodepointSDF(
                fontInfo, scale, codepoint,
                SDF_PIXEL_DIST, (byte) 128,
                (float) SDF_PIXEL_DIST / GLYPH_SIZE * 255f,
                w, h, xoff, yoff
        );

        int[] advance = new int[1], lsb = new int[1];
        STBTruetype.stbtt_GetCodepointHMetrics(fontInfo, codepoint, advance, lsb);

        Glyph glyph;
        if (sdf == null || w[0] == 0 || h[0] == 0) {
            if (sdf != null) STBTruetype.stbtt_FreeSDF(sdf);
            glyph = new Glyph(0, 0, 0, 0, 0, 0, 0, 0, advance[0] * scale, fontHasGlyph);
            glyphMap.put(codepoint, glyph);
            return glyph;
        }

        if (penX + w[0] + 2 > ATLAS_SIZE) {
            penX = 2;
            penY += rowH + 2;
            rowH = 0;
        }
        if (penY + h[0] + 2 > ATLAS_SIZE) {
            STBTruetype.stbtt_FreeSDF(sdf);
            BTexture.getLogger().error("BFont Atlas full! Cannot bake codepoint: " + codepoint);
            glyph = new Glyph(0, 0, 0, 0, 0, 0, 0, 0, advance[0] * scale, fontHasGlyph);
            glyphMap.put(codepoint, glyph);
            return glyph;
        }

        RenderSystem.getDevice().createCommandEncoder()
                .writeToTexture(atlasTexture, sdf, NativeImage.Format.LUMINANCE,
                        0, 0, penX, penY, w[0], h[0]);

        float u0 = (float) penX / ATLAS_SIZE;
        float v0 = (float) penY / ATLAS_SIZE;
        float u1 = (float) (penX + w[0]) / ATLAS_SIZE;
        float v1 = (float) (penY + h[0]) / ATLAS_SIZE;

        glyph = new Glyph(u0, v0, u1, v1, w[0], h[0], xoff[0], yoff[0], advance[0] * scale, fontHasGlyph);
        glyphMap.put(codepoint, glyph);

        penX += w[0] + 2;
        rowH = Math.max(rowH, h[0]);
        STBTruetype.stbtt_FreeSDF(sdf);

        return glyph;
    }

    public Glyph resolve(int codePoint) {
        Glyph g = glyphMap.get(codePoint);
        return g != null ? g : bakeGlyph(codePoint);
    }

    public float[][] getQuads(String text, float x, float y, float size) {
        float scale = size / bakeSize;
        float curX = x;
        float baselineY = y + (ascent * scale);
        List<float[]> quadList = new ArrayList<>();

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            Glyph g = resolve(codePoint);

            if (g.width() > 0 && g.height() > 0) {
                float x0 = curX + g.xOffset() * scale;
                float y0 = baselineY + g.yOffset() * scale;
                float x1 = x0 + g.width() * scale;
                float y1 = y0 + g.height() * scale;

                quadList.add(new float[]{x0, y0, x1, y1, g.u0(), g.v0(), g.u1(), g.v1()});
            }
            curX += g.advance() * scale;
        }
        return quadList.toArray(new float[0][]);
    }

    public FormattedQuads getQuadsFormatted(FormattedCharSequence text, float x, float y, float size, int defaultColor) {
        List<float[]> quadList = new ArrayList<>();
        List<Integer> colorList = new ArrayList<>();
        float scale = size / bakeSize;
        float baselineY = y + (ascent * scale);
        float[] curX = { x };

        text.accept((index, style, codePoint) -> {
            int color = style.getColor() != null
                    ? (0xFF000000 | style.getColor().getValue())
                    : defaultColor;

            Glyph g = resolve(codePoint);
            if (g.width() > 0 && g.height() > 0) {
                float x0 = curX[0] + g.xOffset() * scale;
                float y0 = baselineY + g.yOffset() * scale;
                float x1 = x0 + g.width() * scale;
                float y1 = y0 + g.height() * scale;

                quadList.add(new float[]{x0, y0, x1, y1, g.u0(), g.v0(), g.u1(), g.v1()});
                colorList.add(color);
            }
            curX[0] += g.advance() * scale;
            return true;
        });

        int[] colors = colorList.stream().mapToInt(Integer::intValue).toArray();
        return new FormattedQuads(quadList.toArray(new float[0][]), colors);
    }

    public TextRenderBatch getTextRenderBatch(String text, float x, float y, float size, int color) {
        List<float[]> fontQuads = new ArrayList<>();
        List<Integer> fontColors = new ArrayList<>();
        List<EmojiQuad> emojiQuads = new ArrayList<>();

        float scale = size / bakeSize;
        float baselineY = y + (ascent * scale);
        float curX = x;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            Glyph g = resolve(codePoint);

            if (g.hasGlyph()) {
                if (g.width() > 0 && g.height() > 0) {
                    float x0 = curX + g.xOffset() * scale;
                    float y0 = baselineY + g.yOffset() * scale;
                    float x1 = x0 + g.width() * scale;
                    float y1 = y0 + g.height() * scale;

                    fontQuads.add(new float[]{x0, y0, x1, y1, g.u0(), g.v0(), g.u1(), g.v1()});
                    fontColors.add(color);
                }
                curX += g.advance() * scale;
                continue;
            }

            if (emojiResolver != null) {
                GpuTextureView view = emojiResolver.getEmojiTextureView(codePoint);
                if (view != null) {
                    float x0 = curX;
                    float y0 = y;
                    emojiQuads.add(new EmojiQuad(view, x0, y0, x0 + size, y0 + size));
                    curX += size;
                    continue;
                }
            }

            curX += g.advance() * scale;
        }

        return new TextRenderBatch(fontQuads, fontColors, emojiQuads);
    }

    public TextRenderBatch getTextRenderBatch(FormattedCharSequence text, float x, float y, float size, int defaultColor) {
        List<float[]> fontQuads = new ArrayList<>();
        List<Integer> fontColors = new ArrayList<>();
        List<EmojiQuad> emojiQuads = new ArrayList<>();

        float scale = size / bakeSize;
        float baselineY = y + (ascent * scale);
        float[] curX = { x };

        text.accept((index, style, codePoint) -> {
            int color = style.getColor() != null
                    ? (0xFF000000 | style.getColor().getValue())
                    : defaultColor;

            Glyph g = resolve(codePoint);

            if (g.hasGlyph()) {
                if (g.width() > 0 && g.height() > 0) {
                    float x0 = curX[0] + g.xOffset() * scale;
                    float y0 = baselineY + g.yOffset() * scale;
                    float x1 = x0 + g.width() * scale;
                    float y1 = y0 + g.height() * scale;

                    fontQuads.add(new float[]{x0, y0, x1, y1, g.u0(), g.v0(), g.u1(), g.v1()});
                    fontColors.add(color);
                }
                curX[0] += g.advance() * scale;
                return true;
            }

            if (emojiResolver != null) {
                GpuTextureView view = emojiResolver.getEmojiTextureView(codePoint);
                if (view != null) {
                    float x0 = curX[0];
                    float y0 = y;
                    emojiQuads.add(new EmojiQuad(view, x0, y0, x0 + size, y0 + size));
                    curX[0] += size;
                    return true;
                }
            }

            curX[0] += g.advance() * scale;
            return true;
        });

        return new TextRenderBatch(fontQuads, fontColors, emojiQuads);
    }

    public float textSize(String text, float size) {
        float scale = size / bakeSize;
        float width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            width += resolve(codePoint).advance() * scale;
        }
        return width;
    }

    public boolean hasGlyph(int codePoint) {
        return glyphMap.containsKey(codePoint);
    }

    public Glyph getGlyph(int codePoint) {
        return resolve(codePoint);
    }

    public float getLineHeight(float size) {
        return size;
    }

    public float getAscent(float size) {
        return ascent * (size / bakeSize);
    }

    public void close() {
        if (emojiResolver != null) emojiResolver.close();
        atlasView.close();
        atlasTexture.close();
        if (fontInfo != null) fontInfo.free();
        if (ttfBuf != null) MemoryUtil.memFree(ttfBuf);
    }

    public float getBakeSize(){
        return bakeSize;
    }
    public float getAscent(){
        return ascent;
    }
}