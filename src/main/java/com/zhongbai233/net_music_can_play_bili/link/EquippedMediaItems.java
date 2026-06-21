package com.zhongbai233.net_music_can_play_bili.link;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 查询佩戴在原版头盔槽或 Curios 兼容头部槽位中的媒体装备的工具方法。 */
public final class EquippedMediaItems {
    private static final CuriosBridge CURIOS = CuriosBridge.create();

    private EquippedMediaItems() {
    }

    public static ItemStack firstHeadphones(Player player) {
        return firstEquipped(player, HeadphoneAbility::has);
    }

    public static ItemStack firstHolographicGlasses(Player player) {
        return firstEquipped(player, HolographicGlassesAbility::has);
    }

    public static ItemStack firstMediaGear(Player player) {
        return firstEquipped(player, stack -> HeadphoneAbility.has(stack) || HolographicGlassesAbility.has(stack));
    }

    public static ItemStack firstCuriosEquipped(Player player, Predicate<ItemStack> filter) {
        if (player == null || filter == null) {
            return ItemStack.EMPTY;
        }
        return CURIOS.first(player, filter);
    }

    public static ItemStack firstEquipped(Player player, Predicate<ItemStack> filter) {
        if (player == null || filter == null) {
            return ItemStack.EMPTY;
        }
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (filter.test(head)) {
            return head;
        }
        return CURIOS.first(player, filter);
    }

    public static void forEachEquipped(Player player, Consumer<ItemStack> consumer) {
        if (player == null || consumer == null) {
            return;
        }
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        acceptOnce(seen, consumer, player.getItemBySlot(EquipmentSlot.HEAD));
        CURIOS.all(player, stack -> true).forEach(stack -> acceptOnce(seen, consumer, stack));
    }

    private static void acceptOnce(Set<ItemStack> seen, Consumer<ItemStack> consumer, ItemStack stack) {
        if (stack.isEmpty() || !seen.add(stack)) {
            return;
        }
        consumer.accept(stack);
    }

    private static final class CuriosBridge {
        private final Method getCuriosInventory;
        private final Method findFirstCurio;
        private final Method findCurios;
        private final Method slotResultStack;

        private CuriosBridge(Method getCuriosInventory, Method findFirstCurio, Method findCurios,
                Method slotResultStack) {
            this.getCuriosInventory = getCuriosInventory;
            this.findFirstCurio = findFirstCurio;
            this.findCurios = findCurios;
            this.slotResultStack = slotResultStack;
        }

        static CuriosBridge create() {
            try {
                Class<?> curiosApi = Class.forName("top.theillusivec4.curios.api.CuriosApi");
                Class<?> itemHandler = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
                Class<?> slotResult = Class.forName("top.theillusivec4.curios.api.SlotResult");
                return new CuriosBridge(
                        curiosApi.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class),
                        itemHandler.getMethod("findFirstCurio", Predicate.class),
                        itemHandler.getMethod("findCurios", Predicate.class),
                        slotResult.getMethod("stack"));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return new CuriosBridge(null, null, null, null);
            }
        }

        ItemStack first(Player player, Predicate<ItemStack> filter) {
            Object handler = handler(player);
            if (handler == null || findFirstCurio == null) {
                return ItemStack.EMPTY;
            }
            try {
                Object result = findFirstCurio.invoke(handler, filter);
                if (result instanceof Optional<?> optional && optional.isPresent()) {
                    return stack(optional.get());
                }
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
                return ItemStack.EMPTY;
            }
            return ItemStack.EMPTY;
        }

        List<ItemStack> all(Player player, Predicate<ItemStack> filter) {
            Object handler = handler(player);
            if (handler == null || findCurios == null) {
                return List.of();
            }
            try {
                Object result = findCurios.invoke(handler, filter);
                if (!(result instanceof List<?> list) || list.isEmpty()) {
                    return List.of();
                }
                List<ItemStack> stacks = new ArrayList<>();
                for (Object slotResult : list) {
                    ItemStack stack = stack(slotResult);
                    if (!stack.isEmpty()) {
                        stacks.add(stack);
                    }
                }
                return List.copyOf(stacks);
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
                return List.of();
            }
        }

        private Object handler(Player player) {
            if (player == null || getCuriosInventory == null) {
                return null;
            }
            try {
                Object result = getCuriosInventory.invoke(null, player);
                if (result instanceof Optional<?> optional && optional.isPresent()) {
                    return optional.get();
                }
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
                return null;
            }
            return null;
        }

        private ItemStack stack(Object slotResult) {
            if (slotResult == null || slotResultStack == null) {
                return ItemStack.EMPTY;
            }
            try {
                Object stack = slotResultStack.invoke(slotResult);
                return stack instanceof ItemStack itemStack ? itemStack : ItemStack.EMPTY;
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
                return ItemStack.EMPTY;
            }
        }
    }
}
