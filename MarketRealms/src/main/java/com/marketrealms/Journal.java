package com.marketrealms;

import static com.marketrealms.Store.*;

import java.util.*;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Durable intent, before/after inventory images and explicit uncertain external effects. */
final class Journal {
  record Change(Player player, ItemStack[] before, ItemStack[] after) {
    Change {
      before = Items.copy(before);
      after = Items.copy(after);
    }
  }

  record Charge(UUID owner, long cents) {}

  private final MarketRealmsPlugin plugin;
  private final Set<UUID> busy = new HashSet<>();
  private final Set<UUID> blocked = new HashSet<>();

  Journal(MarketRealmsPlugin plugin) {
    this.plugin = plugin;
    recoverStartup();
  }

  private void recoverStartup() {
    plugin.db.update(
        "UPDATE operations SET state='ROLLED_BACK', error='No effects started' WHERE"
            + " state='PREPARED'");
    plugin.db.update(
        "UPDATE operations SET state='REVIEW', error='Interrupted external/inventory operation;"
            + " inspect before/after images' WHERE state='APPLYING'");
    plugin.db.update("UPDATE trades SET state='CANCELLED' WHERE state='OPEN'");
    plugin.db.update("UPDATE trades SET state='REVIEW' WHERE state='FINALIZING'");
    reloadLocks();
    for (var operation : plugin.db.rows("SELECT payload FROM operations WHERE state='REVIEW'")) {
      try {
        YamlConfiguration data = new YamlConfiguration();
        data.loadFromString(text(operation, "payload"));
        String plot = data.getString("details.plot", "");
        if (plot.isEmpty()) {
          String listing = data.getString("details.listing", "");
          var row = plugin.db.one("SELECT plot FROM listings WHERE id=?", listing);
          if (row != null) plot = text(row, "plot");
        }
        if (!plot.isEmpty())
          plugin.db.update(
              "UPDATE plots SET state='OPERATION_REVIEW' WHERE id=? AND state IN"
                  + " ('AVAILABLE','RENTED','SHOP_REVIEW','DESIGN_REVIEW')",
              plot);
      } catch (Exception e) {
        throw new IllegalStateException("Cannot read recovery intent", e);
      }
    }
  }

  void reloadLocks() {
    blocked.clear();
    for (var row :
        plugin.db.rows(
            "SELECT player FROM participants JOIN operations ON operation=operations.id WHERE"
                + " state='REVIEW'")) blocked.add(UUID.fromString(text(row, "player")));
  }

  boolean locked(UUID player) {
    return busy.contains(player) || blocked.contains(player);
  }

  void require(Player player) {
    if (locked(player.getUniqueId()))
      throw new IllegalArgumentException(
          "Your account has a pending operation. Ask an administrator to inspect /marketadmin"
              + " recovery list.");
    if (plugin.previews != null && plugin.previews.inside(player))
      throw new IllegalArgumentException(
          "Leave preview mode before changing physical inventories or payments.");
  }

  String execute(
      String kind,
      List<Change> changes,
      List<Charge> charges,
      Map<String, Object> details,
      Function<String, Void> sqlWork) {
    LinkedHashSet<UUID> owners = new LinkedHashSet<>();
    changes.forEach(c -> owners.add(c.player().getUniqueId()));
    charges.forEach(c -> owners.add(c.owner()));
    for (UUID id : owners) {
      Player p = Bukkit.getPlayer(id);
      if (p == null) throw new IllegalArgumentException("Participant went offline");
      require(p);
    }
    for (Change c : changes)
      if (!Items.equal(c.before(), c.player().getInventory().getStorageContents()))
        throw new IllegalArgumentException("Inventory changed; please confirm again");
    for (Charge c : charges)
      if (c.cents() > 0 && plugin.money.balance(c.owner()) + 0.0000001 < c.cents() / 100.0)
        throw new IllegalArgumentException("Insufficient Vault money");
    String id = UUID.randomUUID().toString();
    YamlConfiguration data = new YamlConfiguration();
    details.forEach((k, v) -> data.set("details." + k, v));
    for (Change c : changes) {
      String key = c.player().getUniqueId().toString();
      data.set("players." + key + ".before", Items.encode(c.before()));
      data.set("players." + key + ".after", Items.encode(c.after()));
    }
    for (int i = 0; i < charges.size(); i++) {
      Charge c = charges.get(i);
      data.set("charges." + i + ".owner", c.owner().toString());
      data.set("charges." + i + ".cents", c.cents());
      data.set("charges." + i + ".balance-before", plugin.money.balance(c.owner()));
    }
    plugin.db.transaction(
        () -> {
          plugin.db.update(
              "INSERT INTO operations VALUES(?,?,'PREPARED',?,?,NULL)",
              id,
              kind,
              data.saveToString(),
              System.currentTimeMillis());
          for (UUID owner : owners)
            plugin.db.update("INSERT INTO participants VALUES(?,?)", id, owner.toString());
          return null;
        });
    busy.addAll(owners);
    List<Charge> paid = new ArrayList<>();
    boolean uncertain = false;
    boolean committed = false;
    try {
      plugin.db.update(
          "UPDATE operations SET state='APPLYING' WHERE id=? AND state='PREPARED'", id);
      for (Charge charge : charges) {
        boolean ok;
        try {
          ok = plugin.money.transfer(charge.owner(), charge.cents());
        } catch (RuntimeException e) {
          uncertain = true;
          throw e;
        }
        if (!ok) throw new IllegalArgumentException("Economy provider rejected payment");
        paid.add(charge);
      }
      for (Change change : changes) {
        change.player().getInventory().setStorageContents(Items.copy(change.after()));
        change
            .player()
            .getPersistentDataContainer()
            .set(new NamespacedKey(plugin, "last-operation"), PersistentDataType.STRING, id);
        change.player().saveData();
      }
      plugin.db.transaction(
          () -> {
            sqlWork.apply(id);
            if (plugin.db.update(
                    "UPDATE operations SET state='COMMITTED' WHERE id=? AND state='APPLYING'", id)
                != 1) throw new IllegalStateException("Operation state changed");
            return null;
          });
      committed = true;
      return id;
    } catch (RuntimeException e) {
      if (committed) throw e;
      if (e instanceof Store.StorageFailure) {
        uncertain = true;
        plugin.storageFailure(e);
      }
      boolean restored = !uncertain;
      if (!uncertain) {
        try {
          for (Change change : changes) {
            change.player().getInventory().setStorageContents(Items.copy(change.before()));
            change.player().saveData();
          }
        } catch (RuntimeException rollback) {
          restored = false;
          plugin
              .getLogger()
              .severe("Inventory rollback needs review for " + id + ": " + rollback.getMessage());
        }
        for (Charge charge : paid)
          try {
            if (!plugin.money.transfer(charge.owner(), -charge.cents())) restored = false;
          } catch (RuntimeException rollback) {
            restored = false;
          }
      }
      try {
        plugin.db.update(
            "UPDATE operations SET state=?,error=? WHERE id=?",
            restored ? "ROLLED_BACK" : "REVIEW",
            e.getMessage(),
            id);
      } catch (RuntimeException storage) {
        restored = false;
        plugin
            .getLogger()
            .severe("Unable to finalize rollback " + id + ": " + storage.getMessage());
      }
      if (!restored) {
        blocked.addAll(owners);
        plugin
            .getLogger()
            .severe(
                "Operation "
                    + id
                    + " requires review; involved accounts are locked. No blind replay was"
                    + " attempted.");
      }
      throw new IllegalArgumentException(e.getMessage() + " [operation " + id + "]", e);
    } finally {
      busy.removeAll(owners);
    }
  }

  List<Change> payment(Player player, Domain.Currency currency, long amount) {
    require(player);
    if (currency == Domain.Currency.MONEY) {
      plugin.money.require();
      return List.of(
          new Change(
              player,
              player.getInventory().getStorageContents(),
              player.getInventory().getStorageContents()));
    }
    if (amount > Integer.MAX_VALUE) throw new IllegalArgumentException("Price too high");
    var before = player.getInventory().getStorageContents();
    return List.of(new Change(player, before, Items.remove(before, Items.emerald(), (int) amount)));
  }

  List<Charge> charges(Player player, Domain.Currency currency, long amount) {
    return currency == Domain.Currency.MONEY
        ? List.of(new Charge(player.getUniqueId(), amount))
        : List.of();
  }

  void credit(UUID owner, Domain.Currency currency, long amount) {
    String column = currency == Domain.Currency.EMERALDS ? "emeralds" : "money";
    plugin.db.update("INSERT OR IGNORE INTO wallets(owner) VALUES(?)", owner.toString());
    plugin.db.update(
        "UPDATE wallets SET " + column + "=" + column + "+? WHERE owner=?",
        amount,
        owner.toString());
  }

  void resolve(String id, String mode, org.bukkit.command.CommandSender admin) {
    var operation = plugin.db.one("SELECT * FROM operations WHERE id=? AND state='REVIEW'", id);
    if (operation == null) throw new IllegalArgumentException("No review operation with that ID");
    // Never guess external effects. Admin must reconcile provider ledger and physically saved
    // inventories.
    if (!Set.of("acknowledge-commit", "acknowledge-rollback").contains(mode))
      throw new IllegalArgumentException(
          "Use acknowledge-commit or acknowledge-rollback AFTER manual reconciliation");
    plugin.db.update(
        "UPDATE operations SET state=?,error=? WHERE id=? AND state='REVIEW'",
        mode.equals("acknowledge-commit") ? "MANUAL_COMMIT" : "MANUAL_ROLLBACK",
        "Reconciled by " + admin.getName(),
        id);
    reloadLocks();
  }
}
