package dev.combatguard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.util.*;

final class Barriers {
    record Position(UUID world, int x, int y, int z) {
        static Position of(Location l) { return new Position(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ()); }
        Location location() { World w = Bukkit.getWorld(world); return w == null ? null : new Location(w, x, y, z); }
    }
    private final CombatGuardPlugin plugin;
    private final Map<UUID, Set<Position>> shown = new HashMap<>();
    Barriers(CombatGuardPlugin plugin) { this.plugin = plugin; }
    void update(Player player) {
        Settings s = plugin.settings();
        if (!plugin.tagged(player) || !s.flag("safe-zones.enabled") || !s.flag("safe-zones.prevent-entry")
            || !s.barrierMode.equals("BLOCK") || player.hasPermission("combatguard.bypass.safezone")) {
            clear(player); return;
        }
        Set<Position> next = new HashSet<>();
        Location feet = player.getLocation();
        World world = player.getWorld();
        for (int x = feet.getBlockX() - s.radius; x <= feet.getBlockX() + s.radius; x++) {
            for (int z = feet.getBlockZ() - s.radius; z <= feet.getBlockZ() + s.radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = Math.max(world.getMinHeight(), feet.getBlockY() - 1);
                     y <= Math.min(world.getMaxHeight() - 1, feet.getBlockY() + s.height); y++) {
                    Location at = new Location(world, x + 0.5, y, z + 0.5);
                    if (!s.flag("safe-zones.barrier.replace-solid-blocks") && !at.getBlock().getType().isAir()) continue;
                    if (!plugin.safe(player, at)) continue;
                    // Only show the boundary, not the entire interior of a safe region.
                    if (plugin.safe(player, at.clone().add(1, 0, 0)) && plugin.safe(player, at.clone().add(-1, 0, 0))
                        && plugin.safe(player, at.clone().add(0, 0, 1)) && plugin.safe(player, at.clone().add(0, 0, -1))) continue;
                    next.add(Position.of(at));
                }
            }
        }
        Set<Position> previous = shown.getOrDefault(player.getUniqueId(), Set.of());
        for (Position position : previous) if (!next.contains(position)) restore(player, position);
        for (Position position : next) player.sendBlockChange(position.location(), s.barrierBlock);
        shown.put(player.getUniqueId(), next);
    }
    boolean fake(Player player, Location location) { return shown.getOrDefault(player.getUniqueId(), Set.of()).contains(Position.of(location)); }
    private void restore(Player player, Position position) {
        Location l = position.location();
        if (l != null && l.getWorld().equals(player.getWorld()) && l.getWorld().isChunkLoaded(position.x >> 4, position.z >> 4))
            player.sendBlockChange(l, l.getBlock().getBlockData());
    }
    void clear(Player player) {
        Set<Position> positions = shown.remove(player.getUniqueId());
        if (positions != null) positions.forEach(p -> restore(player, p));
    }
    void clearAll() { Bukkit.getOnlinePlayers().forEach(this::clear); shown.clear(); }
}
