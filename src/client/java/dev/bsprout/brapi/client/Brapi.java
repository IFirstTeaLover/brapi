package dev.bsprout.brapi.client;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public class Brapi implements ClientModInitializer {

    public static RenderPipeline ROUNDED_RECT_PIPELINE = null;
    public static RenderPipeline TEXT_PIPELINE = null;
    public static RenderPipeline BLUR_PIPELINE = null;
    public static RenderPipeline TEXTURE_PIPELINE = null;

    public static RenderPipeline BLUR_H_PIPELINE = null;
    public static RenderPipeline BLUR_V_PIPELINE = null;
    public static RenderPipeline GLASS_PIPELINE = null;

    public static boolean pipelinesReady = false;

	@Override
	public void onInitializeClient() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return Identifier.fromNamespaceAndPath("brapi", "pipelines");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                initPipelines();

                // preload test screen assets for no freezes
                if (Test.font != null) Test.font.close();
                if (Test.texture != null) Test.texture.close();

                Test.font = new BFont(
                        Identifier.fromNamespaceAndPath("brapi", "fonts/noto_sans_regular.ttf"),
                        new BFontEmojiResolver()
                );
                Test.texture = new BTexture(Identifier.fromNamespaceAndPath("brapi", "textures/mc_button.png"));
                Test.nineSlice = new NineSlice(Test.texture, 4, 4, 4, 4);
            }
        });
        // Adding a simple test command to open our Test screen (dev/bsprout/brapi/client/Test.java)
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(ClientCommandManager.literal("brapitest")
                .executes(context -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.schedule(() -> {
                        if (mc.player != null) {
                            mc.player.closeContainer();
                        }
                        mc.setScreen(new Test());
                    });
                    return 1;
                })));
	}

    public static void initPipelines() {
        pipelinesReady = false;
        ROUNDED_RECT_PIPELINE = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("brapi", "pipeline/rounded_rect"))
                        .withVertexShader("core/brapi_rounded_rect")
                        .withFragmentShader("core/brapi_rounded_rect")
                        .withUniform("RectData", UniformType.UNIFORM_BUFFER)
                        .withBlend(BlendFunction.TRANSLUCENT)
                        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                        .build()
        );

        TEXT_PIPELINE = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("brapi", "pipeline/text"))
                        .withVertexShader("core/brapi_text")
                        .withFragmentShader("core/brapi_text")
                        .withBlend(BlendFunction.TRANSLUCENT)
                        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                        .build()
        );

        TEXTURE_PIPELINE = RenderPipelines.register(
                RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("brapi", "pipeline/texture"))
                        .withVertexShader("core/brapi_text") // reuse text vsh, same format
                        .withFragmentShader("core/brapi_texture")
                        .withBlend(BlendFunction.TRANSLUCENT)
                        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                        .build()
        );
        pipelinesReady = true;
    }

    public static boolean isReady() {
        return pipelinesReady;
    }
}