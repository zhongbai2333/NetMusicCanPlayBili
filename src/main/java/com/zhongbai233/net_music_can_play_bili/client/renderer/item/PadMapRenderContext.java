package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapSnapshot;
import com.zhongbai233.net_music_can_play_bili.client.pad.PadMapProjection;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Owns Pad map render resources used by the offscreen GUI compositor. */
final class PadMapRenderContext implements AutoCloseable {
    private final PadMapLayerTexture layerTexture;
    private final PadMapGuiLayer guiLayer;

    PadMapRenderContext(String textureKey) {
        this.layerTexture = new PadMapLayerTexture(textureKey);
        this.guiLayer = new PadMapGuiLayer(layerTexture);
    }

    void tick(PadMapSnapshot snapshot) {
        layerTexture.tick(snapshot);
    }

    void draw(GuiGraphicsExtractor g, PadGuiViewState view, PadMapProjection.Rect mapRect) {
        guiLayer.draw(g, view, mapRect);
    }

    @Override
    public void close() {
        layerTexture.close();
    }
}
