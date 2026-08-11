package dev.bsprout.brapi.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

public class BTexture implements ResourceManagerReloadListener {
    private static final List<BTexture> ALL = new ArrayList<>();

    public GpuTexture texture;
    public GpuTextureView view;
    public int width;
    public int height;

    public static final Logger LOGGER = LoggerFactory.getLogger("brapi");

    private final Identifier location;

    public BTexture(Identifier location) {
        this.location = location;
        load();
        ALL.add(this);
    }

    private BTexture(String debugName, ByteBuffer rgba8Pixels, int width, int height) {
        this.location = null;
        this.width = width;
        this.height = height;

        texture = RenderSystem.getDevice().createTexture(
                debugName,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8,
                width, height, 1, 1
        );
        RenderSystem.getDevice().createCommandEncoder()
                .writeToTexture(texture, rgba8Pixels, NativeImage.Format.RGBA, 0, 0, 0, 0, width, height);
        view = RenderSystem.getDevice().createTextureView(texture);
        ALL.add(this);
    }

    private void load() {
        // Close old GPU resources if reloading
        if (view != null) { view.close(); view = null; }
        if (texture != null) { texture.close(); texture = null; }

        ByteBuffer pixels = null;
        boolean fromStb = true;
        try {
            InputStream stream = Minecraft.getInstance()
                    .getResourceManager()
                    .open(location);
            byte[] bytes = stream.readAllBytes();
            stream.close();

            ByteBuffer rawBuf = MemoryUtil.memAlloc(bytes.length);
            rawBuf.put(bytes).flip();

            IntBuffer w = MemoryUtil.memAllocInt(1);
            IntBuffer h = MemoryUtil.memAllocInt(1);
            IntBuffer channels = MemoryUtil.memAllocInt(1);

            pixels = STBImage.stbi_load_from_memory(rawBuf, w, h, channels, 4);
            MemoryUtil.memFree(rawBuf);
            MemoryUtil.memFree(channels);

            if (pixels == null) throw new RuntimeException("STBImage: " + STBImage.stbi_failure_reason());

            width = w.get(0);
            height = h.get(0);
            MemoryUtil.memFree(w);
            MemoryUtil.memFree(h);

        } catch (Exception e) {
            LOGGER.error("[BRender] Failed to load texture '" + location + "': " + e.getMessage() + " - using missing texture");
            pixels = makeMissingTexture();
            fromStb = false;
            width = 16; height = 16;
        }

        texture = RenderSystem.getDevice().createTexture(
                location.toString(),
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8,
                width, height, 1, 1
        );
        RenderSystem.getDevice().createCommandEncoder()
                .writeToTexture(texture, pixels, NativeImage.Format.RGBA,
                        0, 0, 0, 0, width, height);
        if (fromStb) STBImage.stbi_image_free(pixels);
        else MemoryUtil.memFree(pixels);
        view = RenderSystem.getDevice().createTextureView(texture);
    }

    private static ByteBuffer makeMissingTexture() {
        int size = 16 * 16 * 4;
        ByteBuffer buf = MemoryUtil.memAlloc(size);
        for (int py = 0; py < 16; py++) {
            for (int px = 0; px < 16; px++) {
                boolean magenta = (px < 8) != (py < 8);
                buf.put((byte)(magenta ? 0xFF : 0x00));
                buf.put((byte)(0x00));
                buf.put((byte)(magenta ? 0xFF : 0x00));
                buf.put((byte)(0xFF));
            }
        }
        buf.flip();
        return buf;
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        if (location != null) load();
    }

    public void close() {
        ALL.remove(this);
        if (view != null) view.close();
        if (texture != null) texture.close();
    }

    public static Logger getLogger(){
        return LOGGER;
    }
}