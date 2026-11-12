package net.neoforged.neoforge.registries;

import net.minecraft.core.registries.BuiltInRegistries;

/** Test launcher substitute: marks only real registries required by ItemStack codec as synchronized. */
public final class HeadlessRegistrySetup {
    private HeadlessRegistrySetup() { }

    public static void initializeVanillaRegistrySync() {
        // Production NeoForgeRegistriesSetup marks these during ModifyRegistriesEvent.
        // A codec test does not initialize unrelated attribute callbacks or the complete FML registration pipeline.
        ((BaseMappedRegistry<?>) BuiltInRegistries.ITEM).setSync(true);
        ((BaseMappedRegistry<?>) BuiltInRegistries.DATA_COMPONENT_TYPE).setSync(true);
    }
}
