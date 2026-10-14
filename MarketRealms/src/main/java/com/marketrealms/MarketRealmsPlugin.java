package com.marketrealms;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.logging.Level;

public final class MarketRealmsPlugin extends JavaPlugin implements Listener {
    Configuration config;Store db;Worlds worlds;Money money;Journal journal;Rentals rentals;Archives archives;Shops shops;Designs designs;Previews previews;Bells bells;Trades trades;Menus menus;
    boolean fatal;long clock;
    @Override public void onEnable(){try{
        if(!Bukkit.getMinecraftVersion().equals("1.21.11"))getLogger().warning("MarketRealms is tested/targeted for Paper 1.21.11; this server is "+Bukkit.getMinecraftVersion());
        config=new Configuration(this);db=new Store(getDataFolder().toPath().resolve("marketrealms.db"));worlds=new Worlds(this);money=new Money(this);journal=new Journal(this);archives=new Archives(this);rentals=new Rentals(this);shops=new Shops(this);bells=new Bells(this);designs=new Designs(this);menus=new Menus(this);trades=new Trades(this);previews=new Previews(this);
        Commands commands=new Commands(this);for(String name:List.of("market","marketadmin","trade")){var command=Objects.requireNonNull(getCommand(name));command.setExecutor(commands);command.setTabCompleter(commands);}
        for(Listener listener:List.of(this,new Protection(this),previews,menus,trades))getServer().getPluginManager().registerEvents(listener,this);
        Bukkit.getScheduler().runTaskTimer(this,()->{clock++;if(fatal)return;try{if(clock%config.file("config").getLong("rentals.expiration-check-ticks")==0)rentals.tick();}catch(RuntimeException e){storageFailure(e);}},1,1);
        Bukkit.getScheduler().runTask(this,()->{try{shops.repair();archives.resume();rentals.tick();for(Player p:Bukkit.getOnlinePlayers()){if(previews.inside(p))previews.exit(p);if(journal.locked(p.getUniqueId()))p.kickPlayer("MarketRealms operation requires administrator review");}}catch(RuntimeException e){storageFailure(e);}});
        getLogger().info("MarketRealms enabled. SQLite schema 1; private previews use Paper packet-backed visibility APIs. Money available: "+money.available());
    }catch(Exception|LinkageError e){getLogger().log(Level.SEVERE,"MarketRealms startup failed safely: "+e.getMessage(),e);fatal=true;getServer().getPluginManager().disablePlugin(this);}}
    @Override public void onDisable(){if(trades!=null)trades.closeAll();if(menus!=null)menus.closeAll();if(previews!=null)previews.clearAll();Bukkit.getScheduler().cancelTasks(this);if(archives!=null)archives.close();if(db!=null)try{db.close();}catch(Exception e){getLogger().log(Level.SEVERE,"Database close failed",e);}}
    void reload()throws Exception{if(archives.busy())throw new IllegalArgumentException("Wait for archive/reset/restore to finish before reloading");Configuration candidate=new Configuration(this);for(String key:List.of("worlds.marketplace","worlds.preview"))if(!candidate.file("config").getString(key).equals(config.file("config").getString(key)))throw new IllegalArgumentException("World changes require a restart and explicit rental/archive migration");rentals.syncDefinitions(candidate);trades.closeAll();menus.closeAll();previews.clearAll();config=candidate;worlds.market.setSpawnLocation(worlds.marketSpawn());worlds.preview.setSpawnLocation(worlds.previewSpawn());money.refresh();rentals.refresh();}
    void permit(Player p,String node){if(!p.hasPermission("marketrealms."+node))throw new IllegalArgumentException("Missing permission marketrealms."+node);}
    String render(String key,Map<String,String> values){String text=config.file("messages").getString(key,"&cMissing message: "+key);for(var entry:values.entrySet())text=text.replace("{"+entry.getKey()+"}",entry.getValue());return ChatColor.translateAlternateColorCodes('&',config.file("messages").getString("prefix","")+text);}
    void message(Player p,String key,Map<String,String> values){p.sendMessage(render(key,values));}
    void info(Player p,String text){message(p,"info",Map.of("message",text));}void success(Player p,String text){message(p,"success",Map.of("message",text));}void error(Player p,String text){message(p,"error",Map.of("reason",Objects.toString(text,"Unexpected error; see server log")));}
    void handle(Player p,Throwable e){if(e instanceof Store.StorageFailure)storageFailure(e);if(!(e instanceof IllegalArgumentException))getLogger().log(Level.WARNING,"Player action failed: "+e.getMessage(),e);error(p,e.getMessage());}
    void storageFailure(Throwable e){fatal=true;getLogger().log(Level.SEVERE,"MarketRealms entered safety mode. Marketplace edits/payments are blocked until server restart and administrator review.",e);for(Player p:Bukkit.getOnlinePlayers())if(p.hasPermission("marketrealms.admin"))error(p,"MarketRealms storage error. Inspect server logs; do not edit the database while running.");}
    @EventHandler(priority=EventPriority.HIGHEST)public void login(PlayerLoginEvent e){if(journal!=null&&journal.locked(e.getPlayer().getUniqueId()))e.disallow(PlayerLoginEvent.Result.KICK_OTHER,"MarketRealms interrupted operation requires administrator reconciliation before login.");}
    @EventHandler public void join(PlayerJoinEvent e){Bukkit.getScheduler().runTaskLater(this,()->{try{if(e.getPlayer().isOnline()){rentals.info(e.getPlayer());bells.login(e.getPlayer());}}catch(RuntimeException x){handle(e.getPlayer(),x);}},30);}
}
