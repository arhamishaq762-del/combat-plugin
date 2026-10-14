package dev.combatguard;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.util.*;
import java.util.logging.Level;

public final class CombatGuardPlugin extends JavaPlugin implements Listener, TabCompleter {
    record ItemKey(UUID player, Material material) { }
    record Pearl(UUID owner, long launched, boolean refundable) { }
    private Settings settings;
    private SafeZones safeZones;
    private Messages messages;
    private Barriers barriers;
    private final NameTags names = new NameTags();
    private final TimeLedger<UUID> tags = new TimeLedger<>(() -> System.nanoTime() / 1_000_000);
    private final TimeLedger<ItemKey> cooldowns = new TimeLedger<>(() -> System.nanoTime() / 1_000_000);
    private final Set<UUID> tagOwners = new HashSet<>();
    private final Map<UUID, Boolean> originalGlow = new HashMap<>();
    private final Set<ItemKey> overlays = new HashSet<>();
    private final Map<UUID, Pearl> pearls = new HashMap<>();
    private BukkitTask ticker;
    private int tick;
    private boolean safeZoneFailure;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            settings = loadSettings();
            initializeWorldGuard(settings);
        } catch (Exception | LinkageError e) {
            getLogger().log(Level.SEVERE, "Cannot enable CombatGuard: " + e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        messages = new Messages(this);
        barriers = new Barriers(this);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("combatguard")).setTabCompleter(this);
        ticker = Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1);
        for (Player player : Bukkit.getOnlinePlayers()) enforceWorld(player);
        getLogger().info("CombatGuard enabled on " + Bukkit.getBukkitVersion() + "; WorldGuard protection: " + settings.flag("safe-zones.enabled"));
    }
    @Override public void onDisable() {
        if (ticker != null) ticker.cancel();
        if (barriers != null) barriers.clearAll();
        Bukkit.getOnlinePlayers().forEach(this::restoreGlow);
        clearOverlays();
        names.clear();
        tags.clear(); cooldowns.clear(); tagOwners.clear(); originalGlow.clear(); pearls.clear();
        if (messages != null) messages.clear();
    }
    Settings settings() { return settings; }
    boolean tagged(Player player) { return tags.active(player.getUniqueId()); }
    boolean safe(Player player, Location at) {
        safeZoneFailure = false;
        if (!settings.flag("safe-zones.enabled")) return false;
        // Fail closed if the dependency is disabled at runtime or querying fails.
        if (safeZones == null) { safeZoneFailure = true; return true; }
        try { return safeZones.safe(player, at); }
        catch (Exception | LinkageError e) {
            safeZoneFailure = true;
            if (tick % 200 == 0) getLogger().warning("WorldGuard query failed; tagged entry blocked: " + e.getMessage());
            return true;
        }
    }
    private Settings loadSettings() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new File(getDataFolder(), "config.yml"));
        try (var resource = new java.io.InputStreamReader(Objects.requireNonNull(getResource("config.yml")), java.nio.charset.StandardCharsets.UTF_8)) {
            config.setDefaults(YamlConfiguration.loadConfiguration(resource));
        }
        config.options().copyDefaults(true);
        return new Settings(config);
    }
    private void initializeWorldGuard(Settings candidate) {
        if (candidate.flag("safe-zones.enabled")) {
            if (!getServer().getPluginManager().isPluginEnabled("WorldGuard"))
                throw new IllegalArgumentException("Install compatible WorldGuard + WorldEdit, or set safe-zones.enabled: false in config.yml");
            safeZones = new SafeZones(this);
        }
    }
    private void apply(Settings candidate) {
        initializeWorldGuard(candidate); // Validate before changing live state.
        barriers.clearAll();
        names.clear();
        Bukkit.getOnlinePlayers().forEach(this::restoreGlow);
        clearOverlays(); cooldowns.clear();
        settings = candidate;
        if (!settings.flag("combat.enabled")) {
            tags.clear(); tagOwners.clear();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (tagged(player)) applyGlow(player);
            enforceWorld(player);
        }
        updateNames();
    }
    private void tick() {
        tick++;
        for (UUID id : new HashSet<>(tagOwners)) {
            if (!tags.active(id)) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) untag(player, true);
                else tagOwners.remove(id);
            }
        }
        if (tick % 20 == 0) {
            cooldowns.prune();
            overlays.removeIf(key -> !cooldowns.active(key));
            pearls.entrySet().removeIf(entry -> System.nanoTime() / 1_000_000 - entry.getValue().launched() > 60000);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (tagged(player)) {
                    applyGlow(player);
                    if (settings.flag("combat.actionbar-timer")) messages.timer(player, tags.remainingSeconds(player.getUniqueId()));
                }
            }
            updateNames();
        }
        if (tick % settings.refreshTicks == 0) Bukkit.getOnlinePlayers().forEach(barriers::update);
    }
    private void tag(Player player) {
        if (!settings.flag("combat.enabled") || player.hasPermission("combatguard.bypass.combat")
            || settings.listed("combat.disabled-worlds", player.getWorld().getName())) return;
        boolean active = tagged(player);
        tags.start(player.getUniqueId(), settings.combatSeconds);
        tagOwners.add(player.getUniqueId());
        applyGlow(player);
        if (!active) {
            messages.notify(player, "combat-start", values(player), false);
            barriers.update(player);
            updateNames();
        }
    }
    private void untag(Player player, boolean notify) {
        tags.remove(player.getUniqueId()); tagOwners.remove(player.getUniqueId());
        restoreGlow(player); barriers.clear(player); updateNames();
        if (notify) messages.notify(player, "combat-end", values(player), false);
    }
    private void applyGlow(Player player) {
        if (!settings.flag("combat.glow")) return;
        originalGlow.putIfAbsent(player.getUniqueId(), player.isGlowing());
        player.setGlowing(true);
    }
    private void restoreGlow(Player player) {
        Boolean previous = originalGlow.remove(player.getUniqueId());
        if (previous != null) player.setGlowing(previous);
    }
    private void updateNames() {
        Set<String> taggedNames = new HashSet<>();
        if (settings != null && settings.flag("combat.red-nametag"))
            Bukkit.getOnlinePlayers().stream().filter(this::tagged).forEach(p -> taggedNames.add(p.getName()));
        names.update(taggedNames);
    }
    private Map<String, String> values(Player player) {
        return Map.of("seconds", "" + tags.remainingSeconds(player.getUniqueId()), "world", player.getWorld().getName());
    }
    private Player attacker(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (entity instanceof TNTPrimed tnt && tnt.getSource() != null) return attacker(tnt.getSource());
        if (entity instanceof Tameable pet && pet.getOwner() instanceof Player player) return player;
        return null;
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void restrictDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && player.getInventory().getItemInMainHand().getType() == Material.MACE
            && !settings.maceAllowed(player.getWorld()) && !player.hasPermission("combatguard.bypass.mace")) {
            event.setDamage(0); event.setCancelled(true);
            messages.notify(player, "mace-blocked", values(player), true);
            return;
        }
        Player source = attacker(event.getDamager());
        if (source == null) source = attacker(event.getDamageSource().getCausingEntity());
        if (source != null && event.getEntity() instanceof Player victim && !source.equals(victim)
            && settings.flag("safe-zones.prevent-pvp-inside") && (safe(source, source.getLocation()) || safe(victim, victim.getLocation()))) {
            event.setDamage(0); event.setCancelled(true);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damageTag(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0) return;
        Player source = attacker(event.getDamager());
        if (source == null) source = attacker(event.getDamageSource().getCausingEntity());
        if (source == null || source.equals(victim)
            || settings.listed("combat.disabled-worlds", source.getWorld().getName())
            || settings.listed("combat.disabled-worlds", victim.getWorld().getName())) return;
        if (settings.flag("combat.tag-attacker")) tag(source);
        if (settings.flag("combat.tag-victim")) tag(victim);
    }
    private boolean usesCooldown(Player player, Material material) {
        return settings.cooldowns.getOrDefault(material, 0.0) > 0 && !player.hasPermission("combatguard.bypass.cooldown")
            && (!settings.flag("cooldowns.only-in-combat") || tagged(player));
    }
    private boolean cooldownBlocked(Player player, Material material) {
        if (!usesCooldown(player, material)) return false;
        ItemKey key = new ItemKey(player.getUniqueId(), material);
        if (!cooldowns.active(key)) return false;
        messages.notify(player, "cooldown", Map.of("seconds", "" + cooldowns.remainingSeconds(key),
            "item", material.name().toLowerCase(Locale.ROOT).replace('_', ' ')), true);
        return true;
    }
    private void startCooldown(Player player, Material material) {
        if (!usesCooldown(player, material)) return;
        ItemKey key = new ItemKey(player.getUniqueId(), material);
        double seconds = settings.cooldowns.get(material);
        cooldowns.start(key, seconds);
        // Reserve the deadline immediately to prevent same-tick main/offhand double usage.
        // Only the native overlay is deferred until vanilla finishes consuming the item.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || !cooldowns.active(key)) return;
            // Combat-only cooldowns must not block usage outside combat through a native overlay.
            if (settings.flag("cooldowns.item-overlay") && !settings.flag("cooldowns.only-in-combat")) {
                int ticks = (int) Math.ceil(cooldowns.remainingMillis(key) / 50.0);
                player.setCooldown(material, Math.max(player.getCooldown(material), ticks));
                overlays.add(key);
            }
        });
    }
    private void clearOverlays() {
        for (ItemKey key : overlays) {
            Player player = Bukkit.getPlayer(key.player());
            if (player != null) player.setCooldown(key.material(), 0);
        }
        overlays.clear();
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (event.getClickedBlock() != null && barriers.fake(event.getPlayer(), event.getClickedBlock().getLocation())) {
            event.setCancelled(true); barriers.update(event.getPlayer()); return;
        }
        if (item == null) return;
        boolean right = event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (right && cooldownBlocked(event.getPlayer(), item.getType())) event.setUseItemInHand(Event.Result.DENY);
        if (item.getType() == Material.MACE && !settings.maceAllowed(event.getPlayer().getWorld())
            && !event.getPlayer().hasPermission("combatguard.bypass.mace")) {
            event.setUseItemInHand(Event.Result.DENY);
            messages.notify(event.getPlayer(), "mace-blocked", values(event.getPlayer()), true);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void blockLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player player
            && cooldownBlocked(player, Material.ENDER_PEARL)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player player) {
            pearls.put(pearl.getUniqueId(), new Pearl(player.getUniqueId(), System.nanoTime() / 1_000_000,
                player.getGameMode() != GameMode.CREATIVE));
            startCooldown(player, Material.ENDER_PEARL);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void pearlHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl)
            Bukkit.getScheduler().runTask(this, () -> pearls.remove(pearl.getUniqueId()));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void blockConsume(PlayerItemConsumeEvent event) {
        if (cooldownBlocked(event.getPlayer(), event.getItem().getType())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        Material material = event.getItem().getType();
        if (!settings.cooldowns.containsKey(material)) return;
        startCooldown(event.getPlayer(), material);
    }
    private boolean entryBlocked(Player player, Location from, Location to) {
        if (!tagged(player) || !settings.flag("safe-zones.prevent-entry") || player.hasPermission("combatguard.bypass.safezone")) return false;
        return !safe(player, from) && safe(player, to);
    }
    private boolean crossesBoundary(Player player, Location from, Location to) {
        if (!tagged(player) || !settings.flag("safe-zones.prevent-entry") || player.hasPermission("combatguard.bypass.safezone")) return false;
        if (!from.getWorld().equals(to.getWorld())) return entryBlocked(player, from, to);
        boolean fromSafe = safe(player, from);
        if (safeZoneFailure) return true;
        if (fromSafe) return false; // Always permit someone already inside a healthy region to leave.
        double distance = from.distance(to);
        // Unusually large move packets fail closed; real teleports have their own event.
        if (distance > 32) return true;
        int steps = Math.max(1, (int) Math.ceil(distance * 4));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            if (safe(player, new Location(to.getWorld(), from.getX() + (to.getX() - from.getX()) * t,
                from.getY() + (to.getY() - from.getY()) * t, from.getZ() + (to.getZ() - from.getZ()) * t))) return true;
        }
        return false;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || event.getTo() == null || event.getFrom().distanceSquared(event.getTo()) == 0) return;
        if (crossesBoundary(event.getPlayer(), event.getFrom(), event.getTo())) {
            Location back = event.getFrom().clone(); back.setYaw(event.getTo().getYaw()); back.setPitch(event.getTo().getPitch());
            event.setTo(back);
            messages.notify(event.getPlayer(), "safezone-blocked", values(event.getPlayer()), true);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void teleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        if (!settings.worldAllowed(event.getTo().getWorld()) && !player.hasPermission("combatguard.bypass.world")) {
            event.setCancelled(true);
            messages.notify(player, "world-blocked", Map.of("world", event.getTo().getWorld().getName()), true);
            return;
        }
        boolean pearl = event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
        if (!tagged(player) || player.hasPermission("combatguard.bypass.safezone")) return;
        if (safe(player, event.getTo()) && ((pearl) || (settings.flag("safe-zones.block-all-teleports") && settings.flag("safe-zones.prevent-entry")))) {
            event.setCancelled(true);
            messages.notify(player, "safezone-blocked", values(player), true);
            if (pearl && settings.flag("safe-zones.refund-ender-pearl")) refundPearl(player, event.getTo());
        }
    }
    private void refundPearl(Player player, Location destination) {
        // Match a real tracked projectile; no invented refunds for command/plugin teleports.
        UUID match = null;
        double closest = Double.MAX_VALUE;
        for (var entry : pearls.entrySet()) {
            if (!entry.getValue().owner().equals(player.getUniqueId())) continue;
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity == null || !entity.getWorld().equals(destination.getWorld())) continue;
            double distance = entity.getLocation().distanceSquared(destination);
            if (distance < closest) { closest = distance; match = entry.getKey(); }
        }
        if (match == null || closest > 64) return;
        Pearl tracked = pearls.remove(match); // Atomic one-refund-per-projectile guard.
        if (!tracked.refundable()) return; // Creative players never consumed a pearl.
        Entity pearl = Bukkit.getEntity(match);
        if (pearl != null) pearl.remove();
        // Vanilla consumption has finished before a pearl lands. Overflow is dropped at the player's safe origin.
        for (ItemStack overflow : player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL)).values())
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        if (settings.flag("safe-zones.reset-pearl-cooldown-on-refund")) {
            ItemKey key = new ItemKey(player.getUniqueId(), Material.ENDER_PEARL);
            cooldowns.remove(key);
            if (overlays.remove(key)) player.setCooldown(Material.ENDER_PEARL, 0);
        }
        messages.notify(player, "pearl-refunded", values(player), false);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void portal(PlayerPortalEvent event) { teleport(event); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void vehicle(VehicleMoveEvent event) {
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player && crossesBoundary(player, event.getFrom(), event.getTo())) {
                player.leaveVehicle();
                player.teleport(event.getFrom());
                event.getVehicle().setVelocity(new org.bukkit.util.Vector());
                messages.notify(player, "safezone-blocked", values(player), true);
            }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (barriers.fake(event.getPlayer(), event.getBlock().getLocation())) { event.setCancelled(true); barriers.update(event.getPlayer()); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent event) {
        if (barriers.fake(event.getPlayer(), event.getBlockPlaced().getLocation())) { event.setCancelled(true); barriers.update(event.getPlayer()); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!tagged(player)) return;
        String command = event.getMessage().substring(1).trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (command.contains(":")) command = command.substring(command.lastIndexOf(':') + 1);
        final String normalized = command;
        if (settings.config.getStringList("combat.blocked-commands").stream()
            .map(s -> s.replaceFirst("^/", "").toLowerCase(Locale.ROOT)).anyMatch(normalized::equals)) {
            event.setCancelled(true); messages.notify(player, "command-blocked", values(player), true);
        }
    }
    private Location fallback() {
        String name = settings.config.getString("worlds.fallback-world", "");
        World configured = name.isEmpty() ? null : Bukkit.getWorld(name);
        if (configured != null && settings.worldAllowed(configured)) return configured.getSpawnLocation();
        return Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == World.Environment.NORMAL && settings.worldAllowed(w))
            .findFirst().map(World::getSpawnLocation).orElse(null);
    }
    private void enforceWorld(Player player) {
        if (settings.worldAllowed(player.getWorld()) || player.hasPermission("combatguard.bypass.world")) return;
        String blockedWorld = player.getWorld().getName();
        Location target = fallback();
        // Untag only for mandatory evacuation, not for routine teleports.
        untag(player, false);
        if (target == null || !player.teleport(target)) {
            player.kickPlayer(Messages.color("&cThis world is disabled and no allowed fallback world is available."));
        } else messages.notify(player, "world-blocked", Map.of("world", blockedWorld), true);
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            if (!player.isOnline()) return;
            enforceWorld(player);
            if (tagged(player)) applyGlow(player);
            updateNames();
        });
    }
    @EventHandler public void changeWorld(PlayerChangedWorldEvent event) {
        barriers.clear(event.getPlayer());
        enforceWorld(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent event) {
        if (!settings.worldAllowed(event.getRespawnLocation().getWorld()) && !event.getPlayer().hasPermission("combatguard.bypass.world")) {
            Location fallback = fallback();
            if (fallback != null) event.setRespawnLocation(fallback);
            Bukkit.getScheduler().runTask(this, () -> enforceWorld(event.getPlayer()));
        }
    }
    @EventHandler public void death(PlayerDeathEvent event) {
        if (settings.flag("combat.clear-on-death")) untag(event.getEntity(), false);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (tagged(player) && settings.flag("combat.kill-on-logout") && !player.isDead()) player.setHealth(0);
        restoreGlow(player); barriers.clear(player); messages.forget(player.getUniqueId());
        // Keep a non-punished combat deadline through a quick reconnect.
        pearls.entrySet().removeIf(e -> e.getValue().owner().equals(player.getUniqueId()));
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("status")) {
            if (sender instanceof Player player) sender.sendMessage(Messages.color("&eCombat: &f" + tags.remainingSeconds(player.getUniqueId()) + "s"));
            else sender.sendMessage("CombatGuard active; tagged players: " + tagOwners.size());
            return true;
        }
        if (!sender.hasPermission("combatguard.admin")) { sender.sendMessage(Messages.color("&cYou do not have permission.")); return true; }
        try {
            switch (sub) {
                case "reload" -> { apply(loadSettings()); sender.sendMessage(Messages.color("&aCombatGuard configuration reloaded.")); }
                case "set" -> {
                    if (args.length < 3) throw new IllegalArgumentException("Usage: /cg set <config.path> <value>");
                    Settings loaded = loadSettings();
                    Object old = loaded.config.get(args[1]);
                    if (old == null || loaded.config.isConfigurationSection(args[1])) throw new IllegalArgumentException("Unknown setting: " + args[1]);
                    String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    Object replacement;
                    if (old instanceof Boolean) {
                        if (!Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Use true or false");
                        replacement = Boolean.parseBoolean(value);
                    } else if (old instanceof Number) replacement = Double.parseDouble(value);
                    else if (old instanceof List<?>) replacement = value.equals("[]") ? List.of() : Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
                    else replacement = value;
                    loaded.config.set(args[1], replacement);
                    Settings candidate = new Settings(loaded.config);
                    initializeWorldGuard(candidate);
                    // Do not change live settings unless validation AND saving succeed.
                    candidate.config.save(new File(getDataFolder(), "config.yml"));
                    apply(candidate);
                    sender.sendMessage(Messages.color("&aUpdated " + args[1] + " = " + value));
                }
                case "tag", "untag" -> {
                    if (args.length != 2) throw new IllegalArgumentException("Usage: /cg " + sub + " <online player>");
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) throw new IllegalArgumentException("Player must be online");
                    if (sub.equals("tag")) tag(target); else untag(target, true);
                    sender.sendMessage(Messages.color("&a" + target.getName() + " combat time: " + tags.remainingSeconds(target.getUniqueId()) + "s"));
                }
                default -> sender.sendMessage("/cg status | reload | set <path> <value> | tag <player> | untag <player>");
            }
        } catch (Exception | LinkageError e) { sender.sendMessage(Messages.color("&cConfiguration/action failed: " + e.getMessage())); }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) options.addAll(sender.hasPermission("combatguard.admin") ? List.of("status", "reload", "set", "tag", "untag") : List.of("status"));
        else if (sender.hasPermission("combatguard.admin") && args.length == 2) {
            if (args[0].equalsIgnoreCase("set")) settings.config.getKeys(true).stream().filter(k -> !settings.config.isConfigurationSection(k)).forEach(options::add);
            if (Set.of("tag", "untag").contains(args[0].toLowerCase(Locale.ROOT))) Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (sender.hasPermission("combatguard.admin") && args.length == 3 && args[0].equalsIgnoreCase("set")) {
            Object old = settings.config.get(args[1]);
            if (old instanceof Boolean) options.addAll(List.of("true", "false"));
            if (args[1].equals("safe-zones.barrier.mode")) options.addAll(List.of("AIR", "BLOCK"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
