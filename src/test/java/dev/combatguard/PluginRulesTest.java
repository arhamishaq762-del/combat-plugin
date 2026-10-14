package dev.combatguard;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PluginRulesTest {
    Server server;
    World world;
    Player player;
    PlayerInventory inventory;
    YamlConfiguration config;
    Settings settings;
    CombatGuardPlugin plugin;
    TimeLedger<UUID> tags;
    TimeLedger<CombatGuardPlugin.ItemKey> cooldowns;
    Map<UUID, CombatGuardPlugin.Pearl> pearls;
    Messages messages;
    BukkitScheduler scheduler;

    @BeforeEach void setup() throws Exception {
        server = mock(Server.class);
        Field singleton = Bukkit.class.getDeclaredField("server"); singleton.setAccessible(true); singleton.set(null, server);
        when(server.getRegistry(any())).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(0);
            Registry<?> registry = mock(Registry.class);
            when(registry.get(any())).thenAnswer(lookup -> {
                Object entry = mock(type);
                if (entry instanceof org.bukkit.block.BlockType block) {
                    NamespacedKey key = lookup.getArgument(0);
                    when(block.isAir()).thenReturn(Set.of("air", "cave_air", "void_air").contains(key.getKey()));
                }
                return entry;
            });
            return registry;
        });
        when(server.createBlockData(any(Material.class))).thenAnswer(inv -> {
            BlockData block = mock(BlockData.class); when(block.getMaterial()).thenReturn(inv.getArgument(0)); return block;
        });
        scheduler = mock(BukkitScheduler.class); when(server.getScheduler()).thenReturn(scheduler);
        world = mock(World.class);
        when(world.getName()).thenReturn("world"); when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        player = mock(Player.class); inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getName()).thenReturn("TestPlayer");
        when(player.getWorld()).thenReturn(world); when(player.getInventory()).thenReturn(inventory);
        when(player.getServer()).thenReturn(server);
        when(player.getLocation()).thenReturn(new Location(world, -2, 64, 0));
        when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        config = YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/config.yml"))));
        settings = new Settings(config);
        plugin = mock(CombatGuardPlugin.class, CALLS_REAL_METHODS);
        doReturn(server).when(plugin).getServer();
        tags = new TimeLedger<>(() -> System.nanoTime() / 1_000_000);
        cooldowns = new TimeLedger<>(() -> System.nanoTime() / 1_000_000);
        pearls = new HashMap<>(); messages = mock(Messages.class);
        set("settings", settings); set("tags", tags); set("cooldowns", cooldowns);
        set("tagOwners", new HashSet<UUID>()); set("originalGlow", new HashMap<UUID, Boolean>());
        set("overlays", new HashSet<CombatGuardPlugin.ItemKey>()); set("pearls", pearls);
        set("names", mock(NameTags.class)); set("barriers", mock(Barriers.class)); set("messages", messages);
        doAnswer(inv -> { Location l = inv.getArgument(1); return l.getX() >= 0 && l.getX() <= 1; }).when(plugin).safe(any(), any());
    }
    @AfterEach void cleanup() throws Exception {
        Field singleton = Bukkit.class.getDeclaredField("server"); singleton.setAccessible(true); singleton.set(null, null);
    }
    private void set(String name, Object value) throws Exception {
        Field field = CombatGuardPlugin.class.getDeclaredField(name); field.setAccessible(true); field.set(plugin, value);
    }
    private void tagged() { tags.start(player.getUniqueId(), 30); }
    private PlayerTeleportEvent teleport(double x, PlayerTeleportEvent.TeleportCause cause) {
        return new PlayerTeleportEvent(player, player.getLocation(), new Location(world, x, 64, 0), cause);
    }
    @Test void defaultsProvideAllThreeIndependentCooldowns() {
        assertEquals(15.0, settings.cooldowns.get(Material.ENDER_PEARL));
        assertEquals(10.0, settings.cooldowns.get(Material.GOLDEN_APPLE));
        assertEquals(30.0, settings.cooldowns.get(Material.ENCHANTED_GOLDEN_APPLE));
    }
    @ParameterizedTest @ValueSource(strings = {"combat.glow", "combat.red-nametag", "safe-zones.prevent-entry", "mace.enabled", "worlds.nether-enabled"})
    void malformedBooleanIsRejected(String path) {
        config.set(path, "not-a-boolean"); assertThrows(IllegalArgumentException.class, () -> new Settings(config));
    }
    @Test void negativeCooldownRejected() {
        config.set("cooldowns.ender-pearl", -1); assertThrows(IllegalArgumentException.class, () -> new Settings(config));
    }
    @Test void nonFiniteCooldownRejected() {
        config.set("cooldowns.ender-pearl", Double.NaN); assertThrows(IllegalArgumentException.class, () -> new Settings(config));
    }
    @Test void invalidBarrierModeRejected() {
        config.set("safe-zones.barrier.mode", "FAKE"); assertThrows(IllegalArgumentException.class, () -> new Settings(config));
    }
    @Test void airIsNotValidVisibleBarrierMaterial() {
        config.set("safe-zones.barrier.block", "AIR"); assertThrows(IllegalArgumentException.class, () -> new Settings(config));
    }
    @Test void fractionalBarrierRadiusRejected() {
        config.set("safe-zones.barrier.radius", 2.5); assertThrows(IllegalArgumentException.class, () -> new Settings(config));
    }
    @Test void malformedRegionListRejected() {
        config.set("safe-zones.regions.*", "spawn"); assertThrows(IllegalArgumentException.class, () -> new Settings(config));
    }
    @Test void netherToggleAppliesToAnyNetherWorldName() {
        when(world.getEnvironment()).thenReturn(World.Environment.NETHER);
        config.set("worlds.nether-enabled", false); assertFalse(settings.worldAllowed(world));
        config.set("worlds.nether-enabled", true); assertTrue(settings.worldAllowed(world));
    }
    @Test void endToggleAppliesToAnyEndWorldName() {
        when(world.getEnvironment()).thenReturn(World.Environment.THE_END);
        config.set("worlds.end-enabled", false); assertFalse(settings.worldAllowed(world));
    }
    @Test void namedWorldRulesAreCaseInsensitive() {
        config.set("worlds.disabled-worlds", List.of("WORLD")); assertFalse(settings.worldAllowed(world));
    }
    @Test void globalMaceToggleDisablesAllWorlds() {
        config.set("mace.enabled", false); assertFalse(settings.maceAllowed(world));
    }
    @Test void maceWorldListDoesNotDisableWholeWorld() {
        config.set("mace.disabled-worlds", List.of("world"));
        assertFalse(settings.maceAllowed(world)); assertTrue(settings.worldAllowed(world));
    }
    @Test void maceAttackDealsNoDamageAndWarns() {
        config.set("mace.enabled", false);
        ItemStack mace = mock(ItemStack.class); when(mace.getType()).thenReturn(Material.MACE);
        when(inventory.getItemInMainHand()).thenReturn(mace);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class); when(event.getDamager()).thenReturn(player);
        plugin.restrictDamage(event);
        verify(event).setCancelled(true); verify(event).setDamage(0);
        verify(messages).notify(eq(player), eq("mace-blocked"), anyMap(), eq(true));
    }
    @Test void taggedPearlIntoSafeZoneIsCancelled() {
        tagged(); var event = teleport(0.5, PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        plugin.teleport(event); assertTrue(event.isCancelled());
    }
    @Test void untaggedPearlMayEnterSafeZone() {
        var event = teleport(0.5, PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        plugin.teleport(event); assertFalse(event.isCancelled());
    }
    @Test void taggedTeleportOutsideSafeZoneAllowed() {
        tagged(); var event = teleport(-3, PlayerTeleportEvent.TeleportCause.COMMAND);
        plugin.teleport(event); assertFalse(event.isCancelled());
    }
    @Test void ordinaryTeleportIntoSafeZoneBlocked() {
        tagged(); var event = teleport(0.5, PlayerTeleportEvent.TeleportCause.COMMAND);
        plugin.teleport(event); assertTrue(event.isCancelled());
    }
    @Test void safeZoneBypassAllowsEntry() {
        tagged(); when(player.hasPermission("combatguard.bypass.safezone")).thenReturn(true);
        var event = teleport(0.5, PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        plugin.teleport(event); assertFalse(event.isCancelled());
    }
    @Test void commandTeleportToDisabledWorldBlockedEvenUntagged() {
        config.set("worlds.disabled-worlds", List.of("world"));
        var event = teleport(-3, PlayerTeleportEvent.TeleportCause.COMMAND);
        plugin.teleport(event); assertTrue(event.isCancelled());
    }
    @Test void worldBypassAllowsDisabledWorld() {
        config.set("worlds.disabled-worlds", List.of("world")); when(player.hasPermission("combatguard.bypass.world")).thenReturn(true);
        var event = teleport(-3, PlayerTeleportEvent.TeleportCause.COMMAND);
        plugin.teleport(event); assertFalse(event.isCancelled());
    }
    @Test void movementIsBlockedOnlyForTaggedPlayer() {
        var event = new PlayerMoveEvent(player, player.getLocation(), new Location(world, 0.5, 64, 0));
        plugin.move(event); assertEquals(0.5, event.getTo().getX());
        tagged(); plugin.move(event); assertEquals(-2, event.getTo().getX());
    }
    @Test void crossingThinZoneCannotTunnelThrough() {
        tagged(); var event = new PlayerMoveEvent(player, player.getLocation(), new Location(world, 2, 64, 0));
        plugin.move(event); assertEquals(-2, event.getTo().getX());
    }
    @Test void taggedPlayerAlreadyInsideCanLeave() {
        tagged(); var event = new PlayerMoveEvent(player, new Location(world, 0.5, 64, 0), new Location(world, -2, 64, 0));
        plugin.move(event); assertEquals(-2, event.getTo().getX());
    }
    @Test void namespaceDoesNotBypassCommandBlock() {
        tagged(); var event = new PlayerCommandPreprocessEvent(player, "/essentials:home base");
        plugin.command(event); assertTrue(event.isCancelled());
    }
    @Test void untaggedCommandAllowed() {
        var event = new PlayerCommandPreprocessEvent(player, "/home base");
        plugin.command(event); assertFalse(event.isCancelled());
    }
    @Test void pearlCooldownReservedInLaunchTick() {
        EnderPearl pearl = mock(EnderPearl.class); when(pearl.getShooter()).thenReturn(player); when(pearl.getUniqueId()).thenReturn(UUID.randomUUID());
        plugin.launch(new ProjectileLaunchEvent(pearl));
        assertTrue(cooldowns.active(new CombatGuardPlugin.ItemKey(player.getUniqueId(), Material.ENDER_PEARL)));
        var next = new ProjectileLaunchEvent(pearl); plugin.blockLaunch(next); assertTrue(next.isCancelled());
    }
    @Test void combatOnlyCooldownDoesNotApplyOutsideCombat() {
        config.set("cooldowns.only-in-combat", true);
        EnderPearl pearl = mock(EnderPearl.class); when(pearl.getShooter()).thenReturn(player); when(pearl.getUniqueId()).thenReturn(UUID.randomUUID());
        plugin.launch(new ProjectileLaunchEvent(pearl));
        assertFalse(cooldowns.active(new CombatGuardPlugin.ItemKey(player.getUniqueId(), Material.ENDER_PEARL)));
    }
    @Test void combatOnlyCooldownAppliesWhenTagged() {
        config.set("cooldowns.only-in-combat", true); tagged();
        EnderPearl pearl = mock(EnderPearl.class); when(pearl.getShooter()).thenReturn(player); when(pearl.getUniqueId()).thenReturn(UUID.randomUUID());
        plugin.launch(new ProjectileLaunchEvent(pearl));
        assertTrue(cooldowns.active(new CombatGuardPlugin.ItemKey(player.getUniqueId(), Material.ENDER_PEARL)));
    }
    @Test void cooldownBypassSkipsReservation() {
        when(player.hasPermission("combatguard.bypass.cooldown")).thenReturn(true);
        EnderPearl pearl = mock(EnderPearl.class); when(pearl.getShooter()).thenReturn(player); when(pearl.getUniqueId()).thenReturn(UUID.randomUUID());
        plugin.launch(new ProjectileLaunchEvent(pearl));
        assertFalse(cooldowns.active(new CombatGuardPlugin.ItemKey(player.getUniqueId(), Material.ENDER_PEARL)));
    }
    @Test void appleAndEnchantedAppleStayIndependent() {
        ItemStack apple = mock(ItemStack.class); when(apple.getType()).thenReturn(Material.GOLDEN_APPLE);
        when(apple.clone()).thenReturn(apple);
        plugin.consume(new PlayerItemConsumeEvent(player, apple));
        assertTrue(cooldowns.active(new CombatGuardPlugin.ItemKey(player.getUniqueId(), Material.GOLDEN_APPLE)));
        assertFalse(cooldowns.active(new CombatGuardPlugin.ItemKey(player.getUniqueId(), Material.ENCHANTED_GOLDEN_APPLE)));
        var next = new PlayerItemConsumeEvent(player, apple); plugin.blockConsume(next); assertTrue(next.isCancelled());
    }
    @Test void fakePearlTeleportNeverInventsRefund() {
        tagged(); plugin.teleport(teleport(0.5, PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
        verify(inventory, never()).addItem(any(ItemStack.class));
    }
    @Test void realPearlRefundHappensAtMostOnce() {
        tagged(); UUID id = UUID.randomUUID(); EnderPearl pearl = mock(EnderPearl.class);
        when(pearl.getWorld()).thenReturn(world); when(pearl.getLocation()).thenReturn(new Location(world, 0.5, 64, 0));
        when(server.getEntity(id)).thenReturn(pearl); when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        pearls.put(id, new CombatGuardPlugin.Pearl(player.getUniqueId(), 0, true));
        plugin.teleport(teleport(0.5, PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
        plugin.teleport(teleport(0.5, PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
        verify(inventory, times(1)).addItem(any(ItemStack.class)); verify(pearl).remove(); assertTrue(pearls.isEmpty());
    }
    @Test void creativePearlNeverDuplicatesAnItem() {
        tagged(); UUID id = UUID.randomUUID(); EnderPearl pearl = mock(EnderPearl.class);
        when(pearl.getWorld()).thenReturn(world); when(pearl.getLocation()).thenReturn(new Location(world, 0.5, 64, 0));
        when(server.getEntity(id)).thenReturn(pearl); pearls.put(id, new CombatGuardPlugin.Pearl(player.getUniqueId(), 0, false));
        plugin.teleport(teleport(0.5, PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
        verify(inventory, never()).addItem(any(ItemStack.class)); assertTrue(pearls.isEmpty());
    }
    @Test void glowRestoresPreCombatState() {
        when(player.hasPermission("combatguard.admin")).thenReturn(true);
        plugin.onCommand(player, null, "cg", new String[]{"tag", player.getName()});
        // The command requires the server's exact-player lookup.
        when(server.getPlayerExact(player.getName())).thenReturn(player);
        plugin.onCommand(player, null, "cg", new String[]{"tag", player.getName()});
        assertTrue(plugin.tagged(player)); verify(player).setGlowing(true);
        plugin.onCommand(player, null, "cg", new String[]{"untag", player.getName()});
        assertFalse(plugin.tagged(player)); verify(player).setGlowing(false);
    }
    @Test void preExistingGlowIsNotRemovedAtCombatEnd() {
        when(player.isGlowing()).thenReturn(true);
        when(player.hasPermission("combatguard.admin")).thenReturn(true);
        when(server.getPlayerExact(player.getName())).thenReturn(player);
        plugin.onCommand(player, null, "cg", new String[]{"tag", player.getName()});
        plugin.onCommand(player, null, "cg", new String[]{"untag", player.getName()});
        verify(player, never()).setGlowing(false);
    }
    @Test void disabledGlowNeverChangesEntityGlow() {
        config.set("combat.glow", false);
        when(player.hasPermission("combatguard.admin")).thenReturn(true);
        when(server.getPlayerExact(player.getName())).thenReturn(player);
        plugin.onCommand(player, null, "cg", new String[]{"tag", player.getName()});
        assertTrue(plugin.tagged(player)); verify(player, never()).setGlowing(anyBoolean());
    }
    @Test void untaggedPlayersNeverReceiveFakeBlocks() {
        new Barriers(plugin).update(player);
        verify(player, never()).sendBlockChange(any(Location.class), any(BlockData.class));
    }
    @Test void airBarrierUsesNoFakeBlocksButStillBlocksMovement() throws Exception {
        config.set("safe-zones.barrier.mode", "AIR"); set("settings", new Settings(config));
        tagged(); new Barriers(plugin).update(player);
        verify(player, never()).sendBlockChange(any(Location.class), any(BlockData.class));
        var move = new PlayerMoveEvent(player, player.getLocation(), new Location(world, 0.5, 64, 0));
        plugin.move(move); assertEquals(-2, move.getTo().getX());
    }
    @Test void blockBarrierIsPlayerOnlyAndRestoresRealBlocks() {
        tagged(); when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        org.bukkit.block.Block air = mock(org.bukkit.block.Block.class);
        BlockData real = mock(BlockData.class);
        when(air.getType()).thenReturn(Material.AIR); when(air.getBlockData()).thenReturn(real);
        when(world.getBlockAt(any(Location.class))).thenReturn(air);
        when(server.getWorld(world.getUID())).thenReturn(world);
        Barriers barriers = new Barriers(plugin); barriers.update(player);
        verify(player, atLeastOnce()).sendBlockChange(any(Location.class), eq(settings.barrierBlock));
        assertTrue(barriers.fake(player, new Location(world, 0, 64, 0)));
        verify(air, never()).setBlockData(any(BlockData.class));
        barriers.clear(player);
        verify(player, atLeastOnce()).sendBlockChange(any(Location.class), eq(real));
        assertFalse(barriers.fake(player, new Location(world, 0, 64, 0)));
    }

    @Test void redNametagRestoresThePreviousScoreboardTeam() {
        org.bukkit.scoreboard.Scoreboard board = mock(org.bukkit.scoreboard.Scoreboard.class);
        org.bukkit.scoreboard.ScoreboardManager manager = mock(org.bukkit.scoreboard.ScoreboardManager.class);
        org.bukkit.scoreboard.Team staff = mock(org.bukkit.scoreboard.Team.class);
        org.bukkit.scoreboard.Team red = mock(org.bukkit.scoreboard.Team.class);
        java.util.concurrent.atomic.AtomicReference<org.bukkit.scoreboard.Team> current = new java.util.concurrent.atomic.AtomicReference<>(staff);
        java.util.concurrent.atomic.AtomicReference<org.bukkit.scoreboard.Team> registered = new java.util.concurrent.atomic.AtomicReference<>();
        when(staff.getName()).thenReturn("staff"); when(red.getName()).thenReturn("combatguard_red");
        when(server.getScoreboardManager()).thenReturn(manager); when(manager.getMainScoreboard()).thenReturn(board);
        when(player.getScoreboard()).thenReturn(board);
        when(board.getEntryTeam(player.getName())).thenAnswer(inv -> current.get());
        when(board.getTeam("combatguard_red")).thenAnswer(inv -> registered.get());
        when(board.getTeam("staff")).thenReturn(staff);
        when(board.registerNewTeam("combatguard_red")).thenAnswer(inv -> { registered.set(red); return red; });
        doAnswer(inv -> { current.set(red); return true; }).when(red).addEntry("TestPlayer");
        doAnswer(inv -> { current.set(null); return true; }).when(red).removeEntry("TestPlayer");
        doAnswer(inv -> { current.set(staff); return true; }).when(staff).addEntry("TestPlayer");
        NameTags names = new NameTags(); names.update(Set.of(player.getName()));
        assertSame(red, current.get()); verify(red).setColor(ChatColor.RED);
        names.update(Set.of()); assertSame(staff, current.get());
        names.clear(); verify(red).unregister();
    }

}
