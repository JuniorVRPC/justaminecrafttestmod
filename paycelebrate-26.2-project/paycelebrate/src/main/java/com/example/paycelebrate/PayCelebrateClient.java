package com.example.paycelebrate;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PayCelebrateClient implements ClientModInitializer {

    // ====== EDIT THESE ======
    // Real payment message. Group 1 = payer name, group 2 = amount. Adjust to DonutSMP's exact wording.
    private static final Pattern PAY_PATTERN =
            Pattern.compile("(\\w{3,16}) paid you \\$([\\d,]+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    // "Fake" trigger: any chat message containing $1 or "1 dollar"
    private static final Pattern FAKE_PATTERN =
            Pattern.compile("(\\$1(?![\\d,]|\\.\\d))|(\\b1\\s*dollars?\\b)", Pattern.CASE_INSENSITIVE);
    private static final long DURATION_MS = 4000;
    // ========================

    private static final Random RNG = new Random();
    private static final int[] COLORS = {
            0xFFFF4D4D, 0xFFFFD84D, 0xFF4DFF88, 0xFF4DB8FF, 0xFFB84DFF, 0xFFFF4DD2
    };

    private static class Confetti {
        float x, y, vx, vy, size;
        int color;
    }

    private final List<Confetti> confetti = new ArrayList<>();
    private long startTime = -1;
    private String payer = "";

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            handle(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) ->
                handle(message.getString()));

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("paycelebrate", "overlay"),
                this::render);
    }

    private void handle(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String me = mc.player.getName().getString();

        String name = null;
        Matcher pay = PAY_PATTERN.matcher(text);
        if (pay.find()) {
            try {
                double amount = Double.parseDouble(pay.group(2).replace(",", ""));
                if (amount == 1.0) name = pay.group(1);
            } catch (NumberFormatException e) {
                return;
            }
        }
        if (name == null && FAKE_PATTERN.matcher(text).find()) {
            name = findPlayerInText(mc, text, me);
        }
        if (name == null || name.equalsIgnoreCase(me)) return;
        if (startTime > 0 && System.currentTimeMillis() - startTime < 1000) return; // cooldown
        startCelebration(name);
    }

    private String findPlayerInText(Minecraft mc, String text, String me) {
        if (mc.getConnection() == null) return "Someone";
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String n = info.getProfile().name();
            if (!n.equalsIgnoreCase(me) && text.contains(n)) return n;
        }
        return "Someone";
    }

    private void startCelebration(String name) {
        Minecraft mc = Minecraft.getInstance();
        payer = name;
        startTime = System.currentTimeMillis();
        confetti.clear();

        int w = mc.getWindow().getGuiScaledWidth();
        for (int i = 0; i < 250; i++) {
            Confetti c = new Confetti();
            c.x = RNG.nextFloat() * w;
            c.y = -RNG.nextFloat() * 120f;
            c.vx = (RNG.nextFloat() - 0.5f) * 2f;
            c.vy = 1.5f + RNG.nextFloat() * 3f;
            c.size = 2 + RNG.nextInt(3);
            c.color = COLORS[RNG.nextInt(COLORS.length)];
            confetti.add(c);
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f));
    }

    private void drawCentered(GuiGraphicsExtractor g, Minecraft mc, String s, int x, int y, int color) {
        g.text(mc.font, s, x - mc.font.width(s) / 2, y, color, true);
    }

    private void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (startTime < 0) return;
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > DURATION_MS) {
            startTime = -1;
            confetti.clear();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        float fade = Math.min(1f, Math.min(elapsed / 300f, (DURATION_MS - elapsed) / 600f));
        int alpha = Math.max(5, (int) (fade * 255));
        int green = (alpha << 24) | 0x55FF55;
        int white = (alpha << 24) | 0xFFFFFF;

        // Confetti
        for (Confetti c : confetti) {
            c.x += c.vx;
            c.y += c.vy;
            if (c.y > h) { c.y = -10; c.x = RNG.nextFloat() * w; }
            g.fill((int) c.x, (int) c.y, (int) (c.x + c.size), (int) (c.y + c.size), c.color);
        }

        // Big green $1 on screen
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(w / 2f, h / 3f);
        pose.scale(4f, 4f);
        drawCentered(g, mc, "$1", 0, 0, green);
        pose.popMatrix();
        drawCentered(g, mc, payer + " sent you $1", w / 2, h / 3 + 40, white);

        // Green $1 floating above the sender's head
        if (mc.level != null && mc.player != null) {
            Player target = null;
            for (Player p : mc.level.players()) {
                if (p.getName().getString().equalsIgnoreCase(payer)) { target = p; break; }
            }
            if (target != null) {
                float pt = delta.getGameTimeDeltaPartialTick(false);
                double rise = elapsed / 1000.0 * 0.5; // floats upward
                Vec3 pos = target.position().add(0, target.getBbHeight() + 0.6 + rise, 0);
                double[] s = project(mc, mc.player, pt, pos, w, h);
                if (s != null) {
                    pose.pushMatrix();
                    pose.translate((float) s[0], (float) s[1]);
                    pose.scale(2f, 2f);
                    drawCentered(g, mc, "$1", 0, 0, green);
                    pose.popMatrix();
                }
            }
        }
    }

    // World position -> scaled screen coordinates, using the player's view (best in first person)
    private double[] project(Minecraft mc, Player me, float pt, Vec3 target, int w, int h) {
        Vec3 eye = me.getEyePosition(pt);
        Vec3 d = target.subtract(eye);
        double yr = Math.toRadians(me.getViewYRot(pt));
        double pr = Math.toRadians(me.getViewXRot(pt));
        Vec3 forward = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        Vec3 right = new Vec3(-Math.cos(yr), 0, -Math.sin(yr));
        Vec3 up = right.cross(forward);

        double z = d.dot(forward);
        if (z < 0.1) return null;
        double x = d.dot(right);
        double y = d.dot(up);

        double fov = Math.toRadians(mc.options.fov().get());
        double f = 1.0 / Math.tan(fov / 2.0);
        return new double[]{
                w / 2.0 + (x / z) * f * (h / 2.0),
                h / 2.0 - (y / z) * f * (h / 2.0)
        };
    }
}
