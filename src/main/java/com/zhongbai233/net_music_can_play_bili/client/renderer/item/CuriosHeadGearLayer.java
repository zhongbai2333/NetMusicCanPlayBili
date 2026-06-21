package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.link.EquippedMediaItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/** 使用原版头部物品同款头部变换，渲染本模组佩戴在 Curios 头部槽位的媒体装备。 */
public final class CuriosHeadGearLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CustomHeadLayer.Transforms HEAD_TRANSFORMS = CustomHeadLayer.Transforms.DEFAULT;
    private static Method addLayerMethod;

    private final ItemStackRenderState itemRenderState = new ItemStackRenderState();

    private CuriosHeadGearLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    public static void register(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            AvatarRenderer<AbstractClientPlayer> renderer = event.getPlayerRenderer(skin);
            if (renderer != null) {
                addLayer(renderer);
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static <T extends Avatar & ClientAvatarEntity> void addLayer(AvatarRenderer<T> renderer) {
        try {
            Method method = addLayerMethod();
            method.invoke(renderer, new CuriosHeadGearLayer((RenderLayerParent) renderer));
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("附加 Curios 头部媒体装备渲染层失败", e);
        }
    }

    private static Method addLayerMethod() throws NoSuchMethodException {
        if (addLayerMethod == null) {
            addLayerMethod = LivingEntityRenderer.class.getDeclaredMethod("addLayer", RenderLayer.class);
            addLayerMethod.setAccessible(true);
        }
        return addLayerMethod;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state,
            float yRot, float xRot) {
        ItemStack stack = curiosHeadGear(state);
        if (stack.isEmpty()) {
            return;
        }
        Entity entity = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getEntity(state.id)
                : null;
        if (!(entity instanceof AbstractClientPlayer player)) {
            return;
        }

        itemRenderState.clear();
        Minecraft.getInstance().getItemModelResolver().updateForLiving(itemRenderState, stack, ItemDisplayContext.HEAD,
                player);
        if (itemRenderState.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        poseStack.scale(HEAD_TRANSFORMS.horizontalScale(), 1.0F, HEAD_TRANSFORMS.horizontalScale());
        getParentModel().root().translateAndRotate(poseStack);
        ((HeadedModel) getParentModel()).translateToHead(poseStack);
        CustomHeadLayer.translateToHead(poseStack, HEAD_TRANSFORMS);
        itemRenderState.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
    }

    private static ItemStack curiosHeadGear(AvatarRenderState state) {
        Entity entity = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getEntity(state.id)
                : null;
        if (!(entity instanceof AbstractClientPlayer player)) {
            return ItemStack.EMPTY;
        }
        return EquippedMediaItems.firstCuriosEquipped(player, stack -> stack.getItem() == ModItems.CAT_HEADPHONES.get()
                || stack.getItem() == ModItems.HOLOGRAPHIC_GLASSES.get());
    }
}