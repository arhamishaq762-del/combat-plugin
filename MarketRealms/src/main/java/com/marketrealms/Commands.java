package com.marketrealms;

import static com.marketrealms.Domain.*;
import static com.marketrealms.Store.*;

import com.marketrealms.Domain.Currency;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

final class Commands implements CommandExecutor, TabCompleter {
  private final MarketRealmsPlugin plugin;

  Commands(MarketRealmsPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    try {
      if (cmd.getName().equals("marketadmin")) {
        admin(sender, args);
        return true;
      }
      if (!(sender instanceof Player p))
        throw new IllegalArgumentException("This command requires an in-game player");
      plugin.permit(p, cmd.getName().equals("trade") ? "trade" : "use");
      if (plugin.fatal)
        throw new IllegalArgumentException("Storage safety mode is active; ask an administrator");
      if (cmd.getName().equals("trade")) {
        plugin.trades.command(p, args);
        return true;
      }
      String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
      if (!Set.of("menu", "help", "exit", "info", "home").contains(sub))
        plugin.permit(
            p,
            sub.equals("rent")
                ? "rent"
                : sub.equals("renew")
                    ? "renew"
                    : sub.equals("designs")
                        ? "designs"
                        : sub.equals("restore")
                            ? "restore"
                            : sub.equals("category")
                                ? "category"
                                : sub.equals("bell")
                                    ? "bell"
                                    : sub.equals("shop")
                                        ? "shop"
                                        : sub.equals("sales") ? "sales" : "preview");
      switch (sub) {
        case "menu" -> plugin.menus.main(p);
        case "preview" -> {
          if (args.length > 1 && args[1].equals("next")) plugin.previews.next(p);
          else plugin.previews.enter(p, args.length > 1 ? args[1] : "");
        }
        case "exit" -> plugin.previews.exit(p);
        case "rent" -> {
          if (args.length >= 4) {
            String plot = args[1], plan = args[2];
            Currency c = Currency.parse(args[3]);
            Plan spec = Objects.requireNonNull(plugin.config.plans.get(plan), "Unknown plan");
            plugin.menus.confirm(
                p,
                "Rent " + plot,
                new String[] {
                  spec.name(),
                  spec.price().display(c),
                  "Duration: " + spec.durationMillis() / 86400000.0 + " days"
                },
                "rent",
                actor -> {
                  if (!Objects.equals(plugin.config.plans.get(plan), spec))
                    throw new IllegalArgumentException("Plan changed");
                  if (plugin.previews.inside(actor)) plugin.previews.exit(actor);
                  plugin.rentals.rent(actor, plot, plan, c);
                });
          } else plugin.menus.plots(p, 0);
        }
        case "renew" -> {
          if (args.length >= 3) {
            Plan plan = Objects.requireNonNull(plugin.config.plans.get(args[1]), "Unknown plan");
            Currency c = Currency.parse(args[2]);
            plugin.menus.confirm(
                p,
                "Renew",
                new String[] {plan.name(), plan.price().display(c)},
                "renew",
                actor -> {
                  if (!Objects.equals(plugin.config.plans.get(plan.id()), plan))
                    throw new IllegalArgumentException("Plan changed");
                  plugin.rentals.renew(actor, plan.id(), c);
                });
          } else plugin.menus.plans(p, "", true);
        }
        case "home" -> {
          if (plugin.previews.inside(p)) plugin.previews.exit(p);
          plugin.rentals.home(p);
        }
        case "info" -> plugin.rentals.info(p);
        case "category" -> {
          if (args.length > 1) plugin.rentals.category(p, args[1]);
          else plugin.menus.chooseCategory(p);
        }
        case "designs" -> {
          if (args.length > 2 && args[1].equals("preview")) plugin.previews.design(p, args[2]);
          else plugin.menus.designs(p, 0);
        }
        case "restore" -> {
          if (args.length > 1) {
            String id = args[1];
            plugin.menus.confirm(
                p,
                "Restore build",
                new String[] {id, "Empty destination; archive is consumed once"},
                "restore",
                actor -> plugin.archives.restore(actor, id));
          } else plugin.menus.archives(p);
        }
        case "bell" -> {
          if (args.length > 1 && args[1].equals("remove")) plugin.bells.remove(p);
          else plugin.bells.set(p);
        }
        case "shop" -> shop(p, args);
        case "sales" -> {
          if (args.length >= 3 && args[1].equals("claim")) {
            Currency c = Currency.parse(args[2]);
            int amount = args.length > 3 ? Integer.parseInt(args[3]) : 64;
            plugin.menus.confirm(
                p,
                "Claim proceeds",
                new String[] {c.name()},
                "sales",
                actor -> plugin.shops.claim(actor, c, amount));
          } else if (args.length >= 3 && args[1].equals("delivery")) {
            String id = args[2];
            plugin.menus.confirm(
                p,
                "Recover delivery",
                new String[] {id},
                "sales",
                actor -> plugin.shops.claimDelivery(actor, id));
          } else plugin.menus.sales(p);
        }
        case "help" -> help(p);
        default -> help(p);
      }
    } catch (Exception | LinkageError e) {
      if (sender instanceof Player p) plugin.handle(p, e);
      else {
        sender.sendMessage("MarketRealms: " + e.getMessage());
        if (e instanceof Store.StorageFailure) plugin.storageFailure(e);
      }
    }
    return true;
  }

  private Price prices(String e, String m) {
    Long emerald = e.equals("-") ? null : Long.parseLong(e);
    if (emerald != null && emerald > 1000000)
      throw new IllegalArgumentException("Emerald price exceeds limit");
    return new Price(emerald, m.equals("-") ? null : cents(m));
  }

  private void shop(Player p, String[] args) {
    String action = args.length < 2 ? "list" : args[1];
    switch (action) {
      case "list" -> {
        var rows =
            plugin.db.rows("SELECT * FROM listings WHERE owner=?", p.getUniqueId().toString());
        plugin.menus.listings(p, rows, true);
        for (var row : rows) plugin.info(p, text(row, "id") + " / " + text(row, "kind"));
      }
      case "create" -> {
        if (args.length != 6 || !Set.of("display", "container").contains(args[2]))
          throw new IllegalArgumentException(
              "/market shop create display|container <quantity> <emerald-price|-> <money-price|->");
        plugin.shops.create(
            p,
            args[2].toUpperCase(Locale.ROOT),
            Integer.parseInt(args[3]),
            prices(args[4], args[5]));
      }
      case "edit" -> {
        if (args.length != 6)
          throw new IllegalArgumentException(
              "/market shop edit <id> <quantity> <emerald-price|-> <money-price|->");
        plugin.shops.edit(p, args[2], Integer.parseInt(args[3]), prices(args[4], args[5]));
      }
      case "restock" -> {
        if (args.length != 4)
          throw new IllegalArgumentException("/market shop restock <id> <count>");
        plugin.shops.restock(p, args[2], Integer.parseInt(args[3]));
      }
      case "pause", "resume" -> {
        if (args.length != 3)
          throw new IllegalArgumentException("/market shop " + action + " <id>");
        plugin.shops.pause(p, args[2], action.equals("pause"));
      }
      case "remove" -> {
        if (args.length != 3) throw new IllegalArgumentException("/market shop remove <id>");
        String id = args[2];
        plugin.menus.confirm(
            p,
            "Remove listing",
            new String[] {id, "Display stock returns through deliveries"},
            "shop",
            actor -> plugin.shops.remove(actor, id));
      }
      default ->
          plugin.info(
              p, "shop create/edit/restock/pause/resume/remove/list; see README for exact syntax");
    }
  }

  private void help(Player p) {
    plugin.info(
        p,
        "/market preview [category|next], exit, rent [plot plan currency], renew [plan currency],"
            + " home, info, category [id], designs [preview id], restore [archive-id], bell"
            + " [remove], shop, sales. /trade <player>|accept|decline|cancel");
  }

  private String permission(String file) {
    return switch (file) {
      case "plots" -> "admin.plots";
      case "rental-plans" -> "admin.plans";
      case "categories" -> "admin.categories";
      case "designs" -> "admin.designs";
      case "recovery" -> "admin.archive";
      default -> "admin";
    };
  }

  private void admin(CommandSender sender, String[] args) throws Exception {
    if (args.length == 0) {
      sender.sendMessage(
          "/marketadmin reload | status | set <file> <path> <YAML-value> | plot create/remove |"
              + " spawn market/preview | world market/preview <name> | inspect rentals/listings |"
              + " archive list/info/export/retry/delete/restore | reset <plot> confirm | recovery"
              + " list/info/export/resolve/unlockplot");
      return;
    }
    String action = args[0];
    switch (action) {
      case "status" -> {
        permit(sender, "admin");
        sender.sendMessage(
            "Money: "
                + plugin.money.available()
                + "; database safety lock: "
                + plugin.fatal
                + "; active rentals: "
                + plugin.db.rows("SELECT id FROM rentals WHERE state='ACTIVE'").size());
      }
      case "reload" -> {
        permit(sender, "admin");
        plugin.reload();
        sender.sendMessage(
            "Validated configuration reloaded. World changes require a full restart.");
      }
      case "set" -> {
        if (args.length < 4 || !Configuration.FILES.contains(args[1]))
          throw new IllegalArgumentException(
              "/marketadmin set <file-without-yml> <path> <YAML-value>; then /marketadmin reload");
        permit(sender, permission(args[1]));
        File file = new File(plugin.getDataFolder(), args[1] + ".yml");
        YamlConfiguration y = new YamlConfiguration();
        y.load(file);
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(
            "value: " + String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
        y.set(args[2], parsed.get("value"));
        y.save(file);
        sender.sendMessage(
            "Saved configuration draft. /marketadmin reload validates/applies it; active state is"
                + " unchanged until then.");
      }
      case "world" -> {
        permit(sender, "admin");
        if (args.length != 3 || !Set.of("market", "preview").contains(args[1]))
          throw new IllegalArgumentException(
              "/marketadmin world market|preview <name> (restart required)");
        Domain.id(args[2]);
        var y = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        y.set("worlds." + (args[1].equals("market") ? "marketplace" : "preview"), args[2]);
        y.save(new File(plugin.getDataFolder(), "config.yml"));
        sender.sendMessage(
            "World saved. Restart to apply. Never change marketplace worlds while rentals exist.");
      }
      case "spawn" -> {
        permit(sender, "admin");
        Player p = player(sender);
        if (args.length != 2 || !Set.of("market", "preview").contains(args[1]))
          throw new IllegalArgumentException("/marketadmin spawn market|preview");
        World expected = args[1].equals("market") ? plugin.worlds.market : plugin.worlds.preview;
        if (p.getWorld() != expected)
          throw new IllegalArgumentException("Stand in the corresponding world");
        Location l = p.getLocation();
        var y = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        y.set(
            "worlds." + args[1] + "-spawn",
            List.of(l.getX(), l.getY(), l.getZ(), (double) l.getYaw(), (double) l.getPitch()));
        y.save(new File(plugin.getDataFolder(), "config.yml"));
        plugin.reload();
        sender.sendMessage("Spawn updated");
      }
      case "plot" -> {
        permit(sender, "admin.plots");
        Player p = player(sender);
        var y = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "plots.yml"));
        if (args.length == 3 && args[1].equals("remove")) {
          String id = args[2];
          if (!plugin.rentals.available(id))
            throw new IllegalArgumentException("Plot is occupied/locked");
          y.set("plots." + id, null);
        } else if (args.length == 8 && args[1].equals("create")) {
          if (p.getWorld() != plugin.worlds.market)
            throw new IllegalArgumentException(
                "Stand at the minimum corner in the marketplace world");
          String id = Domain.id(args[2]);
          if (plugin.config.plots.containsKey(id)) throw new IllegalArgumentException("ID exists");
          int w = Integer.parseInt(args[3]),
              h = Integer.parseInt(args[4]),
              d = Integer.parseInt(args[5]);
          if (w < 1 || h < 1 || d < 1)
            throw new IllegalArgumentException("Dimensions must be positive");
          Location l = p.getLocation();
          String root = "plots." + id + ".";
          y.set(root + "enabled", true);
          y.set(root + "name", id);
          y.set(root + "district", args[6]);
          y.set(root + "categories", List.of(args[7].split(",")));
          y.set(root + "min", List.of(l.getBlockX(), l.getBlockY(), l.getBlockZ()));
          y.set(
              root + "max",
              List.of(l.getBlockX() + w - 1, l.getBlockY() + h - 1, l.getBlockZ() + d - 1));
        } else
          throw new IllegalArgumentException(
              "/marketadmin plot create <id> <width> <height> <depth> <district> <category,...> |"
                  + " plot remove <id>");
        y.save(new File(plugin.getDataFolder(), "plots.yml"));
        plugin.reload();
        sender.sendMessage("Plot definition saved and validated");
      }
      case "shoprepair" -> {
        permit(sender, "admin");
        plugin.shops.repair();
        sender.sendMessage("Missing display entities repaired; loaded orphaned displays removed");
      }
      case "inspect" -> {
        permit(sender, "admin");
        if (args.length != 2 || !Set.of("rentals", "listings").contains(args[1]))
          throw new IllegalArgumentException("/marketadmin inspect rentals|listings");
        for (var r : plugin.db.rows("SELECT * FROM " + args[1] + " LIMIT 100"))
          sender.sendMessage(r.toString());
      }
      case "reset" -> {
        permit(sender, "admin.reset");
        if (args.length != 3 || !args[2].equals("confirm"))
          throw new IllegalArgumentException(
              "/marketadmin reset <plot> confirm — archive the current rental before reset");
        String id = args[1];
        var r = plugin.db.one("SELECT * FROM rentals WHERE plot=? AND state='ACTIVE'", id);
        if (r == null)
          throw new IllegalArgumentException(
              "Only occupied plots can be reset; no unsafe deletion of unowned public structures");
        plugin.db.transaction(
            () -> {
              plugin.db.update(
                  "UPDATE rentals SET expires=0,state='ARCHIVING' WHERE id=?", text(r, "id"));
              plugin.db.update("UPDATE plots SET state='ARCHIVING' WHERE id=?", id);
              plugin.db.update("UPDATE listings SET paused=1 WHERE plot=?", id);
              return null;
            });
        plugin.rentals.refresh();
        plugin.archives.expire(r);
        sender.sendMessage("Recovery archive started; reset occurs only after verification");
      }
      case "archive" -> {
        permit(sender, "admin.archive");
        if (args.length < 2)
          throw new IllegalArgumentException("archive list/info/export/retry/delete/restore");
        String sub = args[1];
        if (sub.equals("list")) {
          for (var r : plugin.db.rows("SELECT * FROM archives ORDER BY created DESC LIMIT 100"))
            sender.sendMessage(r.toString());
        } else {
          if (args.length < 3) throw new IllegalArgumentException("Supply archive ID");
          String id = args[2];
          var a = plugin.db.one("SELECT * FROM archives WHERE id=?", id);
          if (a == null) throw new IllegalArgumentException("Archive not found");
          switch (sub) {
            case "info" -> sender.sendMessage(a.toString());
            case "export" -> {
              plugin.archives.export(id);
              sender.sendMessage("Exported to plugins/MarketRealms/exports/" + id + ".mrzip");
            }
            case "retry" -> {
              permit(sender, "admin.recovery");
              plugin.archives.retry(id);
            }
            case "delete" -> {
              if (args.length != 4
                  || !args[3].equals("confirm-used")
                  || !text(a, "state").equals("USED"))
                throw new IllegalArgumentException(
                    "Only USED archives may be removed: archive delete <id> confirm-used");
              plugin.archives.export(id);
              plugin.db.update(
                  "UPDATE archives SET state='REMOVED' WHERE id=? AND state='USED'", id);
              Files.deleteIfExists(plugin.archives.path(id));
              sender.sendMessage("Removed used archive after verified export; metadata retained");
            }
            case "restore" -> {
              if (args.length != 4)
                throw new IllegalArgumentException("archive restore <archive-id> <online-owner>");
              Player owner = Bukkit.getPlayerExact(args[3]);
              if (owner == null || !owner.getUniqueId().toString().equals(text(a, "owner")))
                throw new IllegalArgumentException(
                    "Original owner must be online and rent an empty fitting plot");
              plugin.archives.restore(owner, id);
            }
            default -> throw new IllegalArgumentException("Unknown archive action");
          }
        }
      }
      case "recovery" -> {
        permit(sender, "admin.recovery");
        if (args.length < 2)
          throw new IllegalArgumentException("recovery list|info|export|resolve|unlockplot");
        String sub = args[1];
        if (sub.equals("list")) {
          for (var r :
              plugin.db.rows("SELECT id,kind,state,error FROM operations WHERE state='REVIEW'"))
            sender.sendMessage(r.toString());
        } else if (sub.equals("unlockplot")) {
          if (args.length != 4 || !args[3].equals("confirm-reconciled"))
            throw new IllegalArgumentException("recovery unlockplot <plot> confirm-reconciled");
          if (plugin.db.update(
                  "UPDATE plots SET state=CASE WHEN EXISTS(SELECT 1 FROM rentals WHERE"
                      + " plot=plots.id AND state='ACTIVE') THEN 'RENTED' ELSE 'AVAILABLE' END"
                      + " WHERE id=? AND state IN"
                      + " ('SHOP_REVIEW','DESIGN_REVIEW','OPERATION_REVIEW')",
                  args[2])
              != 1) throw new IllegalArgumentException("Plot is not an operation-review lock");
          sender.sendMessage("Plot unlocked after manual reconciliation");
        } else {
          if (args.length < 3) throw new IllegalArgumentException("Supply operation ID");
          String id = args[2];
          UUID.fromString(id);
          var r = plugin.db.one("SELECT * FROM operations WHERE id=?", id);
          if (r == null) throw new IllegalArgumentException("Unknown operation");
          if (sub.equals("info")) sender.sendMessage(r.toString());
          else if (sub.equals("export")) {
            Path exports = plugin.getDataFolder().toPath().resolve("exports");
            Files.createDirectories(exports);
            Files.writeString(exports.resolve(id + ".operation.yml"), text(r, "payload"));
            sender.sendMessage("Exported operation inventory images and external payment intent");
          } else if (sub.equals("resolve")) {
            if (args.length != 4)
              throw new IllegalArgumentException(
                  "recovery resolve <id> acknowledge-commit|acknowledge-rollback AFTER manual"
                      + " reconciliation");
            plugin.journal.resolve(id, args[3], sender);
            sender.sendMessage(
                "Review lock cleared. This command does NOT replay stock, payments or"
                    + " inventories.");
          } else throw new IllegalArgumentException("Unknown recovery action");
        }
      }
      default -> throw new IllegalArgumentException("Unknown administrator action");
    }
  }

  private Player player(CommandSender sender) {
    if (!(sender instanceof Player p))
      throw new IllegalArgumentException("Use this command in game");
    return p;
  }

  private void permit(CommandSender sender, String node) {
    if (!sender.hasPermission("marketrealms." + node))
      throw new IllegalArgumentException("Missing permission marketrealms." + node);
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command cmd, String label, String[] args) {
    List<String> choices = new ArrayList<>();
    if (args.length == 1) {
      if (cmd.getName().equals("trade")) {
        choices.addAll(List.of("accept", "decline", "cancel"));
        Bukkit.getOnlinePlayers().forEach(p -> choices.add(p.getName()));
      } else if (cmd.getName().equals("marketadmin")) {
        if (sender.hasPermission("marketrealms.admin"))
          choices.addAll(
              List.of(
                  "status",
                  "reload",
                  "set",
                  "world",
                  "spawn",
                  "plot",
                  "inspect",
                  "archive",
                  "reset",
                  "recovery"));
      } else
        choices.addAll(
            List.of(
                "preview",
                "exit",
                "rent",
                "renew",
                "home",
                "info",
                "category",
                "designs",
                "restore",
                "bell",
                "shop",
                "sales",
                "help"));
    } else if (cmd.getName().equals("marketadmin") && sender.hasPermission("marketrealms.admin")) {
      if (args.length == 2)
        switch (args[0]) {
          case "set" -> choices.addAll(Configuration.FILES);
          case "spawn", "world" -> choices.addAll(List.of("market", "preview"));
          case "plot" -> choices.addAll(List.of("create", "remove"));
          case "inspect" -> choices.addAll(List.of("rentals", "listings"));
          case "archive" ->
              choices.addAll(List.of("list", "info", "export", "retry", "delete", "restore"));
          case "recovery" ->
              choices.addAll(List.of("list", "info", "export", "resolve", "unlockplot"));
          case "reset" -> choices.addAll(plugin.config.plots.keySet());
        }
      else if (args[0].equals("set") && args.length == 3 && Configuration.FILES.contains(args[1]))
        choices.addAll(plugin.config.file(args[1]).getKeys(true));
    } else if (cmd.getName().equals("market")) {
      if (args.length == 2)
        switch (args[0]) {
          case "preview" -> {
            choices.add("next");
            choices.addAll(plugin.config.categories.keySet());
          }
          case "rent" -> choices.addAll(plugin.config.plots.keySet());
          case "renew" -> choices.addAll(plugin.config.plans.keySet());
          case "category" -> choices.addAll(plugin.config.categories.keySet());
          case "designs" -> choices.add("preview");
          case "bell" -> choices.addAll(List.of("set", "remove"));
          case "shop" ->
              choices.addAll(
                  List.of(
                      "create", "edit", "restock", "pause", "resume", "remove", "list", "help"));
          case "sales" -> choices.addAll(List.of("claim", "delivery"));
        }
      if (args.length == 3 && args[0].equals("rent")) choices.addAll(plugin.config.plans.keySet());
      if (args.length == 3 && args[0].equals("designs"))
        choices.addAll(plugin.config.designs.keySet());
      if (args.length == 3 && args[0].equals("shop") && args[1].equals("create"))
        choices.addAll(List.of("display", "container"));
      if (args.length == 4 && args[0].equals("rent")
          || args.length == 3 && args[0].equals("renew")
          || args.length == 3 && args[0].equals("sales"))
        choices.addAll(List.of("emeralds", "money"));
    }
    String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    return choices.stream()
        .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
        .distinct()
        .sorted()
        .toList();
  }
}
