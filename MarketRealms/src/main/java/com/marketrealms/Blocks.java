package com.marketrealms;

import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import java.util.*;

final class Blocks {
    static boolean supported(BlockState state){return !(state instanceof TileState)||state instanceof Container||state instanceof Sign||state instanceof Bell;}
    static Map<String,Object> capture(Block block,int x,int y,int z){
        BlockState state=block.getState();if(!supported(state))throw new IllegalArgumentException("Unsupported block entity "+block.getType()+" at "+block.getLocation()+". Plot was NOT reset.");
        if(state instanceof org.bukkit.loot.Lootable loot&&loot.getLootTable()!=null)throw new IllegalArgumentException("Loot-table container requires manual emptying before archive");
        Map<String,Object> data=new LinkedHashMap<>();data.put("x",x);data.put("y",y);data.put("z",z);data.put("block",block.getBlockData().getAsString());
        try{if(state instanceof TileState tile)data.put("pdc",Base64.getEncoder().encodeToString(tile.getPersistentDataContainer().serializeToBytes()));}catch(Exception e){throw new IllegalStateException("Block PDC capture failed",e);}
        if(state instanceof Container container){data.put("inventory",Items.encode(container.getSnapshotInventory().getContents()));data.put("lock",container.getLock());if(container.customName()!=null)data.put("name",GsonComponentSerializer.gson().serialize(container.customName()));}
        if(state instanceof Furnace furnace){data.put("burn",(int)furnace.getBurnTime());data.put("cook",(int)furnace.getCookTime());data.put("cook-total",furnace.getCookTimeTotal());}
        if(state instanceof BrewingStand brewing){data.put("brewing",brewing.getBrewingTime());data.put("fuel",brewing.getFuelLevel());}
        if(state instanceof Sign sign){data.put("waxed",sign.isWaxed());for(Side side:Side.values()){var face=sign.getSide(side);data.put(side+"-lines",face.lines().stream().map(c->GsonComponentSerializer.gson().serialize(c)).toList());data.put(side+"-color",face.getColor().name());data.put(side+"-glow",face.isGlowingText());}}
        return data;
    }
    static void data(Block block,Map<?,?> data){block.setBlockData(Bukkit.createBlockData(Objects.toString(data.get("block"))),false);}
    static void state(Block block,Map<?,?> data){
        BlockState state=block.getState();try{if(state instanceof TileState tile&&data.containsKey("pdc"))tile.getPersistentDataContainer().readFromBytes(Base64.getDecoder().decode(Objects.toString(data.get("pdc"))),true);}catch(Exception e){throw new IllegalArgumentException("Cannot restore block PDC",e);}
        if(state instanceof Container container&&data.containsKey("inventory")){ItemStack[] contents=Items.decode(Objects.toString(data.get("inventory")));if(contents.length!=container.getSnapshotInventory().getSize())throw new IllegalArgumentException("Container size changed");container.getSnapshotInventory().setContents(contents);container.setLock(Objects.toString(data.get("lock"),""));if(data.containsKey("name"))container.customName(GsonComponentSerializer.gson().deserialize(Objects.toString(data.get("name"))));}
        if(state instanceof Furnace furnace){furnace.setBurnTime((short)integer(data,"burn"));furnace.setCookTime((short)integer(data,"cook"));furnace.setCookTimeTotal(integer(data,"cook-total"));}
        if(state instanceof BrewingStand brewing){brewing.setBrewingTime(integer(data,"brewing"));brewing.setFuelLevel(integer(data,"fuel"));}
        if(state instanceof Sign sign){sign.setWaxed(Boolean.TRUE.equals(data.get("waxed")));for(Side side:Side.values()){var face=sign.getSide(side);Object raw=data.get(side+"-lines");if(raw instanceof List<?> lines){for(int i=0;i<Math.min(4,lines.size());i++)face.line(i,GsonComponentSerializer.gson().deserialize(Objects.toString(lines.get(i))));}face.setColor(DyeColor.valueOf(Objects.toString(data.get(side+"-color"),"BLACK")));face.setGlowingText(Boolean.TRUE.equals(data.get(side+"-glow")));}}
        if(state instanceof TileState&&!state.update(true,false))throw new IllegalArgumentException("Block state update rejected");
    }
    static int integer(Map<?,?> data,String key){Object n=data.get(key);return n instanceof Number value?value.intValue():0;}
    static List<Map<?,?>> blockList(YamlConfiguration archive){var list=archive.getMapList("blocks");return new ArrayList<>(list);}
    static void clear(Block block){if(block.getState() instanceof Container c){c.getInventory().clear();c.update(true,false);}block.setType(Material.AIR,false);}
}
