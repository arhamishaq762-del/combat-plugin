package com.marketrealms;

import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;
import java.util.*;

final class Worlds {
    final World market,preview;
    private final MarketRealmsPlugin plugin;
    Worlds(MarketRealmsPlugin plugin){this.plugin=plugin;market=load(plugin.config.file("config").getString("worlds.marketplace"));preview=load(plugin.config.file("config").getString("worlds.preview"));if(market==preview)throw new IllegalArgumentException("Worlds must differ");market.setPVP(false);preview.setPVP(false);market.setSpawnLocation(marketSpawn());preview.setSpawnLocation(previewSpawn());for(Domain.Plot p:plugin.config.plots.values())if(p.box().y1()<market.getMinHeight()||p.box().y2()>=market.getMaxHeight())throw new IllegalArgumentException("Plot height out of world: "+p.id());}
    private World load(String name){World loaded=Bukkit.getWorld(name);if(loaded!=null)return loaded;if(!plugin.config.file("config").getBoolean("worlds.create-missing"))throw new IllegalArgumentException("Missing world "+name);World world=Bukkit.createWorld(new WorldCreator(name).generator(new FloorGenerator()).generateStructures(false));return Objects.requireNonNull(world,"World could not be created: "+name);}
    Location marketSpawn(){return spawn(market,"worlds.market-spawn");}Location previewSpawn(){return spawn(preview,"worlds.preview-spawn");}
    Location exit(){String name=plugin.config.file("config").getString("worlds.exit-world","");World w=Bukkit.getWorld(name);if(w==null||w==preview)w=Bukkit.getWorlds().stream().filter(x->x!=market&&x!=preview).findFirst().orElse(market);return w==market?marketSpawn():w.getSpawnLocation();}
    private Location spawn(World world,String key){List<Double> values=plugin.config.file("config").getDoubleList(key);if(values.size()!=5)throw new IllegalArgumentException(key+" requires [x,y,z,yaw,pitch]");return new Location(world,values.get(0),values.get(1),values.get(2),values.get(3).floatValue(),values.get(4).floatValue());}
    boolean protectedWorld(World w){return w==market||w==preview;}
    static final class FloorGenerator extends ChunkGenerator {
        @Override public void generateNoise(WorldInfo world,Random random,int x,int z,ChunkData data){for(int a=0;a<16;a++)for(int b=0;b<16;b++){data.setBlock(a,63,b,Material.BEDROCK);data.setBlock(a,64,b,Material.SMOOTH_STONE);}}
        @Override public boolean shouldGenerateNoise(){return false;}@Override public boolean shouldGenerateSurface(){return false;}@Override public boolean shouldGenerateCaves(){return false;}@Override public boolean shouldGenerateDecorations(){return false;}@Override public boolean shouldGenerateMobs(){return false;}@Override public boolean shouldGenerateStructures(){return false;}
    }
}
