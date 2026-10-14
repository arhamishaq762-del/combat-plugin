package com.marketrealms;

import org.bukkit.entity.Player;
import java.util.*;
import static com.marketrealms.Domain.*;
import static com.marketrealms.Store.*;

final class Designs {
    private final MarketRealmsPlugin plugin;
    Designs(MarketRealmsPlugin plugin){this.plugin=plugin;}
    static List<Map<String,Object>> shape(Design d){List<Map<String,Object>> blocks=new ArrayList<>();for(int x=0;x<d.width();x++)for(int z=0;z<d.depth();z++)for(int y=0;y<d.height();y++){String data=null;if(y==0)data=d.floor();else if(y==d.height()-1)data=d.roof();else if(x==0||x==d.width()-1||z==0||z==d.depth()-1){boolean door=z==0&&x==d.width()/2&&y<=2;boolean window=(y==2||y==3)&&((x>1&&x<d.width()-2)||(z>1&&z<d.depth()-2));if(!door)data=window?d.window():d.wall();}if(data!=null)blocks.add(Map.of("x",x,"y",y,"z",z,"block",data));}return blocks;}
    void buy(Player p,String id,Currency currency){var rental=plugin.rentals.requireOwner(p);Design d=plugin.config.designs.get(id);if(d==null||!d.enabled())throw new IllegalArgumentException("Design is unavailable");Plot plot=plugin.config.plots.get(text(rental,"plot"));if(!plot.box().fits(d.width(),d.height(),d.depth()))throw new IllegalArgumentException("Design does not fit your plot");if(!d.categories().isEmpty()&&!d.categories().contains(text(rental,"category")))throw new IllegalArgumentException("Design not permitted for your shop category");plugin.rentals.ensureEmpty(plot);List<Map<String,Object>> shape=shape(d);long amount=d.price().amount(currency);Box b=plot.box();List<org.bukkit.block.Block> placed=new ArrayList<>();
        try{plugin.journal.execute("DESIGN",plugin.journal.payment(p,currency,amount),plugin.journal.charges(p,currency,amount),Map.of("rental",text(rental,"id"),"plot",plot.id(),"design",id,"currency",currency.name(),"amount",amount),op->{for(var data:shape){var block=plugin.worlds.market.getBlockAt(b.x1()+Blocks.integer(data,"x"),b.y1()+Blocks.integer(data,"y"),b.z1()+Blocks.integer(data,"z"));if(!block.getType().isAir())throw new IllegalArgumentException("Plot changed during placement");Blocks.data(block,data);placed.add(block);if(!Blocks.supported(block.getState()))throw new IllegalArgumentException("Unsupported block entity in design");}plugin.worlds.market.save();return null;});plugin.success(p,"Design "+d.name()+" placed. Its shell contains no prefilled stock.");}catch(RuntimeException e){if(!plugin.journal.locked(p.getUniqueId())){for(var block:placed)Blocks.clear(block);plugin.worlds.market.save();}else plugin.db.update("UPDATE plots SET state='DESIGN_REVIEW' WHERE id=?",plot.id());throw e;}}
}
