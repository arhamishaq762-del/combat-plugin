package com.marketrealms;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.hanging.*;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import java.util.*;

/** Conservative marketplace policy: redstone transport/destructive mechanics are disabled. */
final class Protection implements Listener {
    private final MarketRealmsPlugin plugin;
    Protection(MarketRealmsPlugin plugin){this.plugin=plugin;}
    boolean protectedAt(Location l){return plugin.worlds.protectedWorld(l.getWorld());}
    private boolean allowed(Player p,Location l){if(plugin.fatal||plugin.journal.locked(p.getUniqueId())||p.getWorld()==plugin.worlds.preview)return false;if(plugin.config.file("config").getBoolean("protection.admin-build-bypass")&&p.hasPermission("marketrealms.admin")&&!plugin.archives.locked(Optional.ofNullable(plugin.rentals.at(l)).map(Domain.Plot::id).orElse("")))return true;return plugin.rentals.canBuild(p,l)&&(!plugin.config.file("config").getBoolean("protection.require-survival")||p.getGameMode()==GameMode.SURVIVAL);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void place(BlockPlaceEvent e){if(protectedAt(e.getBlock().getLocation())&&(!allowed(e.getPlayer(),e.getBlock().getLocation())||!Blocks.supported(e.getBlock().getState())||e.getBlock().getType()==Material.HOPPER||e.getBlock().getType()==Material.SPAWNER)){e.setCancelled(true);return;}if(e.getBlock().getType()==Material.CHEST&&protectedAt(e.getBlock().getLocation()))for(var face:List.of(org.bukkit.block.BlockFace.NORTH,org.bukkit.block.BlockFace.SOUTH,org.bukkit.block.BlockFace.EAST,org.bukkit.block.BlockFace.WEST))if(e.getBlock().getRelative(face).getType()==Material.CHEST)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void multi(BlockMultiPlaceEvent e){for(BlockState block:e.getReplacedBlockStates())if(protectedAt(block.getLocation())&&!allowed(e.getPlayer(),block.getLocation())){e.setCancelled(true);return;}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void destroy(BlockBreakEvent e){if(protectedAt(e.getBlock().getLocation())&&(!allowed(e.getPlayer(),e.getBlock().getLocation())||plugin.shops.containerRegistered(e.getBlock().getLocation())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void interact(PlayerInteractEvent e){Player p=e.getPlayer();if(plugin.journal.locked(p.getUniqueId())||plugin.fatal){e.setCancelled(true);return;}if(plugin.previews.inside(p)){e.setCancelled(true);return;}Block block=e.getClickedBlock();if(block==null||!protectedAt(block.getLocation()))return;if(e.getHand()==org.bukkit.inventory.EquipmentSlot.HAND&&e.getAction()==Action.RIGHT_CLICK_BLOCK){if(block.getType()==Material.BELL&&plugin.bells.ring(p,block)){e.setCancelled(true);return;}if(plugin.shops.containerRegistered(block.getLocation())&&!plugin.shops.ownerAt(p,block.getLocation())){e.setCancelled(true);plugin.shops.browseContainer(p,block.getLocation());return;}}if(!allowed(p,block.getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void entityInteract(PlayerInteractEntityEvent e){if(plugin.journal.locked(e.getPlayer().getUniqueId())){e.setCancelled(true);return;}if(protectedAt(e.getRightClicked().getLocation())){e.setCancelled(true);if(e.getHand()==org.bukkit.inventory.EquipmentSlot.HAND)plugin.shops.interact(e.getPlayer(),e.getRightClicked());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void inventory(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p){if(plugin.journal.locked(p.getUniqueId())){e.setCancelled(true);return;}Location at=e.getInventory().getLocation();if(at!=null&&protectedAt(at)&&!allowed(p,at))e.setCancelled(true);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void inventoryClick(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p){Location at=e.getInventory().getLocation();if(at!=null&&protectedAt(at)&&!allowed(p,at)){e.setCancelled(true);return;}if((plugin.fatal||plugin.journal.locked(p.getUniqueId())||plugin.previews.inside(p))&&!(e.getInventory().getHolder() instanceof Menus.View)&&!(e.getInventory().getHolder() instanceof Trades.View))e.setCancelled(true);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void inventoryDrag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p){Location at=e.getInventory().getLocation();if(plugin.journal.locked(p.getUniqueId())||plugin.previews.inside(p)||at!=null&&protectedAt(at)&&!allowed(p,at))e.setCancelled(true);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void bucket(PlayerBucketEmptyEvent e){Location l=e.getBlock().getLocation();if(protectedAt(l)||plugin.journal.locked(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void bucketFill(PlayerBucketFillEvent e){if(protectedAt(e.getBlock().getLocation())||plugin.journal.locked(e.getPlayer().getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void swap(PlayerSwapHandItemsEvent e){if(plugin.journal.locked(e.getPlayer().getUniqueId())||plugin.previews.inside(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void drop(PlayerDropItemEvent e){if(plugin.journal.locked(e.getPlayer().getUniqueId())||plugin.previews.inside(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&(plugin.journal.locked(p.getUniqueId())||plugin.previews.inside(p)))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void damage(EntityDamageEvent e){if(protectedAt(e.getEntity().getLocation())||e.getEntity() instanceof Player p&&plugin.journal.locked(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void explode(EntityExplodeEvent e){if(protectedAt(e.getLocation())){e.blockList().clear();e.setCancelled(true);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void blockExplode(BlockExplodeEvent e){if(protectedAt(e.getBlock().getLocation())){e.blockList().clear();e.setCancelled(true);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void burn(BlockBurnEvent e){if(protectedAt(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void ignite(BlockIgniteEvent e){if(protectedAt(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void spread(BlockSpreadEvent e){if(protectedAt(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void flow(BlockFromToEvent e){if(protectedAt(e.getBlock().getLocation())||protectedAt(e.getToBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void piston(BlockPistonExtendEvent e){if(protectedAt(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void retract(BlockPistonRetractEvent e){if(protectedAt(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void dispense(BlockDispenseEvent e){if(protectedAt(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void moveItems(InventoryMoveItemEvent e){Location a=e.getSource().getLocation(),b=e.getDestination().getLocation();if(a!=null&&protectedAt(a)||b!=null&&protectedAt(b))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void hopperPickup(InventoryPickupItemEvent e){Location at=e.getInventory().getLocation();if(at!=null&&protectedAt(at))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void changeBlock(EntityChangeBlockEvent e){if(protectedAt(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void entityPlace(EntityPlaceEvent e){if(protectedAt(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void hanging(HangingPlaceEvent e){if(protectedAt(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void hangingBreak(HangingBreakEvent e){if(protectedAt(e.getEntity().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void sign(SignChangeEvent e){if(protectedAt(e.getBlock().getLocation())&&!allowed(e.getPlayer(),e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void grow(StructureGrowEvent e){if(protectedAt(e.getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void fertilize(BlockFertilizeEvent e){if(protectedAt(e.getBlock().getLocation()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void mobs(CreatureSpawnEvent e){if(protectedAt(e.getLocation())&&plugin.config.file("config").getBoolean("protection.disable-natural-mobs"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void smelt(FurnaceSmeltEvent e){Domain.Plot p=plugin.rentals.at(e.getBlock().getLocation());if(protectedAt(e.getBlock().getLocation())&&(p==null||plugin.rentals.onPlot(p.id())==null||plugin.archives.locked(p.id())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void burnFuel(FurnaceBurnEvent e){Domain.Plot p=plugin.rentals.at(e.getBlock().getLocation());if(protectedAt(e.getBlock().getLocation())&&(p==null||plugin.rentals.onPlot(p.id())==null||plugin.archives.locked(p.id())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void brew(BrewEvent e){Domain.Plot p=plugin.rentals.at(e.getBlock().getLocation());if(protectedAt(e.getBlock().getLocation())&&(p==null||plugin.rentals.onPlot(p.id())==null||plugin.archives.locked(p.id())))e.setCancelled(true);}
}
