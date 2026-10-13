package dev.ahmedhamedi.minecraft_machines.neoforge;

import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperShockwaveClock;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperStuckDetector;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.joml.Vector3d;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Supplier;

final class MinecraftMachinesLeaperShockwave {
    static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, "minecraft_machines");
    static final Supplier<SoundEvent> CHARGE = sound("leaper_charge");
    static final Supplier<SoundEvent> BLAST = sound("leaper_blast");
    private static Supplier<SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath("minecraft_machines", name)));
    }
    final LeaperShockwaveClock clock = new LeaperShockwaveClock();
    private final LeaperStuckDetector stuck = new LeaperStuckDetector();
    private boolean freeingPulse;
    private UUID effectId;
    private long startTick;
    private Vector3d center = new Vector3d();
    private double floor;
    private double effectScale = 1;
    boolean enabled = true;

    boolean step(ServerLevel level, MinecraftMachinesLeaper rig, MinecraftMachinesLeaperPredatorNavigator nav) {
        return step(level, rig, nav, true, null, true);
    }

    boolean step(ServerLevel level, MinecraftMachinesLeaper rig, MinecraftMachinesLeaperPredatorNavigator nav,
            boolean allowCombat, UUID target, boolean allowClearing) {
        // Eligibility gates the start only. Once charging, loss of sight, a mode
        // change, movement commands or disabling future attacks cannot cancel it.
        if (nav != null && nav.waitingForClearingPulse) {
            if (!clock.active() && rig.jointStrain() <= .11
                    && (freeingPulse || !enabled || !hasTrappingBlocks(level, rig, nav))) {
                nav.replanAfterPulse();
                if (freeingPulse) {
                    nav.leap.requestRecoveryLeap();
                    nav.planning = nav.followSearch = null;
                }
                freeingPulse = false;
            }
        }
        var p = rig.corePivot();
        boolean grounded = rig.upright() > .75 && Math.abs(rig.velocity().y) < 1.5
                && (nav == null || nav.leap.state == MinecraftMachinesLeaperLeap.State.WALK);
        boolean nearby = enabled && allowCombat && level.players().stream().anyMatch(player -> eligible(player)
                && (target == null || target.equals(player.getUUID())) && within(player, p, LeaperShockwaveClock.TRIGGER_RADIUS * rig.scale));
        boolean tryingToMove = enabled && allowClearing && !clock.active() && nav != null && nav.tick > 80
                && nav.goal != null && !nav.arrived && !nav.holdPosition
                && !(nav.escape != null && nav.tick < nav.backUntil)
                && (Math.hypot(nav.goal.x - p.x, nav.goal.z - p.z) > 2.5
                        || Math.abs(nav.goal.y - (p.y - nav.control.standingHeight())) > 1.5);
        boolean recoveryGrounded = nav != null && !nav.leap.inAir()
                && rig.upright() > .55 && Math.abs(rig.velocity().y) < 1.5;
        boolean stalled = stuck.update(tryingToMove, recoveryGrounded, p.x, p.y, p.z);
        boolean trapped = stalled && clock.cooldown() == 0 && hasTrappingBlocks(level, rig, nav);
        var event = clock.tick(nearby || trapped, grounded || trapped);
        if (nav != null) nav.pulseActive = clock.active();
        if (event == LeaperShockwaveClock.Event.START) {
            effectScale = rig.scale;
            freeingPulse = trapped || nav != null && nav.waitingForClearingPulse;
            stuck.reset();
            if (nav != null) nav.leap.cancelPreparation();
            effectId = UUID.randomUUID(); startTick = level.getGameTime();
            level.playSound(null, p.x, p.y, p.z, CHARGE.get(), SoundSource.HOSTILE, 3.0f, 1);
        }
        if (clock.active()) {
            if (clock.age() <= LeaperShockwaveClock.CHARGE_TICKS) {
                center.set(p).add(0, .35 * rig.scale, 0);
                floor = 0;
                for (int i = 0; i < 4; i++) floor += rig.toe(i).y * .25;
            }
            if (clock.age() % 2 == 0 || event == LeaperShockwaveClock.Event.BLAST) broadcast(level, false);
            if (event == LeaperShockwaveClock.Event.BLAST) {
                level.playSound(null, center.x, center.y, center.z, BLAST.get(), SoundSource.HOSTILE, 4.0f, 1);
                blast(level, rig);
                if (nav != null && (freeingPulse || nav.waitingForClearingPulse)) {
                    freeingPulse = true;
                    // Let the recoil pose finish before asking the newly cleared
                    // terrain for traction. Starting Apex drive during decompression
                    // can pull the joints apart for a frame after the blast.
                    nav.waitingForClearingPulse = true;
                    nav.planning = nav.followSearch = null;
                    nav.escape = null;
                    nav.reason = "Recovering after clearing burst";
                }
            }
            // Layer the charge pose onto the running gait; the manager still ticks pursuit.
            if (nav != null) {
                double charge = Math.clamp(clock.age() / 60.0, 0, 1);
                double release = Math.clamp((clock.age() - 60) / 20.0, 0, 1);
                nav.control.shockwaveCompression = clock.age() < 60
                        ? .55 * charge * charge * (3 - 2 * charge)
                                + .6 * Math.pow(Math.sin(Math.clamp((charge - .78) / .22, 0, 1) * Math.PI / 2), 2)
                        : 1.15 * (1 - release * release * (3 - 2 * release));
            }
            return true;
        }
        if (nav != null) nav.control.shockwaveCompression = 0;
        return false;
    }

    private static boolean eligible(ServerPlayer p) { return p.isAlive() && !p.isSpectator(); }
    boolean canClearTrap(ServerLevel level, MinecraftMachinesLeaper rig,
            MinecraftMachinesLeaperPredatorNavigator nav) {
        return enabled && nav.goal != null && !nav.leap.inAir() && rig.upright() > .55
                && (freeingPulse && clock.age() >= LeaperShockwaveClock.CHARGE_TICKS
                        || (nav.waitingForClearingPulse || Math.abs(rig.velocity().y) < 1.5)
                                && hasTrappingBlocks(level, rig, nav));
    }
    private static boolean hasTrappingBlocks(ServerLevel level, MinecraftMachinesLeaper rig,
            MinecraftMachinesLeaperPredatorNavigator nav) {
        var p = rig.corePivot();
        double floor = nav.leap.supportFloor();
        if (!Double.isFinite(floor)) floor = p.y - nav.control.standingHeight();
        var map = MinecraftMachinesLeaperEscape.terrain(nav);
        var clearance = MinecraftMachinesLeaperClearance.measure(map, p.x, floor, p.z,
                nav.control.gradientX, nav.control.gradientZ);
        for (var pos : clearance.collisions()) {
            // A failed route or an ordinary bank alone is not evidence for a blast.
            // Require an actual collision allowance obstruction inside the blast volume.
            if (pos.getCenter().distanceToSqr(p.x, p.y + .35, p.z)
                    <= LeaperShockwaveClock.BLOCK_RADIUS * LeaperShockwaveClock.BLOCK_RADIUS * rig.scale * rig.scale
                    && level.getBlockState(pos).getDestroySpeed(level, pos) >= 0) return true;
        }
        return false;
    }
    private static boolean within(ServerPlayer player, Vector3d p) {
        return within(player, p, LeaperShockwaveClock.TRIGGER_RADIUS);
    }
    private static boolean within(ServerPlayer player, Vector3d p, double radius) {
        double nearestY = Math.clamp(p.y, player.getY(), player.getY() + player.getBbHeight());
        return LeaperShockwaveClock.inRange(player.getX() - p.x, nearestY - p.y, player.getZ() - p.z, radius);
    }
    private void blast(ServerLevel level, MinecraftMachinesLeaper rig) {
        // Sample planted terrain before the explosion changes it.
        for (int i = 0; i < 4; i++) {
            var toe = rig.toe(i);
            var state = level.getBlockState(net.minecraft.core.BlockPos.containing(toe.x, toe.y - .2, toe.z));
            if (!state.isAir()) level.sendParticles(
                    new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, state),
                    toe.x, toe.y + .16, toe.z, 14, .55, .12, .55, .48);
        }
        for (var player : level.players()) {
            if (!eligible(player) || player.isCreative()
                    || !within(player, center, LeaperShockwaveClock.LAUNCH_RADIUS * effectScale)) continue;
            var from = new Vec3(center.x, center.y, center.z);
            var clip = new ClipContext(from, player.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
            ((dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension) clip)
                    .sable$setSubLevelIgnoring(rig::ownsBody);
            var obstruction = level.clip(clip);
            if (obstruction.getType() != HitResult.Type.MISS) continue;
            double dx = player.getX() - center.x, dz = player.getZ() - center.z;
            double distance = Math.hypot(dx, dz);
            double force = 1 - .5 * Math.min(1, distance / (LeaperShockwaveClock.LAUNCH_RADIUS * effectScale));
            if (within(player, center, LeaperShockwaveClock.DAMAGE_RADIUS * effectScale)) {
                double damageFalloff = 1 - .5 * Math.min(1, distance / (LeaperShockwaveClock.DAMAGE_RADIUS * effectScale));
                player.hurt(level.damageSources().sonicBoom(null), (float) (8 * damageFalloff));
            }
            if (distance < .001) { dx = 0; dz = 1; distance = 1; }
            player.push(dx / distance * 4.5 * force, 1.65 * force, dz / distance * 4.5 * force);
            MinecraftMachinesLeaperLaunchPayload.send(player);
        }
        breakTerrain(level, rig);
    }

    private void breakTerrain(ServerLevel level, MinecraftMachinesLeaper rig) {
        ExplosionDamageCalculator calculator = new ExplosionDamageCalculator() {
            @Override
            public Optional<Float> getBlockExplosionResistance(Explosion explosion, BlockGetter reader,
                    BlockPos pos, BlockState state, FluidState fluid) {
                // Sable also traces explosions through moving bodies in their storage coordinates.
                // Our own armor must neither break nor absorb rays or receive explosion impulses.
                if (rig.ownsBody(Sable.HELPER.getContaining(level, pos))) return Optional.empty();
                return super.getBlockExplosionResistance(explosion, reader, pos, state, fluid);
            }

            @Override
            public boolean shouldBlockExplode(Explosion explosion, BlockGetter reader,
                    BlockPos pos, BlockState state, float power) {
                if (rig.ownsBody(Sable.HELPER.getContaining(level, pos))) return false;
                var world = Sable.HELPER.projectOutOfSubLevel(level, pos.getCenter());
                return world.distanceToSqr(explosion.center())
                        <= LeaperShockwaveClock.BLOCK_RADIUS * LeaperShockwaveClock.BLOCK_RADIUS * effectScale * effectScale;
            }

            @Override
            public boolean shouldDamageEntity(Explosion explosion, Entity entity) { return false; }

            @Override
            public float getKnockbackMultiplier(Entity entity) { return 0; }
        };
        // Clearing bursts excavate below the feet too; only armor, resistance and
        // the spherical blast radius limit terrain destruction.
        // Vanilla TNT resistance, fluids, loot, chain reactions and NeoForge explosion hooks.
        // This overload skips the stock audiovisual packet; the existing effect owns that.
        // Player damage and knockback were already applied once, against pre-blast cover.
        level.explode(null, null, calculator, center.x, center.y, center.z, (float) (8 * effectScale), false,
                Level.ExplosionInteraction.TNT, false, ParticleTypes.EXPLOSION,
                ParticleTypes.EXPLOSION_EMITTER, SoundEvents.GENERIC_EXPLODE);
    }
    private void broadcast(ServerLevel level, boolean cancel) {
        if (effectId == null) return;
        var payload = new MinecraftMachinesLeaperShockwavePayload(effectId, startTick,
                center.x, center.y, center.z, floor, effectScale, cancel);
        for (var p : level.players())
            if (p.distanceToSqr(center.x, center.y, center.z) < 96 * 96)
                PacketDistributor.sendToPlayer(p, payload);
    }
    void cancel(ServerLevel level) {
        // Lifecycle disposal only: the physical Leaper is being removed.
        if (effectId != null) broadcast(level, true);
        effectId = null;
        clock.cancel();
    }
}
