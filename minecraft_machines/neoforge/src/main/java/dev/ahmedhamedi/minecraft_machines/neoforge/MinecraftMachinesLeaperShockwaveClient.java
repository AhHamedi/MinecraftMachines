package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.ahmedhamedi.minecraft_machines.content.leaper.LeaperShockwaveClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import java.util.*;

/** Analytic ribbons and soft volumetric impostors, rendered at fractional ticks without particle packets. */
final class MinecraftMachinesLeaperShockwaveClient {
    private static final Map<UUID, Effect> EFFECTS = new LinkedHashMap<>();
    private static ClientLevel world;
    private static final double TAU = Math.PI * 2;
    private static final int GOLD = 0xffd18a, PALE = 0xfff3d8, WHITE = 0xf5f7ff;
    private static class Effect {
        long start;
        Vec3 previous, center;
        double floor, updated, scale;
        Effect(MinecraftMachinesLeaperShockwavePayload p, double now) {
            center = previous = new Vec3(p.x(), p.y(), p.z());
            start = p.startTick(); floor = p.floor(); scale = Math.clamp(p.scale(), 1, 2); updated = now;
        }
    }
    private static Vec3 visualCenter(Effect e, Vec3 point) {
        return new Vec3(point.x, e.floor + (point.y - e.floor) / e.scale, point.z);
    }

    static void init() {
        MinecraftMachinesLeaperShockwavePayload.receiver = MinecraftMachinesLeaperShockwaveClient::receive;
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesLeaperShockwaveClient::render);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesLeaperShockwaveClient::shake);
        NeoForge.EVENT_BUS.addListener(MinecraftMachinesLeaperShockwaveClient::logout);
    }
    private static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        EFFECTS.clear(); world = null;
    }
    private static void receive(MinecraftMachinesLeaperShockwavePayload p) {
        var level = Minecraft.getInstance().level;
        if (world != level) { EFFECTS.clear(); world = level; }
        if (level == null) return;
        if (p.cancelled()) { EFFECTS.remove(p.id()); return; }
        var e = EFFECTS.get(p.id());
        if (e == null) {
            if (EFFECTS.size() >= 24) EFFECTS.remove(EFFECTS.keySet().iterator().next());
            EFFECTS.put(p.id(), new Effect(p, level.getGameTime()));
        } else {
            e.previous = e.center; e.center = new Vec3(p.x(), p.y(), p.z());
            e.floor = p.floor(); e.updated = level.getGameTime();
        }
    }
    private static double unit(double v) { return Math.clamp(v, 0, 1); }
    private static double smooth(double v) { double t = unit(v); return t * t * (3 - 2 * t); }
    private static double hash(int i) { double a = Math.sin(i * 127.1 + 311.7) * 43758.5453; return a - Math.floor(a); }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        var mc = Minecraft.getInstance();
        if (world != mc.level || mc.level == null) { EFFECTS.clear(); world = mc.level; return; }
        double now = world.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        EFFECTS.values().removeIf(e -> now - e.start > LeaperShockwaveClock.END_TICKS + 24 || now < e.start - 20);
        if (EFFECTS.isEmpty()) return;
        Vec3 camera = event.getCamera().getPosition();
        var right = new Vector3f(1, 0, 0).rotate(event.getCamera().rotation());
        var up = new Vector3f(0, 1, 0).rotate(event.getCamera().rotation());
        var draw = new Draw(event.getPoseStack().last().pose(), camera, new Vec3(right), new Vec3(up));
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.enableBlend(); RenderSystem.enableDepthTest(); RenderSystem.depthMask(false);
        RenderSystem.disableCull(); RenderSystem.setShader(() -> GameRenderer.getPositionTexColorShader());
        RenderSystem.setShaderTexture(0, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft_machines", "textures/particle/leaper_glow.png"));
        try {
            // Dust uses ordinary alpha; energy uses additive light. Both respect terrain depth.
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderTexture(0, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft_machines", "textures/particle/leaper_mist.png"));
            draw.begin();
            for (var e : EFFECTS.values()) {
                double age = (now - e.start) / 20;
                if (e.center.distanceToSqr(camera) < 96 * 96) { draw.effect(e); dust(draw, e, age); }
            }
            draw.end(false);
            RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            RenderSystem.setShaderTexture(0, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft_machines", "textures/particle/leaper_glow.png"));
            draw.begin();
            for (var e : EFFECTS.values()) {
                if (e.center.distanceToSqr(camera) > 96 * 96) continue;
                draw.effect(e);
                var center = e.previous.lerp(e.center, unit((now - e.updated) / 2));
                double age = (now - e.start) / 20;
                if (age < 3) charge(draw, visualCenter(e, center), age);
                else blast(draw, visualCenter(e, e.center), e.floor, age - 3);
            }
            draw.end(true);
            // The pressure front carries its own brief white radiance before becoming dust.
            RenderSystem.setShaderTexture(0, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft_machines", "textures/particle/leaper_mist.png"));
            draw.begin();
            for (var e : EFFECTS.values()) {
                double t = (now - e.start) / 20 - 3;
                if (t < 0 || t > .85 || e.center.distanceToSqr(camera) > 96 * 96) continue;
                draw.effect(e);
                for (int i = 0; i < 42; i++) {
                    double az = i * 2.39996 + hash(i + 78) * .7, dy = 1 - 2 * (i + .5) / 42;
                    double h = Math.sqrt(1 - dy * dy), r = 3.2 + (8 + 3 * hash(i + 12)) * (1 - Math.exp(-t * 7));
                    var p = visualCenter(e, e.center).add(Math.cos(az) * h * r, dy * .68 * r, Math.sin(az) * h * r);
                    draw.cloud(p, 1.9 + t * 3, WHITE, (.6 + .4 * smooth(t / .04)) * Math.exp(-t * 4.2) * .74, i + 350);
                }
            }
            draw.end(true);
        } finally {
            RenderSystem.depthMask(true); RenderSystem.enableCull(); RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend(); RenderSystem.setShaderColor(1, 1, 1, 1);
        }
    }

    private static Vec3 orbit(Vec3 center, double phase, int i, double charge) {
        double radius = (4.8 + 1.0 * hash(i + 5)) * (1 - .47 * smooth((charge - .82) / .18));
        double theta = phase + i * 2.39996;
        theta += .19 * Math.sin(theta * 2 + i);
        double tilt = (hash(i + 14) - .5) * 1.8;
        double knot = Math.floor(theta * 9), fraction = theta * 9 - knot;
        double fracture = hash(i * 103 + (int)knot) * (1 - fraction) + hash(i * 103 + (int)knot + 1) * fraction - .5;
        radius *= 1 + .035 * Math.sin(theta * 3 + i) + .07 * fracture;
        double x = radius * Math.cos(theta), z = radius * Math.sin(theta);
        double y = z * Math.sin(tilt) + .38 * Math.sin(theta * 2 + i) + .15 * fracture;
        z *= Math.cos(tilt);
        double yaw = i * 1.17;
        return center.add(x * Math.cos(yaw) - z * Math.sin(yaw), y, x * Math.sin(yaw) + z * Math.cos(yaw));
    }
    private static void charge(Draw d, Vec3 c, double t) {
        if (t < 0) return;
        double q = unit(t / 3), appear = smooth(t / .32);
        double collapse = smooth((q - .86) / .14);
        double phase = t * 2.0 + t * t * 1.6;
        // Each orbit is an open, tapered comet with its own plane and broken wake.
        for (int i = 0; i < 32; i++) {
            double length = .35 + 1.4 * hash(i + 25) + q * (.3 + .8 * hash(i + 6));
            double orbitalPhase = phase * (.72 + .55 * hash(i + 63));
            double strength = appear * (.6 + .85 * q + .3 * collapse) * (i < 10 ? 1 : smooth((q - .10 - (i - 10) * .016) / .2));
            Vec3 prev = orbit(c, orbitalPhase - length, i, q);
            for (int j = 1; j <= 56; j++) {
                double f = j / 56.0;
                Vec3 next = orbit(c, orbitalPhase - length * (1 - f), i, q);
                double a = strength * Math.pow(f, 2.5) * (.65 + .35 * Math.sin(t * (11 + hash(i) * 13) + i));
                d.ribbon(prev, next, .4 + .6 * q, GOLD, a * .26);
                d.ribbon(prev, next, (.09 + .16 * q) * (.5 + hash(i)), GOLD, a * .85);
                d.ribbon(prev, next, (.016 + .068 * Math.pow(f, 3)) * (.55 + hash(i + 62)) * (1 + .85 * collapse), PALE, a * (1 + .6 * collapse));
                prev = next;
            }
            d.glow(prev, .09 + q * .04, GOLD, .35 * strength);
            d.glow(prev, .05, WHITE, strength);
        }
        // Small wake sparks describe rotation without filling in a solid globe.
        for (int i = 0; i < 220; i++) {
            double life = (t * (.3 + hash(i) * .6) + hash(i + 99)) % 1;
            var p = orbit(c, phase * (.72 + .55 * hash(i % 32 + 63)) - life * 2.4, i % 32, q);
            p = p.add((hash(i + 23) - .5) * .35, (hash(i + 61) - .5) * .5, (hash(i + 73) - .5) * .35);
            double a = appear * Math.sin(life * Math.PI) * (.2 + .6 * q);
            d.glow(p, .035 + .065 * hash(i + 7), i % 3 == 0 ? WHITE : GOLD, a * 1.2);
            if (i % 3 != 0) {
                var direction = orbit(c, phase * (.72 + .55 * hash(i % 32 + 63)) - life * 2.4 + .03, i % 32, q).subtract(
                        orbit(c, phase * (.72 + .55 * hash(i % 32 + 63)) - life * 2.4, i % 32, q)).normalize();
                d.ribbon(p.subtract(direction.scale(.08 + .22 * hash(i + 14))), p, .035 + .025 * hash(i + 51), PALE, a);
            }
        }
        // Pulsing corona and late inward spokes give the anticipation a clear endpoint.
        d.glow(c, 2.4, GOLD, appear * (.06 + .15 * q * q));
        d.glow(c, 1.3, PALE, .14 * q * q);
        if (q > .68) {
            double gathering = smooth((q - .68) / .32);
            for (int i = 0; i < 26; i++) {
                double a = i * 2.39996, r = 1.5 + 2 * (1 - (t * 2 + hash(i)) % 1);
                var v = new Vec3(Math.cos(a), (hash(i + 88) - .5) * 1.6, Math.sin(a)).normalize();
                var p = c.add(v.scale(r));
                d.ribbon(p, p.add(v.scale(-.3 - .4 * gathering)), .014, PALE, gathering * .45);
            }
            d.glow(c.add(d.camera.subtract(c).normalize().scale(1.3)), 2 + collapse * 2, PALE, gathering * gathering * .28);
            for (int i = 0; i < 9; i++) {
                var start = orbit(c, phase - .3, i * 2, q);
                var end = orbit(c, phase - .65, (i * 2 + 7) % 20, q);
                var prev = start;
                double intensity = gathering * (.35 + .65 * Math.pow(Math.sin(t * 17 + i), 2));
                for (int j = 1; j <= 12; j++) {
                    double f = j / 12.0;
                    var next = start.lerp(end, f).add(Math.sin(j * 3.3 + i + t * 6) * .09, Math.sin(j * 2.7 + i) * .12, Math.cos(j * 4 + i + t * 7) * .09);
                    d.ribbon(prev, next, .09, GOLD, intensity * .16);
                    d.ribbon(prev, next, .017, PALE, intensity * .65);
                    prev = next;
                }
            }
        }
    }

    private static void blast(Draw d, Vec3 c, double floor, double t) {
        double flash = Math.exp(-t * 10);
        var flashCenter = c.add(d.camera.subtract(c).normalize().scale(2.4));
        d.glow(flashCenter, 6.0 + t * 12, WHITE, flash * .25);
        d.glow(flashCenter, 2.5 + t * 7, WHITE, flash * .7);
        d.glow(flashCenter, 2.9, 0xbbacff, Math.exp(-t * 13) * .6);
        for (int j = 0; j < 18; j++) {
            double f = j / 18.0, g = (j + 1) / 18.0;
            var from = c.add(Math.sin(f * 4) * .25, -.6 + f * 5.0, Math.sin(f * 7) * .18);
            var to = c.add(Math.sin(g * 4) * .25, -.6 + g * 5.0, Math.sin(g * 7) * .18);
            double taper = Math.pow(1 - f, .8);
            d.ribbon(from, to, .2 + 4.6 * f, 0xb499ff, Math.exp(-t * 7) * .7 * taper);
            d.ribbon(from, to, .1 + 1.3 * f, WHITE, Math.exp(-t * 9) * .65 * taper);
        }
        // Torn fingers of light burst through the pressure cloud, each with its own reach.
        for (int i = 0; i < 19; i++) {
            double az = i * 2.39996, y = (hash(i + 91) - .4) * 1.5;
            var direction = new Vec3(Math.cos(az), y, Math.sin(az)).normalize();
            double reach = 2 + (9 + 7 * hash(i + 42)) * (1 - Math.exp(-t * 7));
            var bend = new Vec3(-direction.z, .3 * Math.sin(i), direction.x);
            var prev = c.add(direction.scale(reach * .3));
            for (int j = 1; j <= 10; j++) {
                double f = j / 10.0, r = reach * (.3 + .7 * f);
                var next = c.add(direction.scale(r)).add(bend.scale((Math.sin(f * 5 + i) * .65 + (hash(j * 31 + i) - .5) * .65) * f));
                double strength = Math.exp(-t * (2.8 + hash(i) * 7)) * smooth(t / .025) * (1 - f * .65);
                d.ribbon(prev, next, (1.8 * (1 - f) + .14) * (.5 + hash(i)), WHITE, strength * .5);
                d.ribbon(prev, next, .14 * (1 - f) + .025, PALE, strength);
                prev = next;
            }
        }
        // The ground sweep carries the lower edge of the outward pressure front.
        for (int k = 0; k < 1; k++) {
            double age = t - k * .11;
            if (age < 0) continue;
            double r = .8 + 13 * (1 - Math.exp(-age * 3));
            double opacity = Math.exp(-age * 3.2);
            d.groundRing(new Vec3(c.x, floor + .15 + k * .13, c.z), r, 1.1 + age * .5, PALE, opacity * .43);
            d.groundRing(new Vec3(c.x, floor + .19, c.z), r, .65, GOLD, opacity * .035);
        }
        // Three-dimensional ballistic sparks decelerate in air and fall under gravity.
        for (int i = 0; i < 185; i++) {
            double lifetime = i < 48 ? 1.4 + hash(i + 21) * 1.4 : .45 + hash(i + 21) * 1.25;
            if (t > lifetime) continue;
            double az = i * 2.39996 + .9 * hash(i + 312), elevation = (hash(i + 72) - .25) * 1.5;
            Vec3 direction = new Vec3(Math.cos(az), elevation, Math.sin(az)).normalize();
            double speed = 10 + 21 * hash(i + 1), travel = speed * (1 - Math.exp(-t * 1.6)) / 1.6;
            Vec3 head = c.add(direction.scale(travel)).add(0, -1.9 * t * t, 0);
            double tailT = Math.max(0, t - .008 - .025 * Math.pow(hash(i + 41), 2));
            Vec3 tail = c.add(direction.scale(speed * (1 - Math.exp(-tailT * 1.6)) / 1.6)).add(0, -1.9 * tailT * tailT, 0);
            double fade = Math.pow(1 - t / lifetime, .7) * (.35 + .65 * hash(i + 112));
            d.ribbon(tail, head, .22, GOLD, fade * .25);
            d.ribbon(tail, head, .04 + .035 * hash(i + 2), i % 4 == 0 ? WHITE : PALE, fade * .85);
            d.glow(head, .055 + .06 * hash(i + 71), GOLD, fade * .65);
            if (i < 48) {
                // Heavier chips fall while the light front accelerates away.
                double debrisSpeed = 3 + hash(i + 178) * 8;
                var debris = c.add(direction.scale(debrisSpeed * t)).add(0, -4.3 * t * t, 0);
                if (debris.y > floor + .08) {
                    double size = .04 + .10 * hash(i + 185);
                    var axis = new Vec3(Math.sin(t * 18 + i), Math.cos(t * 23 + i), Math.sin(t * 15 - i)).scale(size);
                    d.ribbon(debris.subtract(axis), debris.add(axis), size, PALE, fade);
                }
            }
        }
    }

    private static void dust(Draw d, Effect e, double age) {
        if (age < 0) return;
        double t = age - 3;
        if (t < 0) {
            double q = unit(age / 3);
            for (int i = 0; i < 32; i++) {
                double a = i * 2.39996 + age * .6;
                var p = visualCenter(e, e.center).add(Math.cos(a) * (3.8 + hash(i)), -.6 + 2.5 * (hash(i) - .5), Math.sin(a) * (3.8 + hash(i)));
                d.cloud(p, 1.0 + q * 1.05, 0xd9d4c3, smooth(q) * .22, i);
            }
            return;
        }
        // A short, irregular pressure cloud tears outward in three dimensions.
        // It is intentionally the primary blast shape; the shock rings are only its edge.
        for (int i = 0; i < 84; i++) {
            double az = i * 2.39996 + .7 * hash(i + 78), dy = 1 - 2 * (i + .5) / 84;
            double horizontal = Math.sqrt(1 - dy * dy);
            var direction = new Vec3(Math.cos(az) * horizontal, dy * .68, Math.sin(az) * horizontal);
            double travel = 3.0 + (6.5 + hash(i + 12) * 5) * (1 - Math.exp(-t * 6));
            var p = visualCenter(e, e.center).add(direction.scale(travel));
            double opacity = (.5 + .5 * smooth(t / .04)) * Math.exp(-t * 2.0) * (.65 + hash(i + 38) * .3) * Math.exp(-t * Math.max(0, dy + .1) * 7);
            d.cloud(p, 1.25 + hash(i + 8) * .7 + t * (2.6 + hash(i)), 0xf9f8f2, opacity, i + 200);
        }
        // Slower turbulent remnants stay close to the chassis after the fast front passes.
        for (int i = 0; i < 28; i++) {
            double a = i * 2.39996 + t * (.12 + .18 * hash(i + 29));
            double r = 1.6 + hash(i + 8) * 3.2 + t * (.3 + .8 * hash(i + 45));
            var p = new Vec3(e.center.x + Math.cos(a) * r, e.floor + .35 + hash(i + 78) * 1.5 + t * .28, e.center.z + Math.sin(a) * r);
            d.cloud(p, .6 + hash(i + 93) * .8 + t * .45, 0xbfbcb0,
                    smooth(t / .18) * Math.exp(-t * 1.0) * (.17 + .13 * hash(i)), i + 440);
        }
        for (int i = 0; i < 60; i++) {
            double delay = hash(i + 201) * .14, life = t - delay;
            if (life < 0) continue;
            double a = i * 2.39996 + hash(i + 54);
            double radius = 2 + (5 + hash(i) * 6) * (1 - Math.exp(-life * 2.5));
            double y = e.floor + .22 + life * (.4 + hash(i + 72) * 1.3);
            var p = new Vec3(e.center.x + Math.cos(a) * radius, y, e.center.z + Math.sin(a) * radius);
            double opacity = smooth(life / .09) * Math.exp(-life * 1.1) * (.30 + .16 * hash(i));
            d.cloud(p, .6 + hash(i + 69) * .7 + life * (1.6 + hash(i + 9)), i % 3 == 0 ? 0xd4cbb1 : 0x9b9685, opacity, i);
        }
    }

    private static void shake(ViewportEvent.ComputeCameraAngles event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.level != world || mc.isPaused()) return;
        double now = mc.level.getGameTime() + event.getPartialTick();
        double intensity = 0;
        for (var e : EFFECTS.values()) {
            double t = (now - e.start - 60) / 20;
            if (t < 0 || t > .85) continue;
            double distance = event.getCamera().getPosition().distanceTo(e.center);
            intensity += Math.exp(-t * 6) * unit(1 - distance / 22) * 1.5;
        }
        // Honor Minecraft's screen-effect accessibility control, including zero.
        intensity *= mc.options.screenEffectScale().get();
        intensity = Math.min(1.8, intensity);
        event.setPitch(event.getPitch() + (float) (Math.sin(now * 2.7) * intensity));
        event.setYaw(event.getYaw() + (float) (Math.sin(now * 3.4) * intensity * .65));
        event.setRoll(event.getRoll() + (float) (Math.cos(now * 2.1) * intensity * .7));
    }

    /** Three texture batches share depth-aware, state-preserving rendering. */
    private static final class Draw {
        final Matrix4f matrix;
        final Vec3 camera, right, up;
        BufferBuilder buffer;
        double floor, scale = 1;
        Vec3 origin = Vec3.ZERO;
        void effect(Effect e) { floor = e.floor; scale = e.scale; origin = new Vec3(e.center.x, floor, e.center.z); }
        Vec3 world(Vec3 p) { return scale == 1 ? p : origin.add(p.subtract(origin).scale(scale)); }
        Draw(Matrix4f matrix, Vec3 camera, Vec3 right, Vec3 up) {
            this.matrix = matrix; this.camera = camera; this.right = right; this.up = up;
        }
        void begin() { buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR); }
        void end(boolean additive) {
            var mesh = buffer.build();
            if (mesh == null) return;
            var parentShader = RenderSystem.getShader();
            // Let the active renderer choose its scene framebuffer, then shade our
            // sprites without particle cutout or material classification heuristics.
            parentShader.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
            parentShader.apply();
            var shader = MinecraftMachinesLeaperFxShader.shader;
            if (shader == null) shader = parentShader;
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
            shader.apply();
            // Iris routes this draw through its particle program with a 0.1 cutout
            // threshold. Our blended energy/dust needs the full alpha range. Restore
            // the actual uniform afterward so the pack's cached value stays valid.
            int alphaLocation = org.lwjgl.opengl.GL20.glGetUniformLocation(shader.getId(), "iris_currentAlphaTest");
            float previousAlphaTest = alphaLocation >= 0 ? org.lwjgl.opengl.GL20.glGetUniformf(shader.getId(), alphaLocation) : 0;
            // Draw only scene colour. Shader-pack auxiliary material/transmission outputs
            // are opaque even where our sprite is transparent and would leave flat cards.
            int targets = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_MAX_DRAW_BUFFERS);
            var masks = org.lwjgl.BufferUtils.createByteBuffer(targets * 4);
            for (int i = 0; i < targets; i++) {
                masks.position(i * 4);
                GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, i, masks);
                GL30.glColorMaski(i, i == 0, i == 0, i == 0, false);
            }
            var caps = GL.getCapabilities();
            int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            boolean blending = GL30.glIsEnabledi(GL11.GL_BLEND, 0), depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            try {
                // Iris locks/defers GlStateManager blending while its program is active.
                // Change only this draw's actual GL state, restoring it before shader.clear
                // so both Minecraft's cache and the shader pack remain consistent.
                if (alphaLocation >= 0) org.lwjgl.opengl.GL20.glUniform1f(alphaLocation, 0);
                GL30.glEnablei(GL11.GL_BLEND, 0);
                int destination = additive ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA;
                if (caps.OpenGL40) GL40.glBlendFuncSeparatei(0, GL11.GL_SRC_ALPHA, destination, GL11.GL_ZERO, GL11.GL_ONE);
                else if (caps.GL_ARB_draw_buffers_blend) ARBDrawBuffersBlend.glBlendFuncSeparateiARB(0, GL11.GL_SRC_ALPHA, destination, GL11.GL_ZERO, GL11.GL_ONE);
                else GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, destination, GL11.GL_ZERO, GL11.GL_ONE);
                GL11.glDepthMask(false);
                BufferUploader.draw(mesh);
            } finally {
                if (alphaLocation >= 0) org.lwjgl.opengl.GL20.glUniform1f(alphaLocation, previousAlphaTest);
                if (caps.OpenGL40) GL40.glBlendFuncSeparatei(0, srcRgb, dstRgb, srcAlpha, dstAlpha);
                else if (caps.GL_ARB_draw_buffers_blend) ARBDrawBuffersBlend.glBlendFuncSeparateiARB(0, srcRgb, dstRgb, srcAlpha, dstAlpha);
                else GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                if (blending) GL30.glEnablei(GL11.GL_BLEND, 0); else GL30.glDisablei(GL11.GL_BLEND, 0);
                GL11.glDepthMask(depthWrite);
                for (int i = 0; i < targets; i++)
                    GL30.glColorMaski(i, masks.get(i*4)!=0, masks.get(i*4+1)!=0, masks.get(i*4+2)!=0, masks.get(i*4+3)!=0);
                shader.clear();
                if (shader != parentShader) parentShader.clear();
            }
        }
        void ribbon(Vec3 a, Vec3 b, double width, int color, double alpha) {
            Vec3 side = b.subtract(a).cross(camera.subtract(world(a)));
            if (side.lengthSqr() < 1e-10) return;
            side = side.normalize().scale(width * .5);
            Vec3[] corners = {a.add(side), a.subtract(side), b.subtract(side), b.add(side)};
            for (int i = 0; i < 4; i++) {
                var p = world(corners[i]);
                buffer.addVertex(matrix, (float)(p.x-camera.x), (float)(p.y-camera.y), (float)(p.z-camera.z))
                        .setUv(i == 0 || i == 3 ? 0 : 1, .5f)
                        .setColor((color>>16)&255, (color>>8)&255, color&255, (int)(unit(alpha)*255));
            }
        }
        void glow(Vec3 c, double radius, int color, double alpha) {
            // Fade through a band above the support plane instead of cutting a flat
            // billboard through the ground. Fine strips work with flat-colour shader packs.
            double bottom = c.y - radius * (Math.abs(up.y) + Math.abs(right.y));
            int strips = bottom < floor + .8 ? Math.max(1, (int)Math.ceil(radius * 32)) : 1;
            for (int row = 0; row < strips; row++) {
                double v0 = row / (double)strips, v1 = (row + 1) / (double)strips;
                double y0 = radius * (2 * v0 - 1), y1 = radius * (2 * v1 - 1);
                double opacity = alpha * smooth((c.y + up.y * (y0 + y1) * .5 - floor - .04) / .8);
                if (opacity < .002) continue;
                Vec3[] corners = {c.subtract(right.scale(radius)).add(up.scale(y0)),
                        c.add(right.scale(radius)).add(up.scale(y0)),
                        c.add(right.scale(radius)).add(up.scale(y1)),
                        c.subtract(right.scale(radius)).add(up.scale(y1))};
                for (int i = 0; i < 4; i++) {
                    var p = world(corners[i]);
                    buffer.addVertex(matrix, (float)(p.x-camera.x), (float)(p.y-camera.y), (float)(p.z-camera.z))
                            .setUv(i == 0 || i == 3 ? 0 : 1, (float)(1 - (i < 2 ? v0 : v1)))
                            .setColor((color>>16)&255, (color>>8)&255, color&255, (int)(unit(opacity)*255));
                }
            }
        }
        void cloud(Vec3 c, double radius, int color, double alpha, int seed) {
            // Independent ray-integrated volumes, varied in orientation and aspect, form
            // a continuous front without repeating a single soft circular stamp.
            Vec3 x = right.scale(radius * (1 + .45 * hash(seed + 7)) * (seed % 2 == 0 ? 1 : -1));
            Vec3 y = up.scale(radius * (.65 + .3 * hash(seed + 8)));
            int tile = Math.floorMod(seed, 16);
            double u0 = (tile % 4) * .25, v0 = (tile / 4) * .25;
            int strips = Math.max(1, (int)Math.ceil(radius * 24));
            for (int row = 0; row < strips; row++) {
                double a = row / (double)strips, b = (row + 1) / (double)strips;
                Vec3 mid = c.add(y.scale(a + b - 1));
                double opacity = alpha * smooth((mid.y - floor - .02) / .85);
                if (opacity < .002) continue;
                Vec3[] points = { c.subtract(x).add(y.scale(2*a-1)), c.add(x).add(y.scale(2*a-1)),
                        c.add(x).add(y.scale(2*b-1)), c.subtract(x).add(y.scale(2*b-1)) };
                for (int j = 0; j < 4; j++) {
                    Vec3 p = world(points[j]);
                    buffer.addVertex(matrix, (float)(p.x-camera.x), (float)(p.y-camera.y), (float)(p.z-camera.z))
                            .setUv((float)(u0 + (j == 0 || j == 3 ? 0 : .25)), (float)(v0 + (1 - (j < 2 ? a : b)) * .25))
                            .setColor((color>>16)&255, (color>>8)&255, color&255, (int)(unit(opacity)*255));
                }
            }
        }
        void billboardRing(Vec3 c, double radius, double width, int color, double alpha) { ring(c, right, up, radius, width, color, alpha); }
        void groundRing(Vec3 c, double radius, double width, int color, double alpha) { ring(c, new Vec3(1, 0, 0), new Vec3(0, 0, 1), radius, width, color, alpha); }
        void ring(Vec3 c, Vec3 x, Vec3 y, double radius, double width, int color, double alpha) {
            // Constant tint per quad; texture alpha supplies the continuous radial falloff,
            // including shader packs that declare their vertex colour varying flat.
            for (int i = 0; i < 192; i++) {
                double a = i * TAU / 192, b = (i + 1) * TAU / 192;
                Vec3 u = x.scale(Math.cos(a)).add(y.scale(Math.sin(a))).scale(1 + .025 * Math.sin(a * 7) + .015 * Math.sin(a * 13)),
                        v = x.scale(Math.cos(b)).add(y.scale(Math.sin(b))).scale(1 + .025 * Math.sin(b * 7) + .015 * Math.sin(b * 13));
                Vec3[] corners = {c.add(u.scale(radius-width)), c.add(u.scale(radius+width)),
                        c.add(v.scale(radius+width)), c.add(v.scale(radius-width))};
                for (int j = 0; j < 4; j++) {
                    var p = world(corners[j]);
                    buffer.addVertex(matrix, (float)(p.x-camera.x), (float)(p.y-camera.y), (float)(p.z-camera.z))
                            .setUv(j == 0 || j == 3 ? 0 : 1, .5f)
                            .setColor((color>>16)&255, (color>>8)&255, color&255, (int)(unit(alpha * (.4 + .6 * Math.pow(.5 + .5 * Math.sin(a * 7 + radius * 1.5), 2)))*255));
                }
            }
        }
    }
}
