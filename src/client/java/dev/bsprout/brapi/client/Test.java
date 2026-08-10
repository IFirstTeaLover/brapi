package dev.bsprout.brapi.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

public class Test extends Screen {
    BRender renderer = new BRender();
    public Test() {
        super(Component.literal("BRender Test Screen"));
    }

    @Override
    protected void init() {

    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        renderer.flush(graphics);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}