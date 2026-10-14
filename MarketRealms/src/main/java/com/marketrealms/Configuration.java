package com.marketrealms;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.marketrealms.Domain.*;

final class Configuration {
    static final List<String> FILES=List.of("config","rental-plans","categories","designs","messages","trade","bell","recovery","plots","shops");
    final Map<String,YamlConfiguration> files=new HashMap<>();
    final Map<String,Plan> plans=new LinkedHashMap<>(); final Map<String,Category> categories=new LinkedHashMap<>();
    final Map<String,Plot> plots=new LinkedHashMap<>(); final Map<String,Design> designs=new LinkedHashMap<>();
    Configuration(JavaPlugin plugin) throws Exception {
        for(String file:FILES) {
            File path=new File(plugin.getDataFolder(),file+".yml");if(!path.exists())plugin.saveResource(file+".yml",false);
            YamlConfiguration y=new YamlConfiguration();y.load(path);
            try(var reader=new InputStreamReader(Objects.requireNonNull(plugin.getResource(file+".yml")),StandardCharsets.UTF_8)){y.setDefaults(YamlConfiguration.loadConfiguration(reader));}
            y.options().copyDefaults(true); files.put(file,y);
        }
        var main=file("config");
        if(main.getString("worlds.marketplace").equals(main.getString("worlds.preview")))throw new IllegalArgumentException("Marketplace and preview must be different worlds");
        for(String world:List.of(main.getString("worlds.marketplace"),main.getString("worlds.preview")))if(!world.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid world name: "+world);
        range("config","limits.max-plot-volume",1,262144);range("config","limits.max-listings-per-owner",1,256);range("config","limits.max-stock",1,1000000);
        range("config","limits.interaction-distance",1,6);range("config","preview.maximum-size",4,32);range("config","preview.pad-spacing",8,64);range("config","preview.highlight-ticks",1,100);
        range("config","rentals.expiration-check-ticks",1,1200);range("recovery","blocks-per-tick",16,4096);range("recovery","maximum-expanded-bytes",1048576,134217728);
        range("trade","request-timeout-seconds",5,300);range("trade","request-cooldown-seconds",1,300);range("trade","maximum-distance",1,32);
        range("trade","maximum-offer-slots",1,18);range("bell","cooldown-seconds",1,3600);
        for(var entry:files.entrySet())for(String key:entry.getValue().getDefaults().getKeys(true))if(entry.getValue().getDefaults().get(key) instanceof Boolean&&!(entry.getValue().get(key) instanceof Boolean))throw new IllegalArgumentException(entry.getKey()+"."+key+" must be true/false");
        section(file("rental-plans"),"plans").getKeys(false).forEach(key->{var s=section(file("rental-plans"),"plans."+key);double days=s.getDouble("duration-days");if(!Double.isFinite(days)||days<=0||days>3650)throw new IllegalArgumentException("Invalid duration for plan "+key);plans.put(id(key),new Plan(key,s.getString("name",key),(long)(days*86400000),price(s),s.getBoolean("enabled")));});
        section(file("categories"),"categories").getKeys(false).forEach(key->{var s=section(file("categories"),"categories."+key);Material icon=Material.matchMaterial(s.getString("icon","CHEST"));if(icon==null||!icon.isItem())throw new IllegalArgumentException("Invalid category icon: "+key);categories.put(id(key),new Category(key,s.getString("name",key),s.getString("description",""),icon.name(),s.getInt("order"),s.getBoolean("enabled")));});
        section(file("plots"),"plots").getKeys(false).forEach(key->{var s=section(file("plots"),"plots."+key);List<Integer> a=s.getIntegerList("min"),b=s.getIntegerList("max");if(a.size()!=3||b.size()!=3)throw new IllegalArgumentException("Plot "+key+" needs min/max triples");Box box=new Box(a.get(0),a.get(1),a.get(2),b.get(0),b.get(1),b.get(2));if(box.volume()>main.getLong("limits.max-plot-volume"))throw new IllegalArgumentException("Plot too large: "+key);Set<String> cat=Set.copyOf(s.getStringList("categories"));cat.forEach(this::requireCategory);String district=s.getString("district","");var ds=file("categories").getConfigurationSection("districts."+district);if(ds==null)throw new IllegalArgumentException("Unknown district "+district);if(!ds.getStringList("categories").containsAll(cat))throw new IllegalArgumentException("Plot categories exceed district permissions: "+key);plots.put(id(key),new Plot(key,s.getString("name",key),district,cat,box,s.getBoolean("enabled")));});
        List<Plot> all=new ArrayList<>(plots.values());for(int i=0;i<all.size();i++)for(int j=i+1;j<all.size();j++)if(all.get(i).box().intersects(all.get(j).box()))throw new IllegalArgumentException("Overlapping plots: "+all.get(i).id()+" / "+all.get(j).id());
        section(file("designs"),"designs").getKeys(false).forEach(key->{var s=section(file("designs"),"designs."+key);List<Integer>d=s.getIntegerList("dimensions");if(d.size()!=3||d.stream().anyMatch(n->n<3||n>32))throw new IllegalArgumentException("Design dimensions must be 3..32: "+key);Set<String> cat=Set.copyOf(s.getStringList("categories"));cat.forEach(this::requireCategory);for(String type:List.of("floor","wall","roof","window")){String block=s.getString(type);if(block==null||Bukkit.createBlockData(block).getMaterial().isAir())throw new IllegalArgumentException("Invalid design palette "+key+"."+type);}designs.put(id(key),new Design(key,s.getString("name",key),s.getString("description",""),d.get(0),d.get(1),d.get(2),cat,price(s),s.getBoolean("enabled"),s.getString("floor"),s.getString("wall"),s.getString("roof"),s.getString("window")));});
        if(plans.values().stream().noneMatch(Plan::enabled))throw new IllegalArgumentException("At least one rental plan must be enabled");
    }
    YamlConfiguration file(String name){return Objects.requireNonNull(files.get(name),name);}
    private void requireCategory(String id){if(!categories.containsKey(id))throw new IllegalArgumentException("Unknown category: "+id);}
    static ConfigurationSection section(ConfigurationSection s,String path){var child=s.getConfigurationSection(path);if(child==null)throw new IllegalArgumentException("Missing section "+path);return child;}
    static Price price(ConfigurationSection s){String e=s.getString("emeralds","-"),m=s.getString("money","-");Long emerald=e.equals("-")?null:Long.parseLong(e);if(emerald!=null&&emerald>1000000)throw new IllegalArgumentException("Emerald price too large");return new Price(emerald,m.equals("-")?null:cents(m));}
    private void range(String file,String path,double min,double max){Object raw=file(file).get(path);if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<min||n.doubleValue()>max)throw new IllegalArgumentException(file+"."+path+" must be "+min+".."+max);}
}
