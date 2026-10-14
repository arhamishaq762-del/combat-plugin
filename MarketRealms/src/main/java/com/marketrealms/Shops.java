package com.marketrealms;

import static com.marketrealms.Domain.*;
import static com.marketrealms.Store.*;

import com.marketrealms.Domain.Currency;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

final class Shops {
  private final MarketRealmsPlugin plugin;
  private final NamespacedKey listingKey;

  Shops(MarketRealmsPlugin plugin) {
    this.plugin = plugin;
    listingKey = new NamespacedKey(plugin, "listing");
  }

  Map<String, Object> listing(String id) {
    var row = plugin.db.one("SELECT * FROM listings WHERE id=?", id);
    if (row == null) throw new IllegalArgumentException("Listing not found");
    return row;
  }

  Price price(Map<String, Object> r) {
    return new Price(
        r.get("emeralds") == null ? null : number(r, "emeralds"),
        r.get("money") == null ? null : number(r, "money"));
  }

  ItemStack item(Map<String, Object> r) {
    return Items.decode(text(r, "item"))[0];
  }

  Location location(Map<String, Object> r) {
    return new Location(plugin.worlds.market, number(r, "x"), number(r, "y"), number(r, "z"));
  }

  boolean containerRegistered(Location l) {
    return l.getWorld() == plugin.worlds.market
        && plugin.db.one(
                "SELECT id FROM listings WHERE kind='CONTAINER' AND x=? AND y=? AND z=?",
                l.getBlockX(),
                l.getBlockY(),
                l.getBlockZ())
            != null;
  }

  boolean ownerAt(Player p, Location l) {
    var rows =
        plugin.db.rows(
            "SELECT * FROM listings WHERE kind='CONTAINER' AND x=? AND y=? AND z=?",
            l.getBlockX(),
            l.getBlockY(),
            l.getBlockZ());
    return !rows.isEmpty()
        && rows.stream().allMatch(r -> text(r, "owner").equals(p.getUniqueId().toString()))
        && plugin.rentals.canBuild(p, l);
  }

  private Container container(Map<String, Object> r) {
    var state = location(r).getBlock().getState();
    if (!(state instanceof Container c)
        || !plugin
            .config
            .file("shops")
            .getStringList("container-materials")
            .contains(state.getType().name()))
      throw new IllegalArgumentException("Registered stock container is missing or unsupported");
    if (c.getInventory().getSize() > 27)
      throw new IllegalArgumentException(
          "Double/combined containers are not supported; use a single chest or barrel");
    return c;
  }

  int stock(Map<String, Object> r) {
    return text(r, "kind").equals("DISPLAY")
        ? (int) number(r, "stock")
        : Items.count(container(r).getInventory().getContents(), item(r));
  }

  Map<String, Object> validate(Map<String, Object> r) {
    var rental =
        plugin.db.one(
            "SELECT * FROM rentals WHERE id=? AND state='ACTIVE' AND expires>?",
            text(r, "rental"),
            System.currentTimeMillis());
    if (rental == null
        || !text(rental, "owner").equals(text(r, "owner"))
        || !text(rental, "plot").equals(text(r, "plot"))
        || plugin.archives.locked(text(r, "plot")))
      throw new IllegalArgumentException("Shop rental is inactive or undergoing recovery");
    Domain.Plot plot = plugin.config.plots.get(text(r, "plot"));
    if (plot == null || !plot.box().contains(location(r)))
      throw new IllegalArgumentException("Listing is outside its plot");
    return rental;
  }

  void nearby(Player p, Map<String, Object> r) {
    Location center = location(r).add(0.5, 0.5, 0.5);
    if (p.getWorld() != center.getWorld()
        || p.getLocation().distanceSquared(center)
            > Math.pow(plugin.config.file("config").getDouble("limits.interaction-distance"), 2))
      throw new IllegalArgumentException("Stand near the listing in its shop");
    var ray =
        p.getWorld()
            .rayTraceBlocks(
                p.getEyeLocation(),
                center.toVector().subtract(p.getEyeLocation().toVector()).normalize(),
                p.getEyeLocation().distance(center),
                FluidCollisionMode.NEVER,
                true);
    if (ray != null
        && ray.getHitBlock() != null
        && !ray.getHitBlock().getLocation().equals(location(r)))
      throw new IllegalArgumentException("Listing interaction is blocked by a wall");
  }

  void create(Player p, String kind, int quantity, Price price) {
    var rental = plugin.rentals.requireOwner(p);
    if (quantity <= 0 || quantity > 2304)
      throw new IllegalArgumentException("Quantity must be 1..2304");
    if (plugin.db.rows("SELECT id FROM listings WHERE owner=?", p.getUniqueId().toString()).size()
        >= plugin.config.file("config").getInt("limits.max-listings-per-owner"))
      throw new IllegalArgumentException("Too many listings");
    ItemStack template = Items.template(p.getInventory().getItemInMainHand());
    Location at;
    if (kind.equals("CONTAINER")) {
      var target = p.getTargetBlockExact(4);
      if (target == null
          || !(target.getState() instanceof Container)
          || !plugin.rentals.canBuild(p, target.getLocation()))
        throw new IllegalArgumentException("Look at an eligible container in your own plot");
      at = target.getLocation();
      if (!plugin
              .config
              .file("shops")
              .getStringList("container-materials")
              .contains(target.getType().name())
          || ((Container) target.getState()).getInventory().getSize() > 27)
        throw new IllegalArgumentException("Use a single chest or barrel");
      if (plugin.db.one(
              "SELECT id FROM listings WHERE kind='CONTAINER' AND x=? AND y=? AND z=? AND item=?",
              at.getBlockX(),
              at.getBlockY(),
              at.getBlockZ(),
              Items.encode(new ItemStack[] {template}))
          != null) throw new IllegalArgumentException("This container already lists that item");
    } else {
      at = p.getLocation().getBlock().getLocation();
      if (!plugin.rentals.canBuild(p, at))
        throw new IllegalArgumentException("Stand inside your own plot");
    }
    String id = UUID.randomUUID().toString();
    plugin.db.update(
        "INSERT INTO listings(id,owner,rental,plot,kind,item,quantity,emeralds,money,x,y,z)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        p.getUniqueId().toString(),
        text(rental, "id"),
        text(rental, "plot"),
        kind,
        Items.encode(new ItemStack[] {template}),
        quantity,
        price.emeralds(),
        price.money(),
        at.getBlockX(),
        at.getBlockY(),
        at.getBlockZ());
    if (kind.equals("DISPLAY")) spawn(listing(id));
    plugin.success(
        p,
        "Listing created: "
            + id
            + ". Display stock starts at zero; use /market shop restock "
            + id
            + " <count>.");
  }

  void restock(Player p, String id, int count) {
    var r = owned(p, id);
    if (!text(r, "kind").equals("DISPLAY"))
      throw new IllegalArgumentException(
          "Restock container shops by opening your chest and adding matching items");
    if (count <= 0
        || number(r, "stock") + count > plugin.config.file("config").getInt("limits.max-stock"))
      throw new IllegalArgumentException("Invalid stock amount");
    ItemStack template = item(r);
    var before = p.getInventory().getStorageContents();
    var after = Items.remove(before, template, count);
    plugin.journal.execute(
        "RESTOCK",
        List.of(new Journal.Change(p, before, after)),
        List.of(),
        Map.of("listing", id, "count", count),
        op -> {
          plugin.db.update("UPDATE listings SET stock=stock+? WHERE id=?", count, id);
          return null;
        });
    plugin.success(p, "Restocked " + count + " items");
  }

  Map<String, Object> owned(Player p, String id) {
    plugin.rentals.requireOwner(p);
    var r = listing(id);
    validate(r);
    if (!text(r, "owner").equals(p.getUniqueId().toString()))
      throw new IllegalArgumentException("That listing belongs to someone else");
    return r;
  }

  void pause(Player p, String id, boolean paused) {
    owned(p, id);
    plugin.db.update("UPDATE listings SET paused=? WHERE id=?", paused ? 1 : 0, id);
    plugin.success(p, paused ? "Listing paused" : "Listing resumed");
  }

  void edit(Player p, String id, int quantity, Price price) {
    owned(p, id);
    if (quantity <= 0 || quantity > 2304)
      throw new IllegalArgumentException("Quantity must be 1..2304");
    plugin.db.update(
        "UPDATE listings SET quantity=?,emeralds=?,money=? WHERE id=?",
        quantity,
        price.emeralds(),
        price.money(),
        id);
    plugin.success(p, "Listing settings updated");
  }

  void remove(Player p, String id) {
    var r = owned(p, id);
    plugin.db.transaction(
        () -> {
          if (text(r, "kind").equals("DISPLAY") && number(r, "stock") > 0)
            mail(p.getUniqueId(), item(r), (int) number(r, "stock"), "listing-" + id);
          plugin.db.update("DELETE FROM listings WHERE id=?", id);
          return null;
        });
    removeEntities(r);
    plugin.success(
        p,
        "Listing removed. Display stock is in /market sales deliveries; container contents were"
            + " left untouched.");
  }

  void buy(Player buyer, String id, Currency currency, Long expectedPrice, Long expectedQuantity) {
    plugin.journal.require(buyer);
    var r = listing(id);
    validate(r);
    nearby(buyer, r);
    if (text(r, "owner").equals(buyer.getUniqueId().toString()))
      throw new IllegalArgumentException("You cannot buy from yourself");
    if (number(r, "paused") != 0) throw new IllegalArgumentException("Listing paused");
    long amount = price(r).amount(currency);
    int quantity = (int) number(r, "quantity");
    if (expectedPrice != null && expectedPrice != amount
        || expectedQuantity != null && expectedQuantity != quantity)
      throw new IllegalArgumentException(
          "Listing changed; inspect the new price and confirm again");
    if (stock(r) < quantity) throw new IllegalArgumentException("Insufficient stock");
    ItemStack template = item(r);
    ItemStack[] before = buyer.getInventory().getStorageContents(), after = Items.copy(before);
    if (currency == Currency.EMERALDS)
      after = Items.remove(after, Items.emerald(), Math.toIntExact(amount));
    else plugin.money.require();
    after = Items.add(after, template, quantity);
    Container container = text(r, "kind").equals("CONTAINER") ? container(r) : null;
    ItemStack[] stockBefore = container == null ? null : container.getInventory().getContents();
    ItemStack[] stockAfter =
        container == null ? null : Items.remove(stockBefore, template, quantity);
    Map<String, Object> details =
        new HashMap<>(
            Map.of(
                "listing",
                id,
                "seller",
                text(r, "owner"),
                "currency",
                currency.name(),
                "amount",
                amount,
                "quantity",
                quantity,
                "stock-before",
                number(r, "stock"),
                "plot",
                text(r, "plot")));
    if (container != null) {
      details.put("container-before", Items.encode(stockBefore));
      details.put("container-after", Items.encode(stockAfter));
      details.put("container-location", List.of(number(r, "x"), number(r, "y"), number(r, "z")));
    }
    try {
      plugin.journal.execute(
          "PURCHASE",
          List.of(new Journal.Change(buyer, before, after)),
          plugin.journal.charges(buyer, currency, amount),
          details,
          op -> {
            var current = listing(id);
            validate(current);
            if (number(current, "paused") != 0
                || price(current).amount(currency) != amount
                || number(current, "quantity") != quantity)
              throw new IllegalArgumentException("Listing changed");
            if (container == null) {
              if (plugin.db.update(
                      "UPDATE listings SET stock=stock-? WHERE id=? AND stock>=?",
                      quantity,
                      id,
                      quantity)
                  != 1) throw new IllegalArgumentException("Stock sold out");
            } else {
              if (!Items.equal(stockBefore, container.getInventory().getContents()))
                throw new IllegalArgumentException("Container stock changed");
              container.getInventory().setContents(Items.copy(stockAfter));
              plugin.worlds.market.save();
            }
            plugin.journal.credit(UUID.fromString(text(r, "owner")), currency, amount);
            plugin.db.update(
                "INSERT INTO sales VALUES(?,?,?,?,?,?,?,?)",
                op,
                id,
                text(r, "owner"),
                buyer.getUniqueId().toString(),
                quantity,
                currency.name(),
                amount,
                System.currentTimeMillis());
            return null;
          });
    } catch (RuntimeException e) {
      if (container != null) {
        if (!plugin.journal.locked(buyer.getUniqueId())) {
          container.getInventory().setContents(Items.copy(stockBefore));
          plugin.worlds.market.save();
        } else plugin.db.update("UPDATE plots SET state='SHOP_REVIEW' WHERE id=?", text(r, "plot"));
      }
      throw e;
    }
    plugin.message(buyer, "purchase", Map.of());
    Player seller = Bukkit.getPlayer(UUID.fromString(text(r, "owner")));
    if (seller != null)
      plugin.message(
          seller,
          "sale",
          Map.of(
              "buyer",
              buyer.getName(),
              "quantity",
              "" + quantity,
              "item",
              template.getType().name(),
              "price",
              price(r).display(currency)));
  }

  private void spawn(Map<String, Object> r) {
    if (!text(r, "kind").equals("DISPLAY")) return;
    Location at = location(r).add(.5, 1, .5);
    if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4))
      at.getChunk().load();
    ItemDisplay display =
        at.getWorld()
            .spawn(
                at,
                ItemDisplay.class,
                e -> {
                  e.setInvulnerable(true);
                  e.setGravity(false);
                  e.setPersistent(true);
                  e.setItemStack(item(r));
                  var transform = e.getTransformation();
                  transform.getScale().set(plugin.config.file("shops").getDouble("display-scale"));
                  e.setTransformation(transform);
                  e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                  e.getPersistentDataContainer()
                      .set(listingKey, PersistentDataType.STRING, text(r, "id"));
                });
    Interaction interaction =
        at.getWorld()
            .spawn(
                at.clone().subtract(0, .6, 0),
                Interaction.class,
                e -> {
                  e.setInvulnerable(true);
                  e.setPersistent(true);
                  e.setInteractionWidth(1);
                  e.setInteractionHeight(1.4f);
                  e.setResponsive(true);
                  e.getPersistentDataContainer()
                      .set(listingKey, PersistentDataType.STRING, text(r, "id"));
                });
    plugin.db.update(
        "UPDATE listings SET display=?,interaction=? WHERE id=?",
        display.getUniqueId().toString(),
        interaction.getUniqueId().toString(),
        text(r, "id"));
  }

  void interact(Player p, Entity e) {
    String id = e.getPersistentDataContainer().get(listingKey, PersistentDataType.STRING);
    if (id == null) return;
    try {
      var r = listing(id);
      validate(r);
      if (!e.getUniqueId().toString().equals(text(r, "display"))
          && !e.getUniqueId().toString().equals(text(r, "interaction")))
        throw new IllegalArgumentException("Stale/orphaned display");
      nearby(p, r);
      plugin.menus.purchase(p, id);
    } catch (RuntimeException ex) {
      plugin.error(p, ex.getMessage());
    }
  }

  void browseContainer(Player p, Location at) {
    var rows =
        plugin.db.rows(
            "SELECT * FROM listings WHERE kind='CONTAINER' AND x=? AND y=? AND z=?",
            at.getBlockX(),
            at.getBlockY(),
            at.getBlockZ());
    plugin.menus.listings(p, rows, false);
  }

  private Entity entity(String id) {
    try {
      return Bukkit.getEntity(UUID.fromString(id));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private void removeEntities(Map<String, Object> r) {
    for (String field : List.of("display", "interaction")) {
      Entity e = entity(text(r, field));
      if (e != null) e.remove();
    }
  }

  void removeEntitiesForPlot(String plot) {
    for (var r : plugin.db.rows("SELECT * FROM listings WHERE plot=?", plot)) removeEntities(r);
  }

  void repair() {
    for (var r : plugin.db.rows("SELECT * FROM listings WHERE kind='DISPLAY'")) {
      Location l = location(r);
      l.getChunk().load();
      if (entity(text(r, "display")) == null || entity(text(r, "interaction")) == null) {
        removeEntities(r);
        spawn(r);
      }
    }
    for (Entity e : plugin.worlds.market.getEntities()) {
      String id = e.getPersistentDataContainer().get(listingKey, PersistentDataType.STRING);
      if (id == null) continue;
      var r = plugin.db.one("SELECT * FROM listings WHERE id=?", id);
      if (r == null
          || !e.getUniqueId().toString().equals(text(r, "display"))
              && !e.getUniqueId().toString().equals(text(r, "interaction"))) e.remove();
    }
  }

  void restoreListings(YamlConfiguration manifest, Map<String, Object> rental, String archiveId) {
    List<Integer> origin = manifest.getIntegerList("origin");
    Plot plot = plugin.config.plots.get(text(rental, "plot"));
    for (Map<?, ?> listing : manifest.getMapList("listings")) {
      if (origin.size() != 3)
        throw new IllegalArgumentException("Archive lacks original plot origin");
      int x = ((Number) listing.get("x")).intValue() - origin.get(0) + plot.box().x1(),
          y = ((Number) listing.get("y")).intValue() - origin.get(1) + plot.box().y1(),
          z = ((Number) listing.get("z")).intValue() - origin.get(2) + plot.box().z1();
      if (!plot.box().contains(x, y, z))
        throw new IllegalArgumentException("Archived listing outside plot");
      String id =
          UUID.nameUUIDFromBytes(
                  (archiveId + ":" + listing.get("id")).getBytes(StandardCharsets.UTF_8))
              .toString();
      plugin.db.update(
          "INSERT OR IGNORE INTO"
              + " listings(id,owner,rental,plot,kind,item,quantity,emeralds,money,stock,paused,x,y,z)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?,1,?,?,?)",
          id,
          text(rental, "owner"),
          text(rental, "id"),
          plot.id(),
          listing.get("kind"),
          listing.get("item"),
          listing.get("quantity"),
          listing.get("emeralds"),
          listing.get("money"),
          listing.get("stock"),
          x,
          y,
          z);
    }
  }

  void mail(UUID owner, ItemStack item, int count, String prefix) {
    int page = 0;
    while (count > 0) {
      int amount = Math.min(count, item.getMaxStackSize() * 36);
      ItemStack[] batch = Items.add(new ItemStack[36], item, amount);
      plugin.db.update(
          "INSERT OR IGNORE INTO deliveries VALUES(?,?,?,0)",
          prefix + ":" + page++,
          owner.toString(),
          Items.encode(batch));
      count -= amount;
    }
  }

  void claim(Player p, Currency currency, int maximum) {
    plugin.journal.require(p);
    var wallet = plugin.db.one("SELECT * FROM wallets WHERE owner=?", p.getUniqueId().toString());
    if (wallet == null) throw new IllegalArgumentException("No proceeds");
    String column = currency == Currency.EMERALDS ? "emeralds" : "money";
    long amount = number(wallet, column);
    if (amount <= 0) throw new IllegalArgumentException("No proceeds for that currency");
    List<Journal.Change> changes;
    List<Journal.Charge> charges;
    if (currency == Currency.EMERALDS) {
      amount = Math.min(amount, Math.max(1, Math.min(2304, maximum)));
      var before = p.getInventory().getStorageContents();
      changes =
          List.of(new Journal.Change(p, before, Items.add(before, Items.emerald(), (int) amount)));
      charges = List.of();
    } else {
      plugin.money.require();
      changes =
          List.of(
              new Journal.Change(
                  p, p.getInventory().getStorageContents(), p.getInventory().getStorageContents()));
      charges = List.of(new Journal.Charge(p.getUniqueId(), -amount));
    }
    final long claimed = amount;
    plugin.journal.execute(
        "CLAIM",
        changes,
        charges,
        Map.of("currency", currency.name(), "amount", amount),
        op -> {
          if (plugin.db.update(
                  "UPDATE wallets SET "
                      + column
                      + "="
                      + column
                      + "-? WHERE owner=? AND "
                      + column
                      + ">=?",
                  claimed,
                  p.getUniqueId().toString(),
                  claimed)
              != 1) throw new IllegalArgumentException("Proceeds already claimed");
          return null;
        });
    plugin.success(
        p,
        "Claimed "
            + (currency == Currency.EMERALDS
                ? amount + " emeralds"
                : moneyText(amount) + " Vault money"));
  }

  void claimDelivery(Player p, String id) {
    var row =
        plugin.db.one(
            "SELECT * FROM deliveries WHERE id=? AND owner=? AND claimed=0",
            id,
            p.getUniqueId().toString());
    if (row == null) throw new IllegalArgumentException("Delivery not available");
    var before = p.getInventory().getStorageContents();
    var after = Items.addAll(before, Items.decode(text(row, "items")));
    plugin.journal.execute(
        "DELIVERY",
        List.of(new Journal.Change(p, before, after)),
        List.of(),
        Map.of("delivery", id),
        op -> {
          if (plugin.db.update("UPDATE deliveries SET claimed=1 WHERE id=? AND claimed=0", id) != 1)
            throw new IllegalArgumentException("Delivery already claimed");
          return null;
        });
    plugin.success(p, "Items recovered");
  }
}
