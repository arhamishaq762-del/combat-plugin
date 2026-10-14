package dev.combatguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Loaded only when a compatible WorldGuard and its WorldEdit dependency are available. */
final class SafeZones {
    private final CombatGuardPlugin plugin;
    private final RegionQuery query;
    SafeZones(CombatGuardPlugin plugin) {
        this.plugin = plugin;
        query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
    }
    boolean safe(Player player, Location location) {
        if (!plugin.settings().flag("safe-zones.enabled")) return false;
        if (!plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard")) throw new IllegalStateException("WorldGuard is disabled");
        var adapted = BukkitAdapter.adapt(location);
        var regions = query.getApplicableRegions(adapted);
        // A failed region database must never silently allow tagged entry.
        if (regions.isVirtual()) throw new IllegalStateException("WorldGuard region data is unavailable");
        var config = plugin.settings();
        for (var region : regions.getRegions()) {
            if (config.listed("safe-zones.regions.*", region.getId())
                || config.listed("safe-zones.regions." + location.getWorld().getName(), region.getId())) return true;
        }
        return config.flag("safe-zones.use-pvp-deny") && query.queryState(adapted,
            WorldGuardPlugin.inst().wrapPlayer(player), Flags.PVP) == StateFlag.State.DENY;
    }
}
