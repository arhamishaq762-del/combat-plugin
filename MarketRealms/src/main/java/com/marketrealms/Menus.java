package com.marketrealms;

import static com.marketrealms.Domain.*;
import static com.marketrealms.Store.*;

import com.marketrealms.Domain.Currency;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

final class Menus implements Listener {
  static final class View implements InventoryHolder {
    final UUID owner;
    final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    final long expires = System.currentTimeMillis() + 300000;
    Inventory inventory;
    boolean once, used;

    View(Player p) {
      owner = p.getUniqueId();
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  private final MarketRealmsPlugin plugin;

  Menus(MarketRealmsPlugin plugin) {
    this.plugin = plugin;
  }

  static ItemStack icon(Material material, String name, String... lore) {
    ItemStack item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.displayName(Component.text(name));
    meta.lore(Arrays.stream(lore).map(Component::text).toList());
    item.setItemMeta(meta);
    return item;
  }

  private View view(Player p, String title) {
    View v = new View(p);
    v.inventory = Bukkit.createInventory(v, 54, Component.text(title));
    return v;
  }

  private void button(
      View v, int slot, ItemStack icon, String permission, Consumer<Player> action) {
    v.inventory.setItem(slot, icon);
    v.actions.put(
        slot,
        p -> {
          plugin.permit(p, permission);
          action.accept(p);
        });
  }

  private void show(Player p, View v) {
    p.openInventory(v.inventory);
  }

  void confirm(
      Player p, String title, String[] details, String permission, Consumer<Player> action) {
    View v = view(p, "Confirm: " + title);
    v.once = true;
    v.inventory.setItem(13, icon(Material.PAPER, title, details));
    button(
        v,
        30,
        icon(Material.LIME_WOOL, "CONFIRM", "Check the price and payment method above"),
        permission,
        actor -> {
          actor.closeInventory();
          action.accept(actor);
        });
    button(
        v,
        32,
        icon(Material.RED_WOOL, "Cancel", "Nothing will be charged"),
        "use",
        Player::closeInventory);
    show(p, v);
  }

  void main(Player p) {
    View v = view(p, "MarketRealms");
    button(
        v,
        10,
        icon(Material.ENDER_EYE, "Private 3D preview", "Walk around plots; right-click to inspect"),
        "preview",
        q -> plugin.previews.enter(q, ""));
    button(
        v,
        12,
        icon(Material.GRASS_BLOCK, "Rent a plot", "One active rental per player"),
        "rent",
        q -> plots(q, 0));
    button(v, 14, icon(Material.CLOCK, "Renew rental"), "renew", q -> plans(q, "", true));
    button(
        v,
        16,
        icon(Material.OAK_DOOR, "My plot"),
        "use",
        q -> {
          q.closeInventory();
          plugin.rentals.home(q);
        });
    button(
        v,
        28,
        icon(Material.BRICKS, "Shop designs", "Preview a premade shell or build custom"),
        "designs",
        q -> designs(q, 0));
    button(
        v,
        30,
        icon(Material.CHEST, "Shop listings", "Display and protected container shops"),
        "shop",
        q ->
            listings(
                q,
                plugin.db.rows("SELECT * FROM listings WHERE owner=?", q.getUniqueId().toString()),
                true));
    button(
        v,
        32,
        icon(Material.GOLD_INGOT, "Sales and proceeds", "Emerald and money balances are separate"),
        "sales",
        this::sales);
    button(
        v,
        34,
        icon(Material.STRUCTURE_BLOCK, "Restore build", "Unused archives are private to you"),
        "restore",
        this::archives);
    button(v, 49, icon(Material.COMPASS, "Browse categories"), "preview", this::categories);
    show(p, v);
  }

  void plots(Player p, int page) {
    View v = view(p, "Available rental plots");
    List<Plot> plots =
        plugin.config.plots.values().stream()
            .filter(plot -> plugin.rentals.available(plot.id()))
            .toList();
    for (int i = 0; i < 45 && page * 45 + i < plots.size(); i++) {
      Plot plot = plots.get(page * 45 + i);
      button(
          v,
          i,
          icon(
              Material.GRASS_BLOCK,
              plot.name(),
              "ID: " + plot.id(),
              "Dimensions: "
                  + plot.box().width()
                  + "×"
                  + plot.box().height()
                  + "×"
                  + plot.box().depth(),
              "District: " + plot.district(),
              "Categories: " + String.join(", ", plot.categories())),
          "rent",
          q -> plot(q, plot.id()));
    }
    if (page > 0) button(v, 45, icon(Material.ARROW, "Previous"), "rent", q -> plots(q, page - 1));
    if ((page + 1) * 45 < plots.size())
      button(v, 53, icon(Material.ARROW, "Next"), "rent", q -> plots(q, page + 1));
    show(p, v);
  }

  void plot(Player p, String id) {
    Plot plot = plugin.config.plots.get(id);
    if (plot == null) throw new IllegalArgumentException("Plot removed");
    View v = view(p, plot.name());
    v.inventory.setItem(
        13,
        icon(
            Material.PAPER,
            plot.name(),
            plot.id(),
            "Land only: build custom or purchase a design",
            "Dimensions: "
                + plot.box().width()
                + "×"
                + plot.box().height()
                + "×"
                + plot.box().depth(),
            "Allowed categories: " + String.join(", ", plot.categories()),
            "Availability: " + plugin.rentals.available(id)));
    button(v, 30, icon(Material.EMERALD, "Choose rental plan"), "rent", q -> plans(q, id, false));
    button(
        v,
        32,
        icon(Material.ENDER_EYE, "View in 3D"),
        "preview",
        q -> {
          q.closeInventory();
          plugin.previews.enter(q, plot.categories().stream().findFirst().orElse(""));
        });
    show(p, v);
  }

  void plans(Player p, String plot, boolean renew) {
    View v = view(p, renew ? "Renew: select duration" : "Rent: select duration");
    int slot = 0;
    for (Plan plan : plugin.config.plans.values())
      if (plan.enabled()) {
        button(
            v,
            slot++,
            icon(
                Material.CLOCK,
                plan.name(),
                "Duration: " + plan.durationMillis() / 86400000.0 + " days",
                "Offline time counts",
                "No cancellation refunds"),
            renew ? "renew" : "rent",
            q ->
                methods(
                    q,
                    renew ? "Renew " + plan.name() : "Rent " + plot + " / " + plan.name(),
                    plan.price(),
                    renew ? "renew" : "rent",
                    currency -> {
                      Plan current = plugin.config.plans.get(plan.id());
                      if (current == null
                          || !current.enabled()
                          || !current.price().equals(plan.price())
                          || current.durationMillis() != plan.durationMillis())
                        throw new IllegalArgumentException("Plan changed; select it again");
                      if (plugin.previews.inside(q)) plugin.previews.exit(q);
                      if (renew) plugin.rentals.renew(q, plan.id(), currency);
                      else plugin.rentals.rent(q, plot, plan.id(), currency);
                    }));
        if (slot >= 45) break;
      }
    show(p, v);
  }

  void methods(Player p, String title, Price price, String permission, Consumer<Currency> action) {
    View v = view(p, "Payment method: " + title);
    int slot = 20;
    for (Currency c : price.methods()) {
      if (c == Currency.MONEY
          && (!plugin.money.available()
              || !plugin.config.file("config").getBoolean("economy.money-enabled"))) continue;
      button(
          v,
          slot,
          icon(
              c == Currency.EMERALDS ? Material.EMERALD : Material.GOLD_INGOT,
              price.display(c),
              "Exact price; no currency conversion",
              "Click to review and confirm"),
          permission,
          q ->
              confirm(
                  q,
                  title,
                  new String[] {
                    "Price: " + price.display(c),
                    "Payment: " + c,
                    "This operation is not cancellable for a refund"
                  },
                  permission,
                  actor -> action.accept(c)));
      slot += 4;
    }
    if (v.actions.isEmpty())
      throw new IllegalArgumentException(
          "No available payment methods. Money requires Vault and an economy provider.");
    show(p, v);
  }

  void designs(Player p, int page) {
    View v = view(p, "Premade shop designs");
    List<Design> designs = plugin.config.designs.values().stream().filter(Design::enabled).toList();
    for (int i = 0; i < 45 && page * 45 + i < designs.size(); i++) {
      Design d = designs.get(page * 45 + i);
      button(
          v,
          i,
          icon(
              Material.BRICKS,
              d.name(),
              d.description(),
              d.width() + "×" + d.height() + "×" + d.depth(),
              "Must fit an empty rented plot"),
          "designs",
          q -> design(q, d.id()));
    }
    if (page > 0)
      button(v, 45, icon(Material.ARROW, "Previous"), "designs", q -> designs(q, page - 1));
    if ((page + 1) * 45 < designs.size())
      button(v, 53, icon(Material.ARROW, "Next"), "designs", q -> designs(q, page + 1));
    show(p, v);
  }

  void design(Player p, String id) {
    Design d = plugin.config.designs.get(id);
    if (d == null || !d.enabled()) throw new IllegalArgumentException("Design unavailable");
    View v = view(p, d.name());
    v.inventory.setItem(
        13,
        icon(
            Material.BRICKS,
            d.name(),
            d.description(),
            d.width() + "×" + d.height() + "×" + d.depth(),
            "Categories: " + d.categories()));
    button(
        v,
        30,
        icon(Material.ENDER_EYE, "Private 3D design preview"),
        "preview",
        q -> {
          q.closeInventory();
          plugin.previews.design(q, id);
        });
    button(
        v,
        32,
        icon(Material.EMERALD, "Buy design shell"),
        "designs",
        q ->
            methods(
                q,
                d.name(),
                d.price(),
                "designs",
                currency -> {
                  Design current = plugin.config.designs.get(id);
                  if (!Objects.equals(current, d))
                    throw new IllegalArgumentException("Design changed; select again");
                  if (plugin.previews.inside(q)) plugin.previews.exit(q);
                  plugin.designs.buy(q, id, currency);
                }));
    show(p, v);
  }

  void categories(Player p) {
    View v = view(p, "Category streets");
    int i = 0;
    for (Category c :
        plugin.config.categories.values().stream()
            .filter(Category::enabled)
            .sorted(Comparator.comparingInt(Category::order))
            .toList()) {
      if (i >= 45) break;
      button(
          v,
          i++,
          icon(
              Material.valueOf(c.icon()),
              c.name(),
              c.description(),
              "Preview plots in this category"),
          "preview",
          q -> {
            q.closeInventory();
            plugin.previews.enter(q, c.id());
          });
    }
    show(p, v);
  }

  void chooseCategory(Player p) {
    var rental = plugin.rentals.requireOwner(p);
    var plot = plugin.config.plots.get(text(rental, "plot"));
    View v = view(p, "Select shop category");
    int i = 0;
    for (String id : plot.categories()) {
      Category c = plugin.config.categories.get(id);
      if (c == null || !c.enabled()) continue;
      button(
          v,
          i++,
          icon(Material.valueOf(c.icon()), c.name(), c.description()),
          "category",
          q -> {
            plugin.rentals.category(q, id);
            q.closeInventory();
          });
    }
    show(p, v);
  }

  void listings(Player p, List<Map<String, Object>> rows, boolean manage) {
    View v = view(p, manage ? "Your shop listings" : "Container shop listings");
    int i = 0;
    for (var row : rows) {
      if (i >= 45) break;
      String id = text(row, "id");
      ItemStack item = plugin.shops.item(row).clone();
      var meta = item.getItemMeta();
      meta.displayName(Component.text(item.getType() + " ×" + number(row, "quantity")));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.text("ID: " + id));
      lore.add(Component.text("Method: " + text(row, "kind")));
      try {
        lore.add(Component.text("Stock: " + plugin.shops.stock(row)));
      } catch (RuntimeException e) {
        lore.add(Component.text("Stock unavailable"));
      }
      for (Currency c : plugin.shops.price(row).methods())
        lore.add(Component.text(plugin.shops.price(row).display(c)));
      meta.lore(lore);
      item.setItemMeta(meta);
      button(
          v,
          i++,
          item,
          manage ? "shop" : "use",
          q -> {
            if (manage) manage(q, id);
            else purchase(q, id);
          });
    }
    if (rows.size() > 45)
      plugin.info(p, "Showing first 45 listings. Full IDs remain available via /market shop list.");
    if (manage)
      button(
          v,
          49,
          icon(
              Material.PAPER,
              "Create or edit a listing",
              "Hold the sale item and use:",
              "/market shop create display <qty> <emerald-price|-> <money-price|->",
              "/market shop create container <qty> <emerald-price|-> <money-price|->"),
          "shop",
          q -> plugin.info(q, "Use /market shop help for all listing commands"));
    show(p, v);
  }

  private void manage(Player p, String id) {
    var r = plugin.shops.owned(p, id);
    View v = view(p, "Manage listing");
    v.inventory.setItem(13, plugin.shops.item(r));
    button(
        v,
        29,
        icon(Material.REDSTONE_TORCH, number(r, "paused") == 0 ? "Pause" : "Resume"),
        "shop",
        q -> {
          plugin.shops.pause(q, id, number(r, "paused") == 0);
          q.closeInventory();
        });
    button(
        v,
        31,
        icon(
            Material.CHEST,
            "Restock",
            "Display: /market shop restock " + id + " <count>",
            "Container: open your registered chest"),
        "shop",
        q -> plugin.info(q, "/market shop restock " + id + " <count>"));
    button(
        v,
        33,
        icon(Material.BARRIER, "Remove listing", "Display stock goes to persistent deliveries"),
        "shop",
        q ->
            confirm(
                q,
                "Remove listing",
                new String[] {id, "Display stock is recoverable; container items stay in place"},
                "shop",
                actor -> plugin.shops.remove(actor, id)));
    show(p, v);
  }

  void purchase(Player p, String id) {
    var r = plugin.shops.listing(id);
    plugin.shops.validate(r);
    plugin.shops.nearby(p, r);
    long quantity = number(r, "quantity");
    Price price = plugin.shops.price(r);
    View v = view(p, "Inspect purchase");
    ItemStack icon = plugin.shops.item(r).clone();
    var meta = icon.getItemMeta();
    meta.displayName(Component.text(icon.getType() + " ×" + quantity));
    meta.lore(
        List.of(
            Component.text("Stock: " + plugin.shops.stock(r)),
            Component.text("Owner: " + text(r, "owner")),
            Component.text("Plot: " + text(r, "plot"))));
    icon.setItemMeta(meta);
    v.inventory.setItem(13, icon);
    int slot = 29;
    for (Currency c : price.methods()) {
      if (c == Currency.MONEY
          && (!plugin.money.available()
              || !plugin.config.file("config").getBoolean("economy.money-enabled"))) continue;
      long amount = price.amount(c);
      button(
          v,
          slot,
          icon(
              c == Currency.EMERALDS ? Material.EMERALD : Material.GOLD_INGOT,
              price.display(c),
              "Purchase " + quantity + " matching items"),
          "use",
          q ->
              confirm(
                  q,
                  "Buy items",
                  new String[] {
                    quantity + " × " + plugin.shops.item(r).getType(),
                    price.display(c),
                    "Stock and capacity are revalidated"
                  },
                  "use",
                  actor -> plugin.shops.buy(actor, id, c, amount, quantity)));
      slot += 4;
    }
    show(p, v);
  }

  void sales(Player p) {
    View v = view(p, "Sales / separate claimable balances");
    var wallet = plugin.db.one("SELECT * FROM wallets WHERE owner=?", p.getUniqueId().toString());
    long emerald = wallet == null ? 0 : number(wallet, "emeralds"),
        money = wallet == null ? 0 : number(wallet, "money");
    button(
        v,
        10,
        icon(Material.EMERALD, "Emerald proceeds: " + emerald, "Claim up to 64 physical emeralds"),
        "sales",
        q ->
            confirm(
                q,
                "Claim emeralds",
                new String[] {"Up to 64 physical emeralds", "Requires inventory space"},
                "sales",
                actor -> plugin.shops.claim(actor, Currency.EMERALDS, 64)));
    button(
        v,
        12,
        icon(
            Material.GOLD_INGOT,
            "Money proceeds: " + moneyText(money),
            "Claim to your Vault account",
            "Not convertible to emeralds"),
        "sales",
        q ->
            confirm(
                q,
                "Claim money",
                new String[] {"Recorded Vault proceeds: " + moneyText(money)},
                "sales",
                actor -> plugin.shops.claim(actor, Currency.MONEY, 0)));
    button(v, 14, icon(Material.CHEST, "Recover delivered stock"), "sales", this::deliveries);
    int i = 18;
    for (var sale :
        plugin.db.rows(
            "SELECT * FROM sales WHERE seller=? ORDER BY created DESC LIMIT 27",
            p.getUniqueId().toString()))
      v.inventory.setItem(
          i++,
          icon(
              Material.PAPER,
              number(sale, "quantity") + " items sold",
              text(sale, "currency")
                  + ": "
                  + (text(sale, "currency").equals("MONEY")
                      ? moneyText(number(sale, "amount"))
                      : number(sale, "amount")),
              "Buyer UUID: " + text(sale, "buyer"),
              java.time.Instant.ofEpochMilli(number(sale, "created")).toString()));
    show(p, v);
  }

  void deliveries(Player p) {
    View v = view(p, "Recoverable stock deliveries");
    int i = 0;
    for (var row :
        plugin.db.rows(
            "SELECT * FROM deliveries WHERE owner=? AND claimed=0 LIMIT 45",
            p.getUniqueId().toString())) {
      String id = text(row, "id");
      button(
          v,
          i++,
          icon(Material.CHEST, id, "Requires room for the entire delivery"),
          "sales",
          q ->
              confirm(
                  q,
                  "Recover stock",
                  new String[] {id},
                  "sales",
                  actor -> plugin.shops.claimDelivery(actor, id)));
    }
    show(p, v);
  }

  void archives(Player p) {
    View v = view(p, "Your private recovery archives");
    int i = 0;
    for (var row :
        plugin.db.rows(
            "SELECT * FROM archives WHERE owner=? ORDER BY created DESC LIMIT 45",
            p.getUniqueId().toString())) {
      String id = text(row, "id");
      button(
          v,
          i++,
          icon(
              Material.STRUCTURE_BLOCK,
              "Build from " + text(row, "plot"),
              id,
              "Status: " + text(row, "state"),
              java.time.Instant.ofEpochMilli(number(row, "created")).toString()),
          "restore",
          q ->
              confirm(
                  q,
                  "Restore archived build",
                  new String[] {
                    id,
                    "Destination must fit and be completely empty",
                    "Successful restoration consumes the archive"
                  },
                  "restore",
                  actor -> plugin.archives.restore(actor, id)));
    }
    show(p, v);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(e.getInventory().getHolder() instanceof View view)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)
        || !p.getUniqueId().equals(view.owner)
        || view.expires < System.currentTimeMillis()
        || view.used) return;
    if (e.getRawSlot() < 0 || e.getRawSlot() >= view.inventory.getSize()) return;
    Consumer<Player> action = view.actions.get(e.getRawSlot());
    if (action == null) return;
    if (view.once) view.used = true;
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!p.isOnline()) return;
              try {
                if (plugin.fatal)
                  throw new IllegalArgumentException("MarketRealms is in storage safety mode");
                if (plugin.journal.locked(p.getUniqueId()))
                  throw new IllegalArgumentException("Pending recovery operation");
                action.accept(p);
              } catch (RuntimeException ex) {
                plugin.handle(p, ex);
              }
            });
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (e.getInventory().getHolder() instanceof View) e.setCancelled(true);
  }

  void closeAll() {
    for (Player p : Bukkit.getOnlinePlayers())
      if (p.getOpenInventory().getTopInventory().getHolder() instanceof View) p.closeInventory();
  }
}
