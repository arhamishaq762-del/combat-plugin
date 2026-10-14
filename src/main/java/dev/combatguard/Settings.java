package dev.combatguard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Settings {
    final FileConfiguration config;
    final double combatSeconds;
    final Map<Material, Double> cooldowns = new EnumMap<>(Material.class);
    final BlockData barrierBlock;
    final String barrierMode;
    final int radius, height, refreshTicks;
    Settings(FileConfiguration config) {
        this.config = config;
        validateTypes();
        combatSeconds = number("combat.duration-seconds", 0.1, 86400);
        cooldowns.put(Material.ENDER_PEARL, number("cooldowns.ender-pearl", 0, 86400));
        cooldowns.put(Material.GOLDEN_APPLE, number("cooldowns.golden-apple", 0, 86400));
        cooldowns.put(Material.ENCHANTED_GOLDEN_APPLE, number("cooldowns.enchanted-golden-apple", 0, 86400));
        barrierMode = config.getString("safe-zones.barrier.mode", "AIR").toUpperCase(Locale.ROOT);
        if (!Set.of("AIR", "BLOCK").contains(barrierMode)) throw new IllegalArgumentException("Barrier mode must be AIR or BLOCK");
        String block = config.getString("safe-zones.barrier.block", "RED_STAINED_GLASS");
        Material material = Material.matchMaterial(block);
        barrierBlock = material == null ? Bukkit.createBlockData(block) : material.createBlockData();
        if (barrierBlock.getMaterial().isAir() || !barrierBlock.getMaterial().isBlock())
            throw new IllegalArgumentException("Barrier block must be a non-air block");
        radius = integer("safe-zones.barrier.radius", 1, 8);
        height = integer("safe-zones.barrier.height", 1, 6);
        refreshTicks = integer("safe-zones.barrier.refresh-ticks", 1, 200);
        number("messages.notification-throttle-ms", 0, 60000);
        integer("messages.title-fade-in-ticks", 0, 1200);
        integer("messages.title-stay-ticks", 0, 1200);
        integer("messages.title-fade-out-ticks", 0, 1200);
    }
    private void validateTypes() {
        var stream = Objects.requireNonNull(Settings.class.getResourceAsStream("/config.yml"));
        var defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        for (String path : defaults.getKeys(true)) {
            Object expected = defaults.get(path), actual = config.get(path);
            if (expected instanceof Boolean && !(actual instanceof Boolean))
                throw new IllegalArgumentException(path + " must be true or false");
            if (expected instanceof String && !(actual instanceof String))
                throw new IllegalArgumentException(path + " must be text (quote colors in YAML)");
            if (expected instanceof List<?> && (!(actual instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String))))
                throw new IllegalArgumentException(path + " must be a list of names");
        }
        var regions = config.getConfigurationSection("safe-zones.regions");
        if (regions == null) throw new IllegalArgumentException("safe-zones.regions must map world names to lists of region IDs");
        for (String world : regions.getKeys(false)) {
            Object value = regions.get(world);
            if (!(value instanceof List<?> ids) || ids.stream().anyMatch(id -> !(id instanceof String)))
                throw new IllegalArgumentException("safe-zones.regions." + world + " must be a list of region IDs");
        }
    }
    boolean flag(String path) { return config.getBoolean(path); }
    boolean listed(String path, String value) { return config.getStringList(path).stream().anyMatch(value::equalsIgnoreCase); }
    boolean worldAllowed(World world) {
        return !listed("worlds.disabled-worlds", world.getName())
            && (world.getEnvironment() != World.Environment.NETHER || flag("worlds.nether-enabled"))
            && (world.getEnvironment() != World.Environment.THE_END || flag("worlds.end-enabled"));
    }
    boolean maceAllowed(World world) {
        return flag("mace.enabled") && !listed("mace.disabled-worlds", world.getName())
            && !(world.getEnvironment() == World.Environment.NETHER && flag("mace.disabled-in-nether"))
            && !(world.getEnvironment() == World.Environment.THE_END && flag("mace.disabled-in-end"));
    }
    private double number(String path, double min, double max) {
        Object raw = config.get(path);
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max)
            throw new IllegalArgumentException(path + " must be a number between " + min + " and " + max);
        return n.doubleValue();
    }
    private int integer(String path, int min, int max) {
        double value = number(path, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException(path + " must be an integer");
        return (int) value;
    }
}
