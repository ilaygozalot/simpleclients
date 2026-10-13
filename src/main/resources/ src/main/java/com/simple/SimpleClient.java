package com.simple;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * SimpleClient: everything in ONE file, no mixins.
 * Menu: Right Shift.  Panic (disable all): End.
 */
public class SimpleClient implements ClientModInitializer {

    static final Minecraft mc = Minecraft.getInstance();
    static final List<Mod> MODS = new ArrayList<>();
    static KeyMapping menuKey, panicKey;

    // =====================================================================
    //  ENTRYPOINT
    // =====================================================================
    @Override
    public void onInitializeClient() {
        // ---- COMBAT ----
        MODS.add(new KillAura());
        MODS.add(new AutoCrystal());
        MODS.add(new AutoAnchor());
        MODS.add(new AutoTotem());
        // ---- RENDER ----
        MODS.add(new ESP());
        MODS.add(new OreFinder());
        MODS.add(new FreeCam());
        MODS.add(new FullBright());
        // ---- PLAYER ----
        MODS.add(new AutoMine());
        MODS.add(new AutoEat());
        MODS.add(new Scaffold());
        MODS.add(new AutoRespawn());
        MODS.add(new AntiAFK());
        MODS.add(new DeathCoords());
        MODS.add(new PlayerAlert());
        // ---- MOVEMENT ----
        MODS.add(new Sprint());
        MODS.add(new AutoWalk());
        MODS.add(new AutoJump());
        MODS.add(new Sneak());
        MODS.add(new Fly());
        MODS.add(new Speed());
        MODS.add(new Glide());

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("simpleclient", "main"));
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.simpleclient.menu",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, cat));
        panicKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.simpleclient.panic",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_END, cat));
        for (Mod m : MODS) {
            m.key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "key.simpleclient." + m.name.toLowerCase(), InputConstants.Type.KEYSYM, m.defKey, cat));
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean inWorld = mc.player != null && mc.level != null;
            if (!inWorld) {
                while (menuKey.consumeClick()) { }
                while (panicKey.consumeClick()) { }
                for (Mod m : MODS) {
                    while (m.key.consumeClick()) { }
                    if (m.on) { m.on = false; try { m.disable(); } catch (Throwable ignored) { } }
                }
                return;
            }
            while (menuKey.consumeClick()) mc.setScreen(new Menu());
            while (panicKey.consumeClick()) panic();
            for (Mod m : MODS) {
                while (m.key.consumeClick()) m.toggle();
                if (!m.on) continue;
                try {
                    m.tick();
                } catch (Throwable t) {       // a broken module never crashes the game
                    m.on = false;
                    try { m.disable(); } catch (Throwable ignored) { }
                    chat("§c" + m.name + " crashed and was disabled: " + t);
                }
            }
        });
    }

    static void panic() {
        for (Mod m : MODS) m.set(false);
    }

    // =====================================================================
    //  SETTINGS
    // =====================================================================
    static abstract class Setting {
        final String name;
        Setting(String n) { name = n; }
        abstract String text();
        abstract void cycle();
    }

    static class Bool extends Setting {
        boolean v;
        Bool(String n, boolean d) { super(n); v = d; }
        String text() { return name + ": " + (v ? "§aON" : "§cOFF"); }
        void cycle() { v = !v; }
    }

    static class Num extends Setting {
        double v; final double min, max, step;
        Num(String n, double d, double min, double max, double step) { super(n); v = d; this.min = min; this.max = max; this.step = step; }
        int i() { return (int) Math.round(v); }
        String text() { return name + ": " + (v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v)); }
        void cycle() { v = Math.round((v + step) * 100.0) / 100.0; if (v > max + 1e-9) v = min; }
    }

    static class Choice extends Setting {
        final String[] opts; int idx;
        Choice(String n, String... o) { super(n); opts = o; }
        boolean is(String s) { return opts[idx].equals(s); }
        String text() { return name + ": §e" + opts[idx]; }
        void cycle() { idx = (idx + 1) % opts.length; }
    }

    // =====================================================================
    //  MODULE BASE
    // =====================================================================
    static abstract class Mod {
        final String name; final int defKey;
        boolean on; KeyMapping key;
        final List<Setting> settings = new ArrayList<>();

        Mod(String name, int defKey) { this.name = name; this.defKey = defKey; }
        <T extends Setting> T add(T s) { settings.add(s); return s; }

        void enable() { }
        void disable() { }
        void tick() { }

        void toggle() { set(!on); }

        void set(boolean v) {
            if (v == on) return;
            on = v;
            try { if (v) enable(); else disable(); } catch (Throwable t) { on = false; chat("§c" + name + " error: " + t); return; }
            chat(name + ": " + (on ? "§aON" : "§cOFF"));
        }
    }

    static final int NONE = GLFW.GLFW_KEY_UNKNOWN;

    // =====================================================================
    //  HELPERS
    // =====================================================================
    static void chat(String s) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(s));
    }

    /** Action-bar text. If this line fails to compile, replace with chat(s). */
    static void bar(String s) {
        if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(s));
    }

    static boolean passable(BlockPos p) {
        return mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty();
    }

    static boolean replaceable(BlockPos p) {
        BlockState s = mc.level.getBlockState(p);
        return s.isAir() || s.canBeReplaced();
    }

    static int hotbar(Predicate<ItemStack> f) {
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (f.test(inv.getItem(i))) return i;
        return -1;
    }

    static void select(int slot) { mc.player.getInventory().setSelectedSlot(slot); }
    static int selected() { return mc.player.getInventory().getSelectedSlot(); }

    static void lookAt(Vec3 t) {
        Vec3 e = mc.player.getEyePosition();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    /** Right-click a block face with the item in hand. */
    static void click(BlockPos block, Direction face) {
        Vec3 hit = Vec3.atCenterOf(block).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, block, false));
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    /** Place the held block at target by clicking any solid neighbour. */
    static boolean placeBlock(BlockPos target) {
        for (Direction d : Direction.values()) {
            BlockPos nb = target.relative(d);
            if (!replaceable(nb)) { click(nb, d.getOpposite()); return true; }
        }
        return false;
    }

    static Player nearestPlayer(double range) {
        Player best = null; double bd = range * range;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isSpectator()) continue;
            double d = mc.player.distanceToSqr(p);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Horizontal unit vector from WASD relative to the given yaw. */
    static Vec3 inputDir(float yawDeg) {
        double f = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double s = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        if (f == 0 && s == 0) return Vec3.ZERO;
        double r = Math.toRadians(yawDeg), sin = Math.sin(r), cos = Math.cos(r);
        double x = -sin * f + cos * s, z = cos * f + sin * s;
        double l = Math.sqrt(x * x + z * z);
        return new Vec3(x / l, 0, z / l);
    }

    static boolean isOre(BlockState s) {
        return s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.GOLD_ORES)
                || s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
                || s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES)
                || s.is(Blocks.ANCIENT_DEBRIS) || s.is(Blocks.NETHER_QUARTZ_ORE) || s.is(Blocks.NETHER_GOLD_ORE);
    }

    // =====================================================================
    //  COMBAT
    // =====================================================================
    static class KillAura extends Mod {
        final Num range = add(new Num("Range", 4.0, 2.0, 6.0, 0.5));
        final Bool players = add(new Bool("Players", true));
        final Bool hostile = add(new Bool("Hostile mobs", true));
        KillAura() { super("KillAura", GLFW.GLFW_KEY_R); }

        @Override void tick() {
            if (mc.screen != null) return;
            Entity best = null; double bd = range.v * range.v;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == mc.player || !(e instanceof LivingEntity le) || !le.isAlive() || e instanceof ArmorStand) continue;
                boolean ok = (players.v && e instanceof Player p && !p.isSpectator()) || (hostile.v && e instanceof Enemy);
                if (!ok) continue;
                double d = mc.player.distanceToSqr(e);
                if (d < bd) { bd = d; best = e; }
            }
            if (best == null) return;
            lookAt(best.getEyePosition());
            if (mc.player.getAttackStrengthScale(0.5f) >= 1.0f) {
                mc.gameMode.attack(mc.player, best);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    /** Simple version: no damage calculation. Places on obsidian/bedrock near the target, then breaks. */
    static class AutoCrystal extends Mod {
        final Num range = add(new Num("Target range", 8, 3, 12, 1));
        final Num placeDelay = add(new Num("Place delay", 2, 0, 10, 1));
        final Num breakDelay = add(new Num("Break delay", 1, 0, 10, 1));
        final Num keepAway = add(new Num("Min dist from me", 3, 1, 5, 0.5));
        final Num minHp = add(new Num("Min health", 8, 0, 20, 2));
        final Bool autoSwitch = add(new Bool("Auto switch", true));
        int pt, bt;
        AutoCrystal() { super("AutoCrystal", NONE); }

        @Override void tick() {
            if (mc.screen != null) return;
            Player t = nearestPlayer(range.v);
            if (t == null || mc.player.getHealth() < minHp.v) return;
            pt++; bt++;

            // ---- break ----
            if (bt > breakDelay.v) {
                for (EndCrystal c : mc.level.getEntitiesOfClass(EndCrystal.class, mc.player.getBoundingBox().inflate(5))) {
                    double dMe = mc.player.distanceTo(c);
                    if (dMe > 4.5 || dMe < keepAway.v || c.distanceTo(t) > 6) continue;
                    lookAt(c.position().add(0, 0.5, 0));
                    mc.gameMode.attack(mc.player, c);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    bt = 0;
                    return;
                }
            }

            // ---- place ----
            if (pt <= placeDelay.v) return;
            int slot = hotbar(s -> s.is(Items.END_CRYSTAL));
            if (slot < 0) return;
            if (selected() != slot && !autoSwitch.v) return;

            Vec3 eye = mc.player.getEyePosition();
            BlockPos me = mc.player.blockPosition(), best = null;
            double bs = 1e9;
            for (int x = -4; x <= 4; x++) for (int y = -3; y <= 3; y++) for (int z = -4; z <= 4; z++) {
                BlockPos pos = me.offset(x, y, z);
                BlockState st = mc.level.getBlockState(pos);
                if (!(st.is(Blocks.OBSIDIAN) || st.is(Blocks.BEDROCK))) continue;
                if (!mc.level.getBlockState(pos.above()).isAir() || !mc.level.getBlockState(pos.above(2)).isAir()) continue;
                if (eye.distanceTo(Vec3.atCenterOf(pos)) > 4.5) continue;
                Vec3 c = Vec3.atBottomCenterOf(pos.above());
                if (c.distanceTo(mc.player.position()) < keepAway.v) continue;
                double d = c.distanceTo(t.position());
                if (d > 6 || d >= bs) continue;
                if (!mc.level.getEntities((Entity) null, new AABB(pos.above())).isEmpty()) continue;
                bs = d; best = pos;
            }
            if (best == null) return;
            select(slot);
            lookAt(Vec3.atCenterOf(best));
            click(best, Direction.UP);
            pt = 0;
        }
    }

    /** Place anchor, charge with glowstone, explode. Does NOT explode in the Nether (vanilla rule). */
    static class AutoAnchor extends Mod {
        final Num range = add(new Num("Target range", 8, 3, 12, 1));
        final Num delay = add(new Num("Delay", 2, 0, 10, 1));
        final Num keepAway = add(new Num("Min dist from me", 4, 3, 6, 0.5));
        final Num minHp = add(new Num("Min health", 10, 0, 20, 2));
        int t;
        AutoAnchor() { super("AutoAnchor", NONE); }

        @Override void tick() {
            if (mc.level.dimension() == Level.NETHER) { chat("§cAnchors don't explode in the Nether."); set(false); return; }
            if (mc.screen != null || ++t <= delay.v) return;
            Player tgt = nearestPlayer(range.v);
            if (tgt == null || mc.player.getHealth() < minHp.v) return;

            Vec3 eye = mc.player.getEyePosition();
            BlockPos me = mc.player.blockPosition();

            // 1) existing anchor near the target?
            BlockPos anchor = null; double bd = 1e9;
            for (int x = -4; x <= 4; x++) for (int y = -4; y <= 4; y++) for (int z = -4; z <= 4; z++) {
                BlockPos p = me.offset(x, y, z);
                if (!mc.level.getBlockState(p).is(Blocks.RESPAWN_ANCHOR)) continue;
                Vec3 c = Vec3.atCenterOf(p);
                if (eye.distanceTo(c) > 4.5 || c.distanceTo(mc.player.position()) < keepAway.v) continue;
                double d = c.distanceTo(tgt.position());
                if (d <= 5 && d < bd) { bd = d; anchor = p; }
            }
            if (anchor != null) {
                int charge = mc.level.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE);
                if (charge == 0) {
                    int g = hotbar(s -> s.is(Items.GLOWSTONE));
                    if (g < 0) return;
                    select(g);
                } else {
                    int s = hotbar(ItemStack::isEmpty);
                    if (s < 0) s = hotbar(i -> !i.is(Items.GLOWSTONE) && !i.is(Items.RESPAWN_ANCHOR));
                    if (s < 0) return;
                    select(s);
                }
                lookAt(Vec3.atCenterOf(anchor));
                click(anchor, Direction.UP);
                t = 0;
                return;
            }

            // 2) place a new anchor next to the target
            int a = hotbar(s -> s.is(Items.RESPAWN_ANCHOR));
            if (a < 0) return;
            BlockPos tp = tgt.blockPosition(), best = null; double bs = 1e9;
            for (int x = -2; x <= 2; x++) for (int y = 0; y <= 2; y++) for (int z = -2; z <= 2; z++) {
                BlockPos p = tp.offset(x, y, z);
                if (!replaceable(p)) continue;
                Vec3 c = Vec3.atCenterOf(p);
                if (eye.distanceTo(c) > 4.5 || c.distanceTo(mc.player.position()) < keepAway.v) continue;
                if (!mc.level.getEntities((Entity) null, new AABB(p)).isEmpty()) continue;
                boolean support = false;
                for (Direction d : Direction.values()) if (!replaceable(p.relative(d))) { support = true; break; }
                if (!support) continue;
                double d = c.distanceTo(tgt.position());
                if (d < bs) { bs = d; best = p; }
            }
            if (best == null) return;
            select(a);
            lookAt(Vec3.atCenterOf(best));
            placeBlock(best);
            t = 0;
        }
    }

    /** Moves a totem from the hotbar into the offhand (3-phase, so the server stays in sync). */
    static class AutoTotem extends Mod {
        int phase, prev, slot;
        AutoTotem() { super("AutoTotem", NONE); }

        @Override void tick() {
            if (phase == 0) {
                if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) return;
                slot = hotbar(s -> s.is(Items.TOTEM_OF_UNDYING));
                if (slot < 0) return;
                prev = selected(); select(slot); phase = 1;
            } else if (phase == 1) {
                mc.getConnection().send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                phase = 2;
            } else {
                select(prev); phase = 0;
            }
        }
        @Override void disable() { phase = 0; }
    }

    // =====================================================================
    //  RENDER
    // =====================================================================
    /** Through-wall outline using the vanilla glow effect (client side only). */
    static class ESP extends Mod {
        final Bool players = add(new Bool("Players", true));
        final Bool hostile = add(new Bool("Hostile mobs", true));
        final Bool items = add(new Bool("Items", true));
        ESP() { super("ESP", GLFW.GLFW_KEY_H); }

        @Override void tick() {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == mc.player) continue;
                boolean want = (players.v && e instanceof Player) || (hostile.v && e instanceof Enemy)
                        || (items.v && e instanceof ItemEntity);
                e.setGlowingTag(want);
            }
        }
        @Override void disable() {
            if (mc.level == null) return;
            for (Entity e : mc.level.entitiesForRendering()) if (e != mc.player) e.setGlowingTag(false);
        }
    }

    /** Shows the nearest ore (name, distance, coordinates) in the action bar. */
    static class OreFinder extends Mod {
        final Num radius = add(new Num("Radius", 16, 4, 32, 4));
        final Bool exposedOnly = add(new Bool("Only exposed", false));
        int timer;
        OreFinder() { super("OreFinder", NONE); }

        @Override void tick() {
            if (timer-- > 0) return;
            timer = 20;
            int r = radius.i();
            BlockPos o = mc.player.blockPosition(), best = null;
            double bd = 1e18;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
                p.set(o.getX() + x, o.getY() + y, o.getZ() + z);
                if (!isOre(mc.level.getBlockState(p))) continue;
                if (exposedOnly.v) {
                    boolean ex = false;
                    for (Direction d : Direction.values()) if (passable(p.relative(d))) { ex = true; break; }
                    if (!ex) continue;
                }
                double d = x * x + y * y + z * z;
                if (d < bd) { bd = d; best = p.immutable(); }
            }
            if (best == null) { bar("§7No ore within " + r + " blocks"); return; }
            String n = mc.level.getBlockState(best).getBlock().getName().getString();
            bar("§b" + n + " §f" + (int) Math.sqrt(bd) + "m  §7[" + best.getX() + " " + best.getY() + " " + best.getZ() + "]");
        }
    }

    /** Camera detaches via an invisible camera entity; your body is held in place. */
    static class FreeCam extends Mod {
        final Num speed = add(new Num("Speed", 1.0, 0.2, 4.0, 0.2));
        ArmorStand cam; Vec3 pos, frozen; float yaw0, pitch0;
        FreeCam() { super("FreeCam", GLFW.GLFW_KEY_G); }

        void moveCam(boolean snap) {
            if (snap) { cam.xo = pos.x; cam.yo = pos.y - cam.getEyeHeight(); cam.zo = pos.z; }
            else { cam.xo = cam.getX(); cam.yo = cam.getY(); cam.zo = cam.getZ(); }
            cam.setPos(pos.x, pos.y - cam.getEyeHeight(), pos.z);
        }

        @Override void enable() {
            cam = new ArmorStand(EntityType.ARMOR_STAND, mc.level);
            cam.setInvisible(true);
            cam.setNoGravity(true);
            pos = mc.player.getEyePosition();
            frozen = mc.player.position();
            yaw0 = mc.player.getYRot(); pitch0 = mc.player.getXRot();
            moveCam(true);
            mc.setCameraEntity(cam);
        }

        @Override void tick() {
            if (cam == null) return;
            float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
            if (mc.screen == null) {
                Vec3 d = inputDir(yaw).scale(speed.v);
                double vy = ((mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0)) * speed.v;
                pos = pos.add(d.x, vy, d.z);
            }
            moveCam(false);
            cam.setYRot(yaw); cam.setXRot(pitch);
            cam.yRotO = yaw; cam.xRotO = pitch;
            // hold the real body still
            mc.player.setPos(frozen.x, frozen.y, frozen.z);
            mc.player.setDeltaMovement(Vec3.ZERO);
        }

        @Override void disable() {
            if (mc.player != null) {
                mc.setCameraEntity(mc.player);
                mc.player.setYRot(yaw0); mc.player.setXRot(pitch0);
            }
            cam = null;
        }
    }

    static class FullBright extends Mod {
        int timer;
        FullBright() { super("FullBright", NONE); }
        @Override void tick() {
            if (timer-- > 0) return;
            timer = 100;
            mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 400, 0, false, false));
        }
        @Override void disable() { if (mc.player != null) mc.player.removeEffect(MobEffects.NIGHT_VISION); }
    }

    // =====================================================================
    //  PLAYER
    // =====================================================================
    static class AutoMine extends Mod {
        final Choice mode = add(new Choice("Mode", "Tunnel", "Down"));
        final Choice size = add(new Choice("Tunnel size", "1x2", "3x3"));
        final Bool autoWalk = add(new Bool("Auto walk", true));
        final Bool mineOres = add(new Bool("Mine nearby ores", true));
        final Num oreRange = add(new Num("Ore range", 3, 1, 5, 1));
        final Bool autoTool = add(new Bool("Auto tool", true));
        final Bool saveTools = add(new Bool("Protect tools", true));
        final Bool stopWater = add(new Bool("Stop at water", true));
        final Bool stopDrops = add(new Bool("Stop at drops", true));
        final Bool stopInv = add(new Bool("Stop if inv full", true));
        final Num minHealth = add(new Num("Min health", 6, 0, 20, 2));
        final Num minY = add(new Num("Min Y (Down)", -55, -64, 300, 20));
        Direction dir = Direction.NORTH; boolean walking;
        AutoMine() { super("AutoMine", GLFW.GLFW_KEY_J); }

        @Override void enable() { dir = mc.player.getDirection(); }
        @Override void disable() {
            walk(false);
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        }

        void walk(boolean w) { if (w != walking) { walking = w; mc.options.keyUp.setDown(w); } }
        void halt(String why) { chat("§cAutoMine stopped: " + why); set(false); }

        @Override void tick() {
            Player p = mc.player;
            if (mc.screen != null) { walk(false); return; }
            if (p.getHealth() < minHealth.v) { halt("low health"); return; }
            if (stopInv.v && p.getInventory().getFreeSlot() == -1) { halt("inventory full"); return; }

            boolean down = mode.is("Down");
            p.setYRot(dir.toYRot());
            p.setXRot(down ? 90f : 0f);

            BlockPos feet = p.blockPosition();
            List<BlockPos> cells = down ? cellsBelow() : tunnelCells(feet);

            for (BlockPos c : cells) if (hazard(c)) { halt("liquid nearby"); return; }
            if (stopDrops.v) {
                if (down && emptyDepth(feet.below()) >= 3) { halt("cave below"); return; }
                if (!down && dropAhead(feet.relative(dir))) { halt("drop ahead"); return; }
            }
            if (down && feet.getY() <= minY.i()) { halt("reached min Y"); return; }

            if (mineOres.v) {
                BlockPos ore = findOre();
                if (ore != null) { walk(false); mine(ore); return; }
            }
            for (BlockPos c : cells) {
                if (passable(c)) continue;
                if (mc.level.getBlockState(c).getDestroySpeed(mc.level, c) < 0) { halt("unbreakable block"); return; }
                walk(false);
                mine(c);
                return;
            }
            walk(!down && autoWalk.v);
        }

        List<BlockPos> tunnelCells(BlockPos feet) {
            boolean wide = size.is("3x3");
            int r = wide ? 1 : 0, h = wide ? 3 : 2;
            Direction left = dir.getCounterClockWise();
            BlockPos front = feet.relative(dir);
            List<BlockPos> out = new ArrayList<>();
            for (int v = 0; v < h; v++) for (int lat = -r; lat <= r; lat++) out.add(front.relative(left, lat).above(v));
            return out;
        }

        List<BlockPos> cellsBelow() {
            AABB bb = mc.player.getBoundingBox();
            int y = mc.player.blockPosition().getY() - 1;
            List<BlockPos> out = new ArrayList<>();
            for (int x = (int) Math.floor(bb.minX); x <= (int) Math.floor(bb.maxX - 1e-7); x++)
                for (int z = (int) Math.floor(bb.minZ); z <= (int) Math.floor(bb.maxZ - 1e-7); z++)
                    out.add(new BlockPos(x, y, z));
            return out;
        }

        BlockPos findOre() {
            Vec3 eye = mc.player.getEyePosition();
            double reach = mc.player.blockInteractionRange();
            BlockPos base = BlockPos.containing(eye), best = null;
            double bd = Double.MAX_VALUE;
            int r = oreRange.i();
            for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
                BlockPos pos = base.offset(x, y, z);
                if (!isOre(mc.level.getBlockState(pos)) || !exposed(pos) || hazard(pos)) continue;
                double d = eye.distanceToSqr(Vec3.atCenterOf(pos));
                if (d <= reach * reach && d < bd) { bd = d; best = pos; }
            }
            return best;
        }

        boolean exposed(BlockPos pos) {
            for (Direction d : Direction.values()) if (passable(pos.relative(d))) return true;
            return false;
        }

        void mine(BlockPos pos) {
            BlockState s = mc.level.getBlockState(pos);
            if (autoTool.v && !selectTool(s)) { halt("tool about to break"); return; }
            Vec3 d = Vec3.atCenterOf(pos).subtract(mc.player.getEyePosition());
            Direction face = Direction.getApproximateNearest(d.x, d.y, d.z).getOpposite();
            mc.gameMode.continueDestroyBlock(pos, face);
        }

        boolean selectTool(BlockState state) {
            Inventory inv = mc.player.getInventory();
            int cur = inv.getSelectedSlot(), best = cur;
            float bs = usable(inv.getItem(cur)) ? inv.getItem(cur).getDestroySpeed(state) : 0f;
            for (int i = 0; i < 9; i++) {
                ItemStack st = inv.getItem(i);
                if (!usable(st)) continue;
                float sp = st.getDestroySpeed(state);
                if (sp > bs) { bs = sp; best = i; }
            }
            if (best != cur) inv.setSelectedSlot(best);
            return usable(inv.getItem(inv.getSelectedSlot()));
        }

        boolean usable(ItemStack st) {
            return !(saveTools.v && st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() <= 3);
        }

        boolean liquid(BlockPos p) {
            var f = mc.level.getFluidState(p);
            return !f.isEmpty() && (f.is(FluidTags.LAVA) || stopWater.v);
        }

        boolean hazard(BlockPos c) {
            if (liquid(c)) return true;
            for (Direction d : Direction.values()) if (liquid(c.relative(d))) return true;
            return false;
        }

        boolean dropAhead(BlockPos ahead) {
            BlockPos floor = ahead.below();
            return passable(floor) && passable(floor.below());
        }

        int emptyDepth(BlockPos target) {
            for (int i = 1; i <= 3; i++) if (!passable(target.below(i))) return i - 1;
            return 3;
        }
    }

    static class AutoEat extends Mod {
        final Num hunger = add(new Num("Eat at hunger", 14, 4, 19, 1));
        boolean eating; int prev = -1;
        AutoEat() { super("AutoEat", NONE); }

        static boolean food(ItemStack s) {
            return s.has(DataComponents.FOOD) && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE)
                    && !s.is(Items.POISONOUS_POTATO) && !s.is(Items.PUFFERFISH)
                    && !s.is(Items.CHORUS_FRUIT) && !s.is(Items.SUSPICIOUS_STEW);
        }

        void stopEating() {
            mc.options.keyUse.setDown(false);
            if (prev >= 0) select(prev);
            eating = false; prev = -1;
        }

        @Override void tick() {
            int food = mc.player.getFoodData().getFoodLevel();
            if (!eating) {
                if (mc.screen != null || food > hunger.v) return;
                int s = hotbar(AutoEat::food);
                if (s < 0) return;
                prev = selected(); select(s); eating = true;
            }
            if (food >= 20 || !food(mc.player.getMainHandItem())) { stopEating(); return; }
            mc.options.keyUse.setDown(true);
        }
        @Override void disable() { if (eating) stopEating(); }
    }

    static class Scaffold extends Mod {
        Scaffold() { super("Scaffold", NONE); }
        @Override void tick() {
            if (mc.screen != null) return;
            BlockPos below = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.5, mc.player.getZ());
            if (!replaceable(below)) return;
            int s = hotbar(st -> st.getItem() instanceof BlockItem bi
                    && !(bi.getBlock() instanceof FallingBlock)
                    && bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(mc.level, below));
            if (s < 0) return;
            select(s);
            placeBlock(below);
        }
    }

    static class AutoRespawn extends Mod {
        AutoRespawn() { super("AutoRespawn", NONE); }
        @Override void tick() { if (mc.player.getHealth() <= 0f) mc.player.respawn(); }
    }

    static class AntiAFK extends Mod {
        int t; boolean jumped;
        AntiAFK() { super("AntiAFK", NONE); }
        @Override void tick() {
            if (jumped) { mc.options.keyJump.setDown(false); jumped = false; }
            if (++t < 300) return;
            t = 0;
            mc.options.keyJump.setDown(true); jumped = true;
            mc.player.setYRot(mc.player.getYRot() + (mc.level.random.nextBoolean() ? 15f : -15f));
        }
        @Override void disable() { mc.options.keyJump.setDown(false); }
    }

    static class DeathCoords extends Mod {
        boolean dead;
        DeathCoords() { super("DeathCoords", NONE); }
        @Override void tick() {
            if (mc.player.getHealth() <= 0f) {
                if (!dead) {
                    dead = true;
                    chat("§cYou died at §f" + mc.player.getBlockX() + " " + mc.player.getBlockY() + " " + mc.player.getBlockZ());
                }
            } else dead = false;
        }
    }

    static class PlayerAlert extends Mod {
        final Set<UUID> seen = new HashSet<>();
        PlayerAlert() { super("PlayerAlert", NONE); }
        @Override void enable() {
            seen.clear();
            for (Player p : mc.level.players()) seen.add(p.getUUID());
        }
        @Override void tick() {
            Set<UUID> now = new HashSet<>();
            for (Player p : mc.level.players()) {
                now.add(p.getUUID());
                if (p != mc.player && seen.add(p.getUUID()))
                    chat("§e" + p.getName().getString() + " §7appeared (" + (int) mc.player.distanceTo(p) + "m)");
            }
            seen.retainAll(now);
        }
    }

    // =====================================================================
    //  MOVEMENT
    // =====================================================================
    static class Sprint extends Mod {
        Sprint() { super("Sprint", NONE); }
        @Override void tick() {
            if (mc.options.keyUp.isDown() && !mc.player.isShiftKeyDown() && !mc.player.horizontalCollision)
                mc.player.setSprinting(true);
        }
    }

    static class AutoWalk extends Mod {
        AutoWalk() { super("AutoWalk", NONE); }
        @Override void tick() { mc.options.keyUp.setDown(true); }
        @Override void disable() { mc.options.keyUp.setDown(false); }
    }

    static class AutoJump extends Mod {
        boolean pressed;
        AutoJump() { super("AutoJump", NONE); }
        @Override void tick() {
            if (pressed) { mc.options.keyJump.setDown(false); pressed = false; }
            if (mc.options.keyUp.isDown() && mc.player.onGround() && mc.player.horizontalCollision) {
                mc.options.keyJump.setDown(true); pressed = true;
            }
        }
        @Override void disable() { mc.options.keyJump.setDown(false); }
    }

    static class Sneak extends Mod {
        Sneak() { super("Sneak", NONE); }
        @Override void tick() { mc.options.keyShift.setDown(true); }
        @Override void disable() { mc.options.keyShift.setDown(false); }
    }

    static class Fly extends Mod {
        final Num speed = add(new Num("Speed", 1.0, 0.2, 4.0, 0.2));
        Fly() { super("Fly", NONE); }
        @Override void tick() {
            if (mc.screen != null) return;
            Vec3 d = inputDir(mc.player.getYRot()).scale(speed.v * 0.5);
            double vy = ((mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0)) * speed.v * 0.5;
            mc.player.setDeltaMovement(d.x, vy, d.z);
            mc.player.fallDistance = 0;
        }
    }

    static class Speed extends Mod {
        final Num factor = add(new Num("Factor", 1.4, 1.0, 3.0, 0.2));
        Speed() { super("Speed", NONE); }
        @Override void tick() {
            if (mc.screen != null) return;
            Vec3 d = inputDir(mc.player.getYRot());
            if (d == Vec3.ZERO) return;
            double s = 0.2873 * factor.v;
            Vec3 m = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(d.x * s, m.y, d.z * s);
        }
    }

    static class Glide extends Mod {
        Glide() { super("Glide", NONE); }
        @Override void tick() {
            Vec3 m = mc.player.getDeltaMovement();
            if (!mc.player.onGround() && m.y < -0.08) mc.player.setDeltaMovement(m.x, -0.08, m.z);
        }
    }

    // =====================================================================
    //  GUI
    // =====================================================================
    static Component label(Mod m) {
        return Component.literal(m.name + ": " + (m.on ? "§aON" : "§cOFF"));
    }

    static class Menu extends Screen {
        Menu() { super(Component.literal("SimpleClient")); }

        @Override protected void init() {
            int cols = Math.max(1, Math.min(4, this.width / 130));
            int cw = Math.min(125, this.width / cols - 4);
            int x0 = (this.width - cols * (cw + 4)) / 2;
            int i = 0;
            for (Mod m : MODS) {
                int cx = x0 + (i % cols) * (cw + 4), cy = 14 + (i / cols) * 22;
                boolean gear = !m.settings.isEmpty();
                int bw = gear ? cw - 22 : cw;
                addRenderableWidget(Button.builder(label(m), b -> { m.toggle(); b.setMessage(label(m)); })
                        .bounds(cx, cy, bw, 20).build());
                if (gear) addRenderableWidget(Button.builder(Component.literal("S"), b -> mc.setScreen(new Config(m)))
                        .bounds(cx + bw + 2, cy, 20, 20).build());
                i++;
            }
            int rows = (MODS.size() + cols - 1) / cols;
            int y = 14 + rows * 22 + 6;
            addRenderableWidget(Button.builder(Component.literal("Disable all"), b -> { panic(); mc.setScreen(new Menu()); })
                    .bounds(this.width / 2 - 102, y, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                    .bounds(this.width / 2 + 2, y, 100, 20).build());
        }
    }

    static class Config extends Screen {
        final Mod m;
        Config(Mod m) { super(Component.literal(m.name)); this.m = m; }

        @Override protected void init() {
            Button head = Button.builder(Component.literal("§l" + m.name + " settings"), b -> { })
                    .bounds(this.width / 2 - 95, 4, 190, 16).build();
            head.active = false;
            addRenderableWidget(head);

            int cols = m.settings.size() > 7 ? 2 : 1, w = 190;
            int x0 = (this.width - cols * (w + 6)) / 2;
            int i = 0;
            for (Setting s : m.settings) {
                int x = x0 + (i % cols) * (w + 6), y = 26 + (i / cols) * 22;
                addRenderableWidget(Button.builder(Component.literal(s.text()), b -> {
                    s.cycle(); b.setMessage(Component.literal(s.text()));
                }).bounds(x, y, w, 20).build());
                i++;
            }
            int rows = (m.settings.size() + cols - 1) / cols;
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                    .bounds(this.width / 2 - 60, 26 + rows * 22 + 6, 120, 20).build());
        }

        @Override public void onClose() { mc.setScreen(new Menu()); }
    }
}
