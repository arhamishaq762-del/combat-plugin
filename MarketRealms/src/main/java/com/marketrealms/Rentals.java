package com.marketrealms;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.*;
import com.marketrealms.Domain.Currency;
import static com.marketrealms.Domain.*;
import static com.marketrealms.Store.*;

final class Rentals {
    private final MarketRealmsPlugin plugin;
    private final Map<String,Map<String,Object>> active=new HashMap<>();
    Rentals(MarketRealmsPlugin plugin){this.plugin=plugin;syncDefinitions(plugin.config);refresh();}
    void syncDefinitions(Configuration candidate){
        // Reject silent relocation, deletion or permission changes of any occupied/locked plot.
        for(var row:plugin.db.rows("SELECT * FROM plots")){
            String id=text(row,"id");Plot p=candidate.plots.get(id);String before=text(row,"definition");boolean occupied=!text(row,"state").equals("AVAILABLE");
            if(occupied&&(p==null||!before.equals(definition(p))))throw new IllegalArgumentException("Plot "+id+" is occupied/locked. Archive/reset explicitly before changing its definition.");
            if(p==null)plugin.db.update("DELETE FROM plots WHERE id=? AND state='AVAILABLE' AND NOT EXISTS(SELECT 1 FROM rentals WHERE plot=?)",id,id);
        }
        plugin.db.transaction(()->{for(Plot p:candidate.plots.values())plugin.db.update("INSERT INTO plots(id,definition) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET definition=excluded.definition",p.id(),definition(p));return null;});
    }
    private String definition(Plot p){return p.id()+"|"+p.box()+"|"+p.district()+"|"+String.join(",",new TreeSet<>(p.categories()))+"|"+p.enabled();}
    void refresh(){active.clear();for(var row:plugin.db.rows("SELECT * FROM rentals WHERE state='ACTIVE'"))active.put(text(row,"plot"),row);}
    Map<String,Object> owned(UUID owner){return plugin.db.one("SELECT * FROM rentals WHERE owner=? AND state='ACTIVE' AND expires>?",owner.toString(),System.currentTimeMillis());}
    Map<String,Object> onPlot(String plot){Map<String,Object> row=active.get(plot);return row!=null&&number(row,"expires")>System.currentTimeMillis()?row:null;}
    Plot at(Location location){if(location.getWorld()!=plugin.worlds.market)return null;for(Plot p:plugin.config.plots.values())if(p.box().contains(location))return p;return null;}
    boolean canBuild(Player player,Location location){if(plugin.fatal||plugin.journal.locked(player.getUniqueId()))return false;Plot p=at(location);if(p==null)return false;Map<String,Object> r=onPlot(p.id());return r!=null&&text(r,"owner").equals(player.getUniqueId().toString())&&player.hasPermission("marketrealms.build")&&!plugin.archives.locked(p.id())&&plugin.db.one("SELECT id FROM plots WHERE id=? AND state='RENTED'",p.id())!=null;}
    boolean available(String plot){return plugin.config.plots.containsKey(plot)&&plugin.config.plots.get(plot).enabled()&&plugin.db.one("SELECT id FROM plots WHERE id=? AND state='AVAILABLE'",plot)!=null;}
    void ensureEmpty(Plot plot){Box b=plot.box();for(int x=b.x1();x<=b.x2();x++)for(int y=b.y1();y<=b.y2();y++)for(int z=b.z1();z<=b.z2();z++)if(!plugin.worlds.market.getBlockAt(x,y,z).getType().isAir())throw new IllegalArgumentException("Plot is not empty; an administrator must inspect/reset it before assignment");}
    void rent(Player player,String plotId,String planId,Currency currency){
        plugin.journal.require(player);Plot plot=Objects.requireNonNull(plugin.config.plots.get(plotId),"Unknown plot");Plan plan=Objects.requireNonNull(plugin.config.plans.get(planId),"Unknown plan");
        if(!plan.enabled()||!available(plotId))throw new IllegalArgumentException("Plot or plan is unavailable");
        if(plugin.db.one("SELECT id FROM rentals WHERE owner=? AND state IN ('ACTIVE','ARCHIVING','RESETTING')",player.getUniqueId().toString())!=null)throw new IllegalArgumentException("You already have a rental or one undergoing recovery");
        ensureEmpty(plot);long amount=plan.price().amount(currency);String id=UUID.randomUUID().toString();long expires=Math.addExact(System.currentTimeMillis(),plan.durationMillis());
        String category=plot.categories().stream().filter(c->plugin.config.categories.get(c).enabled()).sorted().findFirst().orElseThrow(()->new IllegalArgumentException("Plot has no enabled category"));
        plugin.journal.execute("RENT",plugin.journal.payment(player,currency,amount),plugin.journal.charges(player,currency,amount),Map.of("plot",plotId,"rental",id,"plan",planId,"currency",currency.name(),"amount",amount),op->{if(plugin.db.update("UPDATE plots SET state='RENTED' WHERE id=? AND state='AVAILABLE'",plotId)!=1)throw new IllegalArgumentException("Plot was rented by someone else");plugin.db.update("INSERT INTO rentals VALUES(?,?,?,?,?,?,'ACTIVE')",id,player.getUniqueId().toString(),plotId,planId,category,expires);return null;});refresh();plugin.success(player,"Rented "+plot.name()+" until "+java.time.Instant.ofEpochMilli(expires)+". /market home; /market designs or build in survival.");
    }
    void renew(Player player,String planId,Currency currency){Map<String,Object> r=requireOwner(player);Plan plan=Objects.requireNonNull(plugin.config.plans.get(planId),"Unknown plan");if(!plan.enabled())throw new IllegalArgumentException("Plan disabled");long amount=plan.price().amount(currency);long old=number(r,"expires"),expiry=renewedExpiry(old,System.currentTimeMillis(),plan.durationMillis());String id=text(r,"id");plugin.journal.execute("RENEW",plugin.journal.payment(player,currency,amount),plugin.journal.charges(player,currency,amount),Map.of("rental",id,"old-expiry",old,"new-expiry",expiry,"currency",currency.name(),"amount",amount),op->{if(plugin.db.update("UPDATE rentals SET expires=?,plan=? WHERE id=? AND state='ACTIVE' AND expires=?",expiry,planId,id,old)!=1)throw new IllegalArgumentException("Rental changed; confirm again");plugin.db.update("DELETE FROM warnings WHERE rental=?",id);return null;});refresh();info(player);}
    Map<String,Object> requireOwner(Player p){plugin.journal.require(p);var r=owned(p.getUniqueId());if(r==null)throw new IllegalArgumentException("You do not have an active rental");if(plugin.archives!=null&&plugin.archives.locked(text(r,"plot")))throw new IllegalArgumentException("Plot is being recovered");return r;}
    void home(Player p){var r=owned(p.getUniqueId());if(r==null)throw new IllegalArgumentException("No active rental");Plot plot=plugin.config.plots.get(text(r,"plot"));p.teleport(new Location(plugin.worlds.market,plot.box().x1()+0.5,plot.box().y1(),plot.box().z1()+0.5));}
    void category(Player p,String category){var r=requireOwner(p);Plot plot=plugin.config.plots.get(text(r,"plot"));Category cat=plugin.config.categories.get(category);if(cat==null||!cat.enabled()||!plot.categories().contains(category))throw new IllegalArgumentException("Category not permitted for this plot");plugin.db.update("UPDATE rentals SET category=? WHERE id=?",category,text(r,"id"));refresh();plugin.success(p,"Category set to "+cat.name());}
    void info(Player p){var r=owned(p.getUniqueId());if(r==null){plugin.info(p,"No active rental. /market preview");return;}plugin.message(p,"remaining",Map.of("plot",text(r,"plot"),"expires",java.time.Instant.ofEpochMilli(number(r,"expires")).toString(),"remaining",remaining(number(r,"expires"),System.currentTimeMillis())));}
    void tick(){long now=System.currentTimeMillis();for(var r:plugin.db.rows("SELECT * FROM rentals WHERE state='ACTIVE' AND expires<=?",now)){String plot=text(r,"plot");plugin.db.transaction(()->{plugin.db.update("UPDATE rentals SET state='ARCHIVING' WHERE id=? AND state='ACTIVE'",text(r,"id"));plugin.db.update("UPDATE plots SET state='ARCHIVING' WHERE id=?",plot);plugin.db.update("UPDATE listings SET paused=1 WHERE rental=?",text(r,"id"));return null;});refresh();Player owner=Bukkit.getPlayer(UUID.fromString(text(r,"owner")));if(owner!=null)plugin.message(owner,"expired",Map.of("plot",plot));plugin.archives.expire(r);}
        for(var r:active.values())for(int hours:plugin.config.file("config").getIntegerList("rentals.warning-hours")){if(hours<=0)continue;if(number(r,"expires")-now<=hours*3600000L&&plugin.db.one("SELECT 1 FROM warnings WHERE rental=? AND hours=?",text(r,"id"),hours)==null){Player owner=Bukkit.getPlayer(UUID.fromString(text(r,"owner")));if(owner!=null){plugin.message(owner,"warning",Map.of("plot",text(r,"plot"),"remaining",remaining(number(r,"expires"),now)));plugin.db.update("INSERT OR IGNORE INTO warnings VALUES(?,?)",text(r,"id"),hours);}}}}
}
