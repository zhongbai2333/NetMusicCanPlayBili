package com.zhongbai233.net_music_can_play_bili.init;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE,
            NetMusicCanPlayBili.MODID);

    public static final DeferredHolder<Attribute, Attribute> HEADPHONES = ATTRIBUTES.register("headphones",
            () -> new RangedAttribute("attribute.name.net_music_can_play_bili.headphones", 0.0D, 0.0D, 1024.0D)
                    .setSentiment(Attribute.Sentiment.POSITIVE)
                    .setSyncable(true));

    private ModAttributes() {
    }
}
