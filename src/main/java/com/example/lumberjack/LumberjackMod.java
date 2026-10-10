package com.example.lumberjack;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Полноценный мод (работает и на сервере, и в одиночной игре).
 * Золотой топор «Топор дровосека» в основной руке:
 *  - один - ПКМ делает рывок вперёд;
 *  - вместе с огнивом в другой руке - ПКМ поджигает блоки вокруг игрока,
 *    а огниво при этом изнашивается и может сломаться, как обычно.
 */
public class LumberjackMod implements ModInitializer {

    /** Имя, которое нужно дать золотому топору в наковальне */
    public static final String AXE_NAME = "Топор дровосека";
    /** Сила рывка */
    private static final double DASH_POWER = 1.8;
    /** Радиус поджога вокруг игрока (в блоках) */
    private static final int IGNITE_RADIUS = 4;
    /** Перезарядка обеих способностей, в тиках (20 = 1 секунда) */
    private static final int COOLDOWN_TICKS = 20;

    public static boolean isDashAxe(ItemStack stack) {
        if (!stack.isOf(Items.GOLDEN_AXE)) return false;
        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        return name != null && name.getString().trim().equalsIgnoreCase(AXE_NAME);
    }

    @Override
    public void onInitialize() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return ActionResult.PASS; // считаем только на сервере (и на сервере одиночной игры)
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

            ItemStack mainHand = player.getStackInHand(Hand.MAIN_HAND);
            if (!isDashAxe(mainHand)) return ActionResult.PASS;
            if (player.getItemCooldownManager().isCoolingDown(mainHand)) return ActionResult.PASS;

            ItemStack offHand = player.getStackInHand(Hand.OFF_HAND);
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;

            if (offHand.isOf(Items.FLINT_AND_STEEL)) {
                igniteAround(serverPlayer, (ServerWorld) world);
                damageFlintAndSteel(serverPlayer, (ServerWorld) world, offHand);
            } else {
                dash(serverPlayer, world);
            }

            player.getItemCooldownManager().set(mainHand, COOLDOWN_TICKS);
            return ActionResult.SUCCESS;
        });
    }

    private void dash(ServerPlayerEntity player, World world) {
        Vec3d dir = player.getRotationVec(1.0f).normalize().multiply(DASH_POWER);
        player.setVelocity(dir);
        player.fallDistance = 0;
        player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));

        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP.value(),
                SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    private void igniteAround(ServerPlayerEntity player, ServerWorld world) {
        BlockPos center = player.getBlockPos();
        int r = IGNITE_RADIUS;

        // Частицы по кругу для эффекта "крутится и поджигает"
        for (int i = 0; i < 24; i++) {
            double angle = i * (2 * Math.PI / 24);
            double px = center.getX() + 0.5 + Math.cos(angle) * r;
            double pz = center.getZ() + 0.5 + Math.sin(angle) * r;
            world.spawnParticles(ParticleTypes.FLAME, px, center.getY() + 0.2, pz, 1, 0, 0, 0, 0.01);
        }

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x * x + y * y + z * z > r * r) continue; // круглая область, а не куб
                    BlockPos pos = center.add(x, y, z);
                    if (!world.getBlockState(pos).isAir()) continue;
                    if (!hasFlammableNeighbor(world, pos)) continue;
                    world.setBlockState(pos, net.minecraft.block.Blocks.FIRE.getDefaultState());
                }
            }
        }

        world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_FLINTANDSTEEL_USE.value(),
                SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    private boolean hasFlammableNeighbor(World world, BlockPos air) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos n = air.add(dx, dy, dz);
                    if (world.getBlockState(n).isBurnable()) return true;
                }
            }
        }
        return false;
    }

    /** Снимает прочность с огнива, как при обычном использовании, и ломает его, если прочность кончилась */
    private void damageFlintAndSteel(ServerPlayerEntity player, ServerWorld world, ItemStack offHand) {
        int damage = offHand.getOrDefault(DataComponentTypes.DAMAGE, 0) + 1;
        int maxDamage = offHand.getMaxDamage();

        if (damage >= maxDamage) {
            player.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ITEM_BREAK.value(),
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
        } else {
            offHand.set(DataComponentTypes.DAMAGE, damage);
        }
    }
}
