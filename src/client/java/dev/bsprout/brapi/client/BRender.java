package dev.bsprout.brapi.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.*;

public class BRender {
    public static class RoundRectCmd {
        public final int x, y, w, h, color, r1, r2, r3, r4, strokeWidth, layer;

        public RoundRectCmd(int x, int y, int w, int h, int color, int r1, int r2, int r3, int r4, int strokeWidth, int layer) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.color = color; this.r1 = r1; this.r2 = r2;
            this.r3 = r3; this.r4 = r4; this.strokeWidth = strokeWidth;
            this.layer = layer;
        }

        public int x() { return x; }
        public int y() { return y; }
        public int w() { return w; }
        public int h() { return h; }
        public int color() { return color; }
        public int r1() { return r1; }
        public int r2() { return r2; }
        public int r3() { return r3; }
        public int r4() { return r4; }
        public int strokeWidth() { return strokeWidth; }
        public int layer() { return layer; }
    }

    private record RectCmd(int x, int y, int w, int h, int color, int layer) {}

    private final List<RoundRectCmd> roundRects = new ArrayList<>();
    private final List<RectCmd> rects = new ArrayList<>();

    private record TextCmd(BFont font, String text, float x, float y, float size, int color, boolean shadow, boolean centered, int layer, int[] charColors, float[][] bakedQuads) {
        TextCmd(BFont font, String text, float x, float y, float size, int color, boolean shadow, boolean centered, int layer) {
            this(font, text, x, y, size, color, shadow, centered, layer, null, null);
        }
    }

    private final List<TextCmd> texts = new ArrayList<>();

    private record BlurCmd(int x, int y, int w, int h, float strength) {}
    private final List<BlurCmd> blurs = new ArrayList<>();

    public static class GradientRoundRectCmd extends RoundRectCmd {
        public final int colorTL, colorTR, colorBL, colorBR;

        public GradientRoundRectCmd(int x, int y, int w, int h, int r1, int r2, int r3, int r4,
                                    int strokeWidth, int layer,
                                    Gradient gradient, GradientDirection dir) {
            super(x, y, w, h, 0, r1, r2, r3, r4, strokeWidth, layer);
            int[] c = BRender.cornersFromGradient(gradient, dir);
            this.colorTL = c[0]; this.colorTR = c[1];
            this.colorBL = c[2]; this.colorBR = c[3];
        }

        public int colorTL() { return colorTL; }
        public int colorTR() { return colorTR; }
        public int colorBL() { return colorBL; }
        public int colorBR() { return colorBR; }
    }

    public static GuiGraphics pendingGraphics;

    public record DrawEntry(int layer, int type, Object cmd) {}
    public static final List<DrawEntry> DRAW_LIST = new ArrayList<>();
    // Each entry: { layer, type, cmd }
    // type: 0=roundrect, 1=rect, 2=text, 3=texture, 4=nineslice

    private record TextureCmd(
            BTexture texture,
            float x, float y, float w, float h,
            float u0, float v0, float u1, float v1,
            int color,
            boolean tile,
            boolean linear,
            int layer
    ) {}

    private record NineSliceCmd(
            NineSlice slice,
            float x, float y, float w, float h,
            int color,
            boolean linear,
            int layer
    ) {}

    private final List<TextureCmd> textures = new ArrayList<>();
    private final List<NineSliceCmd> nineSlices = new ArrayList<>();

    private static final int MAX_RECTS = 4096;
    // Persistent native staging buffer for rect data - never freed
    private static final ByteBuffer RECT_DATA_STAGING = MemoryUtil.memAlloc(MAX_RECTS * 48);
    // Persistent per-rect vertex GPU buffers - created once
    private static GpuBuffer[] vertexGpuBufs = null;
    // Single persistent rect data GPU buffer
    private static GpuBuffer rectDataGpuBuf = null;

    // Example: bRender.roundRect(100, 100, 100, 100, 0xFFFF0000, 10, 1);
    public void roundRect(int x, int y, int w, int h, int color, int radius, int layer) {
        roundRects.add(new RoundRectCmd(x, y, w, h, color, radius, radius, radius, radius, 0, layer));
    }

    // Example: bRender.roundRect(100, 100, 100, 100, 0xFFFF0000, 10, 5, 20, 30, 1);
    public void roundRect(int x, int y, int w, int h, int color, int r1, int r2, int r3, int r4, int layer) {
        roundRects.add(new RoundRectCmd(x, y, w, h, color, r1, r2, r3, r4, 0, layer));
    }

    // R1 = top-left/bottom-right, R2 = top-right/bottom-left
    // Example: bRender.roundRect(100, 100, 100, 100, 0xFFFF0000, 10, 20, 1);
    @Deprecated // use 4 radius method instead
    public void roundRect(int x, int y, int w, int h, int color, int r1, int r2, int layer) {
        roundRects.add(new RoundRectCmd(x, y, w, h, color, r1, r2, r1, r2, 0, layer));
    }

    // Example: bRender.circle(100, 100, 50, 0xFFFF0000, 1);
    public void circle(int x, int y, int radius, int color, int layer) {
        roundRects.add(new RoundRectCmd(x, y, radius * 2, radius * 2, color, radius, radius, radius, radius, 0, layer));
    }

    // Example: bRender.rect(100, 100, 100, 100, 0xFFFF0000, 1);
    public void rect(int x, int y, int w, int h, int color, int layer) {
        rects.add(new RectCmd(x, y, w, h, color, layer));
    }

    // Example: bRender.stroke(100, 100, 100, 100, 0xFFFF0000, 2, 1);
    public void stroke(int x, int y, int w, int h, int color, int strokeWidth, int layer) {
        roundRects.add(new RoundRectCmd(x, y, w, h, color, 0, 0, 0, 0, strokeWidth, layer));
    }

    // Example: bRender.strokeRounded(100, 100, 100, 100, 0xFFFF0000, 10, 2, 1);
    public void strokeRounded(int x, int y, int w, int h, int color, int radius, int strokeWidth, int layer) {
        roundRects.add(new RoundRectCmd(x, y, w, h, color, radius, radius, radius, radius, strokeWidth, layer));
    }

    // R1 = top-left/bottom-right, R2 = top-right/bottom-left
    // Example: bRender.strokeRounded(100, 100, 100, 100, 0xFFFF0000, 10, 5, 2, 1);
    @Deprecated
    public void strokeRounded(int x, int y, int w, int h, int color, int r1, int r2, int strokeWidth, int layer) {
        roundRects.add(new RoundRectCmd(x, y, w, h, color, r1, r2, r1, r2, strokeWidth, layer));
    }

    // Example: bRender.strokeRounded(100, 100, 100, 100, 0xFFFF0000, 10, 5, 20, 40, 2, 1);
    public void strokeRounded(int x, int y, int w, int h, int color, int r1, int r2, int r3, int r4, int strokeWidth, int layer) {
        roundRects.add(new RoundRectCmd(x, y, w, h, color, r1, r2, r3, r4, strokeWidth, layer));
    }

    // Example: bRender.roundRectStroked(100, 100, 100, 100, 0xFF0000FF, 0xFFFF0000, 10, 2, 1);
    public void roundRectStroked(int x, int y, int w, int h, int fillColor, int strokeColor, int radius, int strokeWidth, int layer) {
        roundRects.add(new RoundRectCmd(x, y, w, h, fillColor, radius, radius, radius, radius, 0, layer));
        roundRects.add(new RoundRectCmd(x, y, w, h, strokeColor, radius, radius, radius, radius, strokeWidth, layer));
    }

    // R1 = top-left/bottom-right, R2 = top-right/bottom-left
    // Example: bRender.roundRectStroked(100, 100, 100, 100, 0xFF0000FF, 0xFFFF0000, 10, 5, 2, 1);
    @Deprecated
    public void roundRectStroked(int x, int y, int w, int h, int fillColor, int strokeColor, int r1, int r2, int strokeWidth, int layer) {
        roundRects.add(new RoundRectCmd(x, y, w, h, fillColor, r1, r2, r1, r2, 0, layer));
        roundRects.add(new RoundRectCmd(x, y, w, h, strokeColor, r1, r2, r1, r2, strokeWidth, layer));
    }

    // Example: bRender.roundRectStroked(100, 100, 100, 100, 0xFF0000FF, 0xFFFF0000, 10, 5, 20, 40, 2, 1);
    public void roundRectStroked(int x, int y, int w, int h, int fillColor, int strokeColor, int r1, int r2, int r3, int r4, int strokeWidth, int layer) {
        roundRects.add(new RoundRectCmd(x, y, w, h, fillColor, r1, r2, r3, r4, 0, layer));
        roundRects.add(new RoundRectCmd(x, y, w, h, strokeColor, r1, r2, r3, r4, strokeWidth, layer));
    }

    // Example: bRender.drawText(myFont, "Hello!", 100, 100, 24, 0xFFFFFFFF, 1);
    public void drawText(BFont font, String text, float x, float y, float size, int color, int layer) {
        if (text == null || text.isEmpty()) return;
        texts.add(new TextCmd(font, text, x, y, size, color, false, false, layer));
    }

    // Example: bRender.drawText(myFont, Component.nullToEmpty("§cT§eh§ai§bs §dt§5e§cx§et §eis §ar§ba§di§5n§cb§eo§aw").getVisualOrderText(), 100, 100, 24, 0xFFFFFFFF, 1);
    public void drawText(BFont font, FormattedCharSequence text, float x, float y, float size, int defaultColor, int layer) {
        if (text == null) return;
        BFont.FormattedQuads fq = font.getQuadsFormatted(text, x, y, size, defaultColor);
        if (fq.quads() == null || fq.quads().length == 0) return;
        texts.add(new TextCmd(font, "", x, y, size, defaultColor, false, false, layer, fq.colors(), fq.quads()));
    }

    // Example: bRender.drawTextShadow(myFont, "Hello!", 100, 100, 24, 0xFFFFFFFF, 1);
    public void drawTextShadow(BFont font, String text, float x, float y, float size, int color, int layer) {
        if (text == null || text.isEmpty()) return;
        texts.add(new TextCmd(font, text, x, y, size, color, true, false, layer));
    }

    // this will NOT fallback to mc font for now
    public void drawText(String text, float x, float y, int color, int layer) {
        texts.add(new TextCmd(null, text, x, y, 0, color, false, false, layer));
    }
    public void drawTextShadow(String text, float x, float y, int color, int layer) {
        texts.add(new TextCmd(null, text, x, y, 0, color, true, false, layer));
    }
    public void drawTextCentered(String text, float x, float y, int color, int layer) {
        texts.add(new TextCmd(null, text, x, y, 0, color, false, true, layer));
    }

    // Stretch texture to fill region
    // Example: bRender.drawTexture(tex, 100, 100, 200, 150, 0xFFFFFFFF, 1);
    public void drawTexture(BTexture texture, float x, float y, float w, float h, int color, boolean linear, int layer) {
        textures.add(new TextureCmd(texture, x, y, w, h, 0, 0, 1, 1, color, false, linear, layer));
    }

    // Crop - draw sub-region of texture
    // srcX, srcY, srcW, srcH in pixels
    // Example: bRender.drawTextureCropped(tex, 100, 100, 64, 64, 0, 0, 32, 32, 0xFFFFFFFF, 1);
    public void drawTextureCropped(BTexture texture, float x, float y, float w, float h,
                                   int srcX, int srcY, int srcW, int srcH, int color, boolean linear, int layer) {
        float u0 = (float) srcX / texture.width;
        float v0 = (float) srcY / texture.height;
        float u1 = (float) (srcX + srcW) / texture.width;
        float v1 = (float) (srcY + srcH) / texture.height;
        textures.add(new TextureCmd(texture, x, y, w, h, u0, v0, u1, v1, color, false, linear, layer));
    }

    // Tile texture across region
    // Example: bRender.drawTextureTiled(tex, 100, 100, 200, 200, 0xFFFFFFFF, 1);
    public void drawTextureTiled(BTexture texture, float x, float y, float w, float h, int color, boolean linear, int layer) {
        float u1 = w / texture.width;
        float v1 = h / texture.height;
        textures.add(new TextureCmd(texture, x, y, w, h, 0, 0, u1, v1, color, true, linear, layer));
    }

    // 9-slice
    // Example: bRender.drawTexture9Slice(slice, 100, 100, 300, 200, 0xFFFFFFFF, 1);
    public void drawTexture9Slice(NineSlice slice, float x, float y, float w, float h, int color, boolean linear, int layer) {
        nineSlices.add(new NineSliceCmd(slice, x, y, w, h, color, linear, layer));
    }


    // roundRect
    public void roundRect(int x, int y, int w, int h, Gradient g, GradientDirection dir, int radius, int layer) {
        roundRects.add(new GradientRoundRectCmd(x, y, w, h, radius, radius, radius, radius, 0, layer, g, dir));
    }
    public void roundRect(int x, int y, int w, int h, Gradient g, GradientDirection dir, int r1, int r2, int r3, int r4, int layer) {
        roundRects.add(new GradientRoundRectCmd(x, y, w, h, r1, r2, r3, r4, 0, layer, g, dir));
    }
    public void roundRect(int x, int y, int w, int h, Gradient g, GradientDirection dir, int r1, int r2, int layer) {
        roundRects.add(new GradientRoundRectCmd(x, y, w, h, r1, r2, r1, r2, 0, layer, g, dir));
    }

    // rect
    public void rect(int x, int y, int w, int h, Gradient g, GradientDirection dir, int layer) {
        roundRects.add(new GradientRoundRectCmd(x, y, w, h, 0, 0, 0, 0, 0, layer, g, dir));
    }

    // circle
    public void circle(int x, int y, int radius, Gradient g, GradientDirection dir, int layer) {
        roundRects.add(new GradientRoundRectCmd(x, y, radius*2, radius*2, radius, radius, radius, radius, 0, layer, g, dir));
    }

    // strokeRounded
    public void strokeRounded(int x, int y, int w, int h, Gradient g, GradientDirection dir, int radius, int strokeWidth, int layer) {
        roundRects.add(new GradientRoundRectCmd(x, y, w, h, radius, radius, radius, radius, strokeWidth, layer, g, dir));
    }
    public void strokeRounded(int x, int y, int w, int h, Gradient g, GradientDirection dir, int r1, int r2, int strokeWidth, int layer) {
        roundRects.add(new GradientRoundRectCmd(x, y, w, h, r1, r2, r1, r2, strokeWidth, layer, g, dir));
    }
    public void strokeRounded(int x, int y, int w, int h, Gradient g, GradientDirection dir, int r1, int r2, int r3, int r4, int strokeWidth, int layer) {
        roundRects.add(new GradientRoundRectCmd(x, y, w, h, r1, r2, r3, r4, strokeWidth, layer, g, dir));
    }

    // roundRectStroked (fill gradient, solid stroke)
    public void roundRectStroked(int x, int y, int w, int h, Gradient fillGradient, GradientDirection dir, int strokeColor, int radius, int strokeWidth, int layer) {
        roundRects.add(new GradientRoundRectCmd(x, y, w, h, radius, radius, radius, radius, 0, layer, fillGradient, dir));
        roundRects.add(new RoundRectCmd(x, y, w, h, strokeColor, radius, radius, radius, radius, strokeWidth, layer));
    }

    // stroke (outline only, gradient)
    public void stroke(int x, int y, int w, int h, Gradient g, GradientDirection dir, int strokeWidth, int layer) {
        roundRects.add(new GradientRoundRectCmd(x, y, w, h, 0, 0, 0, 0, strokeWidth, layer, g, dir));
    }


    // Call after adding all elements you want
    public void flush(GuiGraphics graphics) {
        pendingGraphics = graphics;
        for (RoundRectCmd c : roundRects) DRAW_LIST.add(new DrawEntry(c.layer(), 0, c));
        // Convert plain rects to round rects with r=0, reuse type 0
        for (RectCmd c : rects) DRAW_LIST.add(new DrawEntry(c.layer(), 0,
                new RoundRectCmd(c.x(), c.y(), c.w(), c.h(), c.color(), 0, 0, 0, 0, 0, c.layer())));
        for (TextCmd c : texts)           DRAW_LIST.add(new DrawEntry(c.layer(), 2, c));
        for (TextureCmd c : textures)     DRAW_LIST.add(new DrawEntry(c.layer(), 3, c));
        for (NineSliceCmd c : nineSlices) DRAW_LIST.add(new DrawEntry(c.layer(), 4, c));

        roundRects.clear(); rects.clear(); texts.clear();
        textures.clear(); nineSlices.clear(); blurs.clear();
    }

    /* Actually render everything (you shouldn't run this, its ran automagically)
       if you want you can steal this for your custom needs...
     */
    public static void flushAll() {
        if (DRAW_LIST.isEmpty()) return;

        DRAW_LIST.sort(Comparator.comparingInt(DrawEntry::layer));

        // Batch consecutive round rects for drawMultipleIndexed fast path
        List<RoundRectCmd> rrBatch = new ArrayList<>();

        for (DrawEntry entry : DRAW_LIST) {
            if (entry.type() != 0 && !rrBatch.isEmpty()) {
                flushRoundRectBatch(rrBatch);
                rrBatch.clear();
            }

            switch (entry.type()) {
                case 0 -> rrBatch.add((RoundRectCmd) entry.cmd());
                case 2 -> flushTextCmd((TextCmd) entry.cmd(), pendingGraphics);
                case 3 -> {
                    TextureCmd c = (TextureCmd) entry.cmd();
                    // get correct sampler
                    Minecraft mc = Minecraft.getInstance();
                    RenderTarget rt = mc.getMainRenderTarget();
                    GpuBufferSlice dt = RenderSystem.getDynamicUniforms().writeTransform(
                            new Matrix4f().setTranslation(0, 0, -11000),
                            new Vector4f(1,1,1,1), new Vector3f(), new Matrix4f()
                    );
                    GpuSampler sampler = c.tile()
                            ? (c.linear() ? RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR)
                            : RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST))
                            : (c.linear() ? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                            : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    drawTextureQuad(rt, dt, sampler, c.texture().view,
                            c.x(), c.y(), c.w(), c.h(),
                            c.u0(), c.v0(), c.u1(), c.v1(), c.color());
                }
                case 4 -> {
                    NineSliceCmd c = (NineSliceCmd) entry.cmd();
                    Minecraft mc = Minecraft.getInstance();
                    RenderTarget rt = mc.getMainRenderTarget();
                    GpuBufferSlice dt = RenderSystem.getDynamicUniforms().writeTransform(
                            new Matrix4f().setTranslation(0, 0, -11000),
                            new Vector4f(1,1,1,1), new Vector3f(), new Matrix4f()
                    );
                    GpuSampler sampler = c.linear()
                            ? RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
                            : RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                    drawNineSlice(rt, dt, sampler, c);
                }
            }
        }

        // Flush any trailing round rect batch
        if (!rrBatch.isEmpty()) flushRoundRectBatch(rrBatch);

        DRAW_LIST.clear();
    }
    private static void flushTextCmd(TextCmd cmd, GuiGraphics graphics) {
        if (cmd.font() == null) {
            // Use Minecraft font via GuiGraphics
            Font mcFont = Minecraft.getInstance().font;
            if (cmd.centered()) {
                graphics.drawCenteredString(mcFont, cmd.text(), (int)cmd.x(), (int)cmd.y(), cmd.color());
            } else {
                graphics.drawString(mcFont, cmd.text(), (int)cmd.x(), (int)cmd.y(), cmd.color(), cmd.shadow());
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget renderTarget = mc.getMainRenderTarget();

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(
                        new Matrix4f().setTranslation(0.0F, 0.0F, -11000.0F),
                        new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                        new Vector3f(),
                        new Matrix4f()
                );

        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        float[][] quads = cmd.bakedQuads() != null
                ? cmd.bakedQuads()
                : cmd.font().getQuads(cmd.text(), cmd.x(), cmd.y(), cmd.size());

        ByteBuffer verts = MemoryUtil.memAlloc(quads.length * 4 * 24);

        for (int i = 0; i < quads.length; i++) {
            float[] q = quads[i];
            int color = (cmd.charColors() != null && i < cmd.charColors().length)
                    ? cmd.charColors()[i]
                    : cmd.color();
            float x0 = q[0], y0 = q[1], x1 = q[2], y1 = q[3];
            float u0 = q[4], v0 = q[5], u1 = q[6], v1 = q[7];
            putTextVertex(verts, x0, y0, u0, v0, color);
            putTextVertex(verts, x0, y1, u0, v1, color);
            putTextVertex(verts, x1, y1, u1, v1, color);
            putTextVertex(verts, x1, y0, u1, v0, color);
        }
        verts.flip();

        int indexCount = quads.length * 6;
        RenderSystem.AutoStorageIndexBuffer indexStorage =
                RenderSystem.getSequentialBuffer(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        GpuBuffer indexBuf = indexStorage.getBuffer(indexCount);

        GpuBuffer vertexBuf = RenderSystem.getDevice().createBuffer(
                () -> "BRender text vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                verts.remaining()
        );
        RenderSystem.getDevice().createCommandEncoder()
                .writeToBuffer(vertexBuf.slice(), verts);
        MemoryUtil.memFree(verts);

        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "BRender text",
                        renderTarget.getColorTextureView(),
                        OptionalInt.empty(),
                        renderTarget.useDepth ? renderTarget.getDepthTextureView() : null,
                        OptionalDouble.empty()
                )) {
            pass.setPipeline(Brapi.TEXT_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.bindTexture("Sampler0", cmd.font().atlasView, sampler);
            pass.setVertexBuffer(0, vertexBuf);
            pass.setIndexBuffer(indexBuf, indexStorage.type());
            pass.drawIndexed(0, 0, indexCount, 1);
        }

        vertexBuf.close();
    }
    private static void flushRoundRectBatch(List<RoundRectCmd> batch) {
        if (batch.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget renderTarget = mc.getMainRenderTarget();
        Window window = mc.getWindow();
        int guiScale = window.getGuiScale();
        float screenH = window.getHeight();

        int count = Math.min(batch.size(), MAX_RECTS);

        if (vertexGpuBufs == null) {
            vertexGpuBufs = new GpuBuffer[MAX_RECTS];
            for (int j = 0; j < MAX_RECTS; j++) {
                vertexGpuBufs[j] = RenderSystem.getDevice().createBuffer(
                        () -> "BRender vertex buffer",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                        (long) 4 * 16
                );
            }
        }
        if (rectDataGpuBuf == null) {
            rectDataGpuBuf = RenderSystem.getDevice().createBuffer(
                    () -> "BRender RectData buffer",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    (long) MAX_RECTS * 48
            );
        }

        RECT_DATA_STAGING.clear();

        RenderSystem.AutoStorageIndexBuffer indexStorage = RenderSystem.getSequentialBuffer(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        GpuBuffer indexBuf = indexStorage.getBuffer(6);

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(
                        new Matrix4f().setTranslation(0.0F, 0.0F, -11000.0F),
                        new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                        new Vector3f(),
                        new Matrix4f()
                );

        List<RenderPass.Draw<Void>> draws = new ArrayList<>(count);
        ByteBuffer verts = MemoryUtil.memAlloc(4 * 16);

        for (int i = 0; i < count; i++) {
            RoundRectCmd cmd = batch.get(i);

            verts.clear();
            if (cmd instanceof GradientRoundRectCmd g) {
                putVertex(verts, g.x(),         g.y(),         g.colorTL());
                putVertex(verts, g.x(),         g.y() + g.h(), g.colorBL());
                putVertex(verts, g.x() + g.w(), g.y() + g.h(), g.colorBR());
                putVertex(verts, g.x() + g.w(), g.y(),         g.colorTR());
            } else {
                putVertex(verts, cmd.x(),         cmd.y(),         cmd.color());
                putVertex(verts, cmd.x(),         cmd.y() + cmd.h(), cmd.color());
                putVertex(verts, cmd.x() + cmd.w(), cmd.y() + cmd.h(), cmd.color());
                putVertex(verts, cmd.x() + cmd.w(), cmd.y(),         cmd.color());
            }
            verts.flip();
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToBuffer(vertexGpuBufs[i].slice(), verts);

            RECT_DATA_STAGING.putFloat(cmd.x() * guiScale);
            RECT_DATA_STAGING.putFloat(screenH - (cmd.y() + cmd.h()) * guiScale);
            RECT_DATA_STAGING.putFloat(cmd.w() * guiScale);
            RECT_DATA_STAGING.putFloat(cmd.h() * guiScale);
            RECT_DATA_STAGING.putFloat(cmd.r1() * guiScale);
            RECT_DATA_STAGING.putFloat(cmd.r2() * guiScale);
            RECT_DATA_STAGING.putFloat(cmd.r3() * guiScale);
            RECT_DATA_STAGING.putFloat(cmd.r4() * guiScale);
            RECT_DATA_STAGING.putFloat(cmd.strokeWidth() * guiScale);
            RECT_DATA_STAGING.putFloat(0).putFloat(0).putFloat(0);

            final int rectDataOffset = i * 48;
            draws.add(new RenderPass.Draw<>(
                    0,
                    vertexGpuBufs[i],
                    indexBuf,
                    indexStorage.type(),
                    0,
                    6,
                    (unused, uploader) -> uploader.upload(
                            "RectData",
                            rectDataGpuBuf.slice(rectDataOffset, 48)
                    )
            ));
        }

        MemoryUtil.memFree(verts);

        RECT_DATA_STAGING.flip();
        RenderSystem.getDevice().createCommandEncoder()
                .writeToBuffer(rectDataGpuBuf.slice(0, (long) count * 48), RECT_DATA_STAGING);

        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "BRender rounded rects",
                        renderTarget.getColorTextureView(),
                        OptionalInt.empty(),
                        renderTarget.useDepth ? renderTarget.getDepthTextureView() : null,
                        OptionalDouble.empty()
                )) {
            pass.setPipeline(Brapi.ROUNDED_RECT_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.drawMultipleIndexed(draws, null, null, List.of("RectData"), null);
        }
    }

    private static void drawNineSlice(RenderTarget renderTarget, GpuBufferSlice dynamicTransforms,
                                      GpuSampler sampler, NineSliceCmd cmd) {
        NineSlice s = cmd.slice();
        BTexture tex = s.texture();
        float x = cmd.x(), y = cmd.y(), w = cmd.w(), h = cmd.h();
        int color = cmd.color();

        int bt = s.borderTop(), br = s.borderRight(), bb = s.borderBottom(), bl = s.borderLeft();

        // UV fractions
        float ubl = (float) bl / tex.width;
        float ubr = (float) (tex.width - br) / tex.width;
        float vbt = (float) bt / tex.height;
        float vbb = (float) (tex.height - bb) / tex.height;

        // Screen positions
        float xbl = x + bl, xbr = x + w - br;
        float ybt = y + bt, ybb = y + h - bb;

        // 9 quads: corners, edges, center
        // Top-left corner
        drawTextureQuad(renderTarget, dynamicTransforms, sampler, tex.view,
                x, y, bl, bt, 0, 0, ubl, vbt, color);
        // Top-right corner
        drawTextureQuad(renderTarget, dynamicTransforms, sampler, tex.view,
                xbr, y, br, bt, ubr, 0, 1, vbt, color);
        // Bottom-left corner
        drawTextureQuad(renderTarget, dynamicTransforms, sampler, tex.view,
                x, ybb, bl, bb, 0, vbb, ubl, 1, color);
        // Bottom-right corner
        drawTextureQuad(renderTarget, dynamicTransforms, sampler, tex.view,
                xbr, ybb, br, bb, ubr, vbb, 1, 1, color);
        // Top edge
        drawTextureQuad(renderTarget, dynamicTransforms, sampler, tex.view,
                xbl, y, w - bl - br, bt, ubl, 0, ubr, vbt, color);
        // Bottom edge
        drawTextureQuad(renderTarget, dynamicTransforms, sampler, tex.view,
                xbl, ybb, w - bl - br, bb, ubl, vbb, ubr, 1, color);
        // Left edge
        drawTextureQuad(renderTarget, dynamicTransforms, sampler, tex.view,
                x, ybt, bl, h - bt - bb, 0, vbt, ubl, vbb, color);
        // Right edge
        drawTextureQuad(renderTarget, dynamicTransforms, sampler, tex.view,
                xbr, ybt, br, h - bt - bb, ubr, vbt, 1, vbb, color);
        // Center
        drawTextureQuad(renderTarget, dynamicTransforms, sampler, tex.view,
                xbl, ybt, w - bl - br, h - bt - bb, ubl, vbt, ubr, vbb, color);
    }

    private static void drawTextureQuad(RenderTarget renderTarget, GpuBufferSlice dynamicTransforms,
                                        GpuSampler sampler, GpuTextureView view,
                                        float x, float y, float w, float h,
                                        float u0, float v0, float u1, float v1,
                                        int color) {
        ByteBuffer verts = MemoryUtil.memAlloc(4 * 24);
        putTextVertex(verts, x,     y,     u0, v0, color);
        putTextVertex(verts, x,     y + h, u0, v1, color);
        putTextVertex(verts, x + w, y + h, u1, v1, color);
        putTextVertex(verts, x + w, y,     u1, v0, color);
        verts.flip();

        GpuBuffer vertexBuf = RenderSystem.getDevice().createBuffer(
                () -> "BRender texture vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                verts.remaining()
        );
        RenderSystem.getDevice().createCommandEncoder()
                .writeToBuffer(vertexBuf.slice(), verts);
        MemoryUtil.memFree(verts);

        RenderSystem.AutoStorageIndexBuffer indexStorage =
                RenderSystem.getSequentialBuffer(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS);
        GpuBuffer indexBuf = indexStorage.getBuffer(6);

        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "BRender texture",
                        renderTarget.getColorTextureView(),
                        OptionalInt.empty(),
                        renderTarget.useDepth ? renderTarget.getDepthTextureView() : null,
                        OptionalDouble.empty()
                )) {
            pass.setPipeline(Brapi.TEXTURE_PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.bindTexture("Sampler0", view, sampler);
            pass.setVertexBuffer(0, vertexBuf);
            pass.setIndexBuffer(indexBuf, indexStorage.type());
            pass.drawIndexed(0, 0, 6, 1);
        }

        vertexBuf.close();
    }

    private static void putTextVertex(ByteBuffer buf, float x, float y, float u, float v, int color) {
        buf.putFloat(x);
        buf.putFloat(y);
        buf.putFloat(0);
        buf.putFloat(u);
        buf.putFloat(v);
        buf.put((byte)((color >> 16) & 0xFF)); // r
        buf.put((byte)((color >> 8)  & 0xFF)); // g
        buf.put((byte)(color         & 0xFF)); // b
        buf.put((byte)((color >> 24) & 0xFF)); // a
    }

    private static void putVertex(ByteBuffer buf, int x, int y, int color) {
        buf.putFloat(x);
        buf.putFloat(y);
        buf.putFloat(0);
        buf.put((byte)((color >> 16) & 0xFF)); // r
        buf.put((byte)((color >> 8)  & 0xFF)); // g
        buf.put((byte)(color         & 0xFF)); // b
        buf.put((byte)((color >> 24) & 0xFF)); // a
    }

    public static int[] cornersFromGradient(Gradient g, GradientDirection dir) {
        // returns [TL, TR, BL, BR]
        return switch (dir) {
            case LEFT_RIGHT ->  new int[]{ g.sample(0), g.sample(1), g.sample(0), g.sample(1) };
            case TOP_BOTTOM ->  new int[]{ g.sample(0), g.sample(0), g.sample(1), g.sample(1) };
            case TOP_LEFT_BOTTOM_RIGHT -> new int[]{ g.sample(0), g.sample(0.5f), g.sample(0.5f), g.sample(1) };
            case TOP_RIGHT_BOTTOM_LEFT -> new int[]{ g.sample(0.5f), g.sample(0), g.sample(1), g.sample(0.5f) };
        };
    }
}