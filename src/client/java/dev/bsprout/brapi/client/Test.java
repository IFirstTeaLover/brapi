package dev.bsprout.brapi.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * TODO: Make it by myself
 */
public class Test extends Screen {
    private final BRender bRender = new BRender();

    // Static assets - persistent across screen instances
    public static BFont font;
    public static BTexture texture;
    public static NineSlice nineSlice;

    // Mouse follower state
    private float followerX = 0, followerY = 0;
    private float targetX = 0, targetY = 0;

    // Scrolling state (2D offsets)
    private static final float CONTENT_WIDTH = 1000f;
    private static final float CONTENT_HEIGHT = 850f;
    private static final float SCROLL_SMOOTHING = 0.25f;
    private static final float SCROLL_STEP = 40f;

    private float targetScrollX = 0f;
    private float currentScrollX = 0f;
    private float targetScrollY = 0f;
    private float currentScrollY = 0f;

    public Test() {
        super(Component.literal("BRender Test Screen"));
    }

    @Override
    protected void init() {
        followerX = this.width / 2f;
        followerY = this.height / 2f;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        targetX = (float) mouseX;
        targetY = (float) mouseY;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Handle vertical scroll
        targetScrollY -= (float) scrollY * SCROLL_STEP;
        targetScrollY = clampScrollY(targetScrollY);

        // Handle horizontal scroll (Shift + Wheel or trackpad)
        targetScrollX -= (float) scrollX * SCROLL_STEP;
        targetScrollX = clampScrollX(targetScrollX);

        return true;
    }

    private float clampScrollY(float value) {
        float maxScrollY = Math.max(0f, CONTENT_HEIGHT - this.height);
        return Math.max(0f, Math.min(value, maxScrollY));
    }

    private float clampScrollX(float value) {
        float maxScrollX = Math.max(0f, CONTENT_WIDTH - this.width);
        return Math.max(0f, Math.min(value, maxScrollX));
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Interpolate smooth 2D scrolling
        currentScrollX += (targetScrollX - currentScrollX) * SCROLL_SMOOTHING;
        if (Math.abs(targetScrollX - currentScrollX) < 0.05f) currentScrollX = targetScrollX;

        currentScrollY += (targetScrollY - currentScrollY) * SCROLL_SMOOTHING;
        if (Math.abs(targetScrollY - currentScrollY) < 0.05f) currentScrollY = targetScrollY;

        // Interpolate mouse follower position
        float speed = 0.15f;
        followerX += (targetX - followerX) * speed;
        followerY += (targetY - followerY) * speed;

        // Safety check for assets
        if (font == null || texture == null) return;

        // Calculate current 2D offset vector
        float offsetX = -currentScrollX;
        float offsetY = -currentScrollY;

        // ==================== FILLED SHAPES ====================
        bRender.drawText(font, "--- Filled Rects ---", 10, 10, 12, 0xFFAAAAAA, 1);
        bRender.rect(10, 30, 80, 30, 0xFFFF0000, 1);
        bRender.rect(100, 30, 80, 30, new Gradient(0xFFFF0000, 0xFF0000FF), GradientDirection.LEFT_RIGHT, 1);
        bRender.rect(190, 30, 80, 30, new Gradient(0xFF00FF00, 0xFF000000), GradientDirection.TOP_BOTTOM, 1);
        bRender.rect(280, 30, 80, 30, new Gradient(0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF), GradientDirection.TOP_LEFT_BOTTOM_RIGHT, 1);

        // ==================== ROUNDED RECTS ====================
        bRender.drawText(font, "--- Rounded Rects ---", 10, 75, 12, 0xFFAAAAAA, 1);
        bRender.roundRect(10, 95, 80, 30, 0xFF00FF00, 8, 1);
        bRender.roundRect(100, 95, 80, 30, new Gradient(0xFF00FF00, 0xFF0000FF), GradientDirection.LEFT_RIGHT, 8, 1);
        bRender.roundRect(190, 95, 80, 30, 0xFF0000FF, 16, 4, 1);
        bRender.roundRect(280, 95, 80, 30, new Gradient(0xFFFF8800, 0xFF8800FF), GradientDirection.TOP_BOTTOM, 16, 4, 1);
        bRender.roundRect(370, 95, 80, 30, 0xFFFFFF00, 0, 8, 16, 4, 1);
        bRender.roundRect(460, 95, 80, 30, new Gradient(0xFFFF0088, 0xFF00FFFF), GradientDirection.TOP_RIGHT_BOTTOM_LEFT, 0, 8, 16, 4, 1);

        // ==================== CIRCLES ====================
        bRender.drawText(font, "--- Circles ---", 10, 140, 12, 0xFFAAAAAA, 1);
        bRender.circle(30, 180, 20, 0xFFFF00FF, 1);
        bRender.circle(90, 180, 20, new Gradient(0xFFFF0000, 0xFF0000FF), GradientDirection.TOP_BOTTOM, 1);
        bRender.circle(150, 180, 20, new Gradient(0xFF00FF00, 0xFFFF8800, 0xFF0000FF), GradientDirection.LEFT_RIGHT, 1);

        // ==================== STROKES ====================
        bRender.drawText(font, "--- Strokes ---", 10, 215, 12, 0xFFAAAAAA, 1);
        bRender.stroke(10, 235, 80, 30, 0xFFFFFFFF, 2, 1);
        bRender.stroke(100, 235, 80, 30, new Gradient(0xFFFF0000, 0xFF00FFFF), GradientDirection.LEFT_RIGHT, 2, 1);
        bRender.strokeRounded(190, 235, 80, 30, 0xFFFFFFFF, 8, 2, 1);
        bRender.strokeRounded(280, 235, 80, 30, new Gradient(0xFF00FF00, 0xFFFF8800), GradientDirection.TOP_BOTTOM, 8, 2, 1);
        bRender.strokeRounded(370, 235, 80, 30, 0xFFFFFFFF, 16, 4, 2, 1);
        bRender.strokeRounded(460, 235, 80, 30, new Gradient(0xFFFF0088, 0xFF8800FF), GradientDirection.LEFT_RIGHT, 0, 8, 16, 4, 2, 1);

        // ==================== FILLED + STROKE ====================
        bRender.drawText(font, "--- Filled + Stroke ---", 10, 280, 12, 0xFFAAAAAA, 1);
        bRender.roundRectStroked(10, 300, 80, 30, 0xFF1144AA, 0xFFFFFFFF, 8, 2, 1);
        bRender.roundRectStroked(100, 300, 80, 30, 0xFF228844, 0xFFFFFF00, 16, 4, 2, 1);
        bRender.roundRectStroked(190, 300, 80, 30, 0xFF884422, 0xFF00FFFF, 0, 8, 16, 4, 2, 1);
        bRender.roundRectStroked(280, 300, 80, 30, new Gradient(0xFF0044FF, 0xFF00FFAA), GradientDirection.LEFT_RIGHT, 0xFFFFFFFF, 8, 2, 1);

        // ==================== TEXT (ASCII & SIZING) ====================
        bRender.drawText(font, "--- Text (ASCII & Sizing) ---", 10, 345, 12, 0xFFAAAAAA, 1);
        bRender.drawText(font, "Text at 10px", 10, 365, 10, 0xFFFFFFFF, 1);
        bRender.drawText(font, "Text at 20px", 120, 365, 20, 0xFFFFFF00, 1);
        bRender.drawText(font, "Text at 32px", 280, 365, 32, 0xFFFF8800, 1);

        bRender.drawText(font, "Red 16px", 10, 410, 16, 0xFFFF0000, 1);
        bRender.drawText(font, "Green 16px", 120, 410, 16, 0xFF00FF00, 1);
        bRender.drawText(font, "Blue 16px", 240, 410, 16, 0xFF0088FF, 1);

        // ==================== TEXT (NON-ASCII + EMOJI) ====================
        bRender.drawText(font, "--- Text (Non-ASCII & Emoji) ---", 10, 465, 12, 0xFFAAAAAA, 1);
        bRender.drawText(font, "Привет, мир! 🚀🔥", 10, 485, 20, 0xFFFFFFFF, 1);
        bRender.drawText(font, "Ελληνικά κείμενο ✨", 10, 510, 18, 0xFFAADDFF, 1);
        bRender.drawText(font, "Türkçe: ğüşıöç 🌍", 10, 533, 18, 0xFFFFDD88, 1);
        bRender.drawText(font, "日本語テスト 🎌", 10, 556, 18, 0xFFFF88DD, 1);
        bRender.drawText(font, "Emoji row: 🎉🐍🍕🚗🌈", 10, 579, 22, 0xFFFFFFFF, 1);

        // ==================== TEXTURES ====================
        bRender.drawText(font, "--- Textures ---", 10, 615, 12, 0xFFAAAAAA, 1);
        bRender.drawTexture(texture, 10, 635, 80, 40, 0xFFFFFFFF, false, 1);
        bRender.drawTextureCropped(texture, 100, 635, 40, 40, 0, 0, 8, 8, 0xFFFFFFFF, false, 1);
        bRender.drawTextureTiled(texture, 150, 635, 80, 40, 0xFFFFFFFF, false, 1);
        bRender.drawTexture(texture, 240, 635, 80, 40, 0xFFFF8800, false, 1);

        // ==================== 9-SLICE ====================
        bRender.drawText(font, "--- 9-Slice ---", 10, 690, 12, 0xFFAAAAAA, 1);
        if (nineSlice != null) {
            bRender.drawTexture9Slice(nineSlice, 10, 710, 100, 50, 0xFFFFFFFF, false, 1);
            bRender.drawTexture9Slice(nineSlice, 120, 710, 250, 80, 0xFFFFFFFF, false, 1);
            bRender.drawTexture9Slice(nineSlice, 380, 710, 120, 80, 0xFF88CCFF, false, 1);
        }

        // Flush all scrolled content using BRender.WithOffset(x, y)
        bRender.flush(graphics, BRender.WithOffset(offsetX, offsetY));

        // ==================== UN-SCROLLED OVERLAYS (Fixed UI) ====================
        int fw = 60, fh = 60;
        bRender.roundRect(
                (int)(followerX - fw / 2f), (int)(followerY - fh / 2f), fw, fh,
                new Gradient(0xFFFF0066, 0xFF6600FF, 0xFF00CCFF),
                GradientDirection.TOP_LEFT_BOTTOM_RIGHT, 12, 10
        );
        bRender.strokeRounded(
                (int)(followerX - fw / 2f) - 2, (int)(followerY - fh / 2f) - 2,
                fw + 4, fh + 4,
                new Gradient(0xAAFF0066, 0xAA00CCFF),
                GradientDirection.TOP_LEFT_BOTTOM_RIGHT, 14, 2, 10
        );

        // Flush static screen overlay without scroll offset
        bRender.flush(graphics, BRender.WithOffset(0, 0));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}