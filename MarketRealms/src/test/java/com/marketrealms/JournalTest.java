package com.marketrealms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {
  @TempDir Path dir;
  Store db;
  MarketRealmsPlugin plugin;

  @BeforeEach
  void setup() throws Exception {
    db = new Store(dir.resolve("market.db"));
    plugin = mock(MarketRealmsPlugin.class);
    plugin.db = db;
    when(plugin.getLogger()).thenReturn(Logger.getLogger("MarketRealms-test"));
    plugin.money = mock(Money.class);
  }

  @AfterEach
  void close() throws Exception {
    db.close();
  }

  @Test
  void preparedOperationSafelyRollsBackOnRestart() {
    db.update("INSERT INTO operations VALUES('id','RENT','PREPARED','',0,NULL)");
    new Journal(plugin);
    assertEquals(
        "ROLLED_BACK", Store.text(db.one("SELECT * FROM operations WHERE id='id'"), "state"));
  }

  @Test
  void applyingOperationLocksAccountAndPlotOnRestart() {
    UUID owner = UUID.randomUUID();
    db.update("INSERT INTO plots(id,definition) VALUES('p','p')");
    db.update(
        "INSERT INTO operations VALUES('id','RENT','APPLYING','details:\n  plot: p\n',0,NULL)");
    db.update("INSERT INTO participants VALUES('id',?)", owner.toString());
    Journal j = new Journal(plugin);
    assertTrue(j.locked(owner));
    assertEquals("REVIEW", Store.text(db.one("SELECT * FROM operations WHERE id='id'"), "state"));
    assertEquals(
        "OPERATION_REVIEW", Store.text(db.one("SELECT * FROM plots WHERE id='p'"), "state"));
  }

  @Test
  void confirmedOperationNeverReplayed() {
    UUID owner = UUID.randomUUID();
    db.update("INSERT INTO operations VALUES('id','PURCHASE','COMMITTED','',0,NULL)");
    db.update("INSERT INTO participants VALUES('id',?)", owner.toString());
    Journal j = new Journal(plugin);
    assertFalse(j.locked(owner));
    verifyNoInteractions(plugin.money);
  }

  @Test
  void openTradesCancelWithoutTakingAnyItems() {
    db.update("INSERT INTO trades VALUES('id','a','b','OPEN','',0)");
    new Journal(plugin);
    assertEquals("CANCELLED", Store.text(db.one("SELECT * FROM trades WHERE id='id'"), "state"));
  }

  @Test
  void sellerProceedsAreSeparateAndPersistOffline() {
    Journal j = new Journal(plugin);
    UUID seller = UUID.randomUUID();
    db.transaction(
        () -> {
          j.credit(seller, Domain.Currency.EMERALDS, 3);
          j.credit(seller, Domain.Currency.MONEY, 999);
          return null;
        });
    var row = db.one("SELECT * FROM wallets WHERE owner=?", seller.toString());
    assertEquals(3, Store.number(row, "emeralds"));
    assertEquals(999, Store.number(row, "money"));
    verifyNoInteractions(plugin.money);
  }

  @Test
  void resolvingReviewDoesNotReplayPayments() {
    UUID owner = UUID.randomUUID();
    db.update("INSERT INTO operations VALUES('id','CLAIM','REVIEW','',0,'uncertain')");
    db.update("INSERT INTO participants VALUES('id',?)", owner.toString());
    Journal j = new Journal(plugin);
    var admin = mock(org.bukkit.command.CommandSender.class);
    when(admin.getName()).thenReturn("Console");
    j.resolve("id", "acknowledge-rollback", admin);
    assertFalse(j.locked(owner));
    verifyNoInteractions(plugin.money);
  }

  @Test
  void invalidReviewDecisionLeavesLockIntact() {
    db.update("INSERT INTO operations VALUES('id','CLAIM','REVIEW','',0,'uncertain')");
    Journal j = new Journal(plugin);
    assertThrows(
        IllegalArgumentException.class,
        () -> j.resolve("id", "retry-charge", mock(org.bukkit.command.CommandSender.class)));
    assertEquals("REVIEW", Store.text(db.one("SELECT * FROM operations WHERE id='id'"), "state"));
  }

  private Player actor(UUID owner) {
    Player p = mock(Player.class);
    PlayerInventory inv = mock(PlayerInventory.class);
    when(p.getUniqueId()).thenReturn(owner);
    when(p.getInventory()).thenReturn(inv);
    when(inv.getStorageContents()).thenReturn(new ItemStack[36]);
    when(p.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
    when(plugin.getName()).thenReturn("MarketRealms");
    return p;
  }

  @Test
  void rejectedSqlWorkCompensatesKnownVaultWithdrawal() {
    UUID owner = UUID.randomUUID();
    Player p = actor(owner);
    Journal journal = new Journal(plugin);
    when(plugin.money.balance(owner)).thenReturn(10.0);
    when(plugin.money.transfer(owner, 100)).thenReturn(true);
    when(plugin.money.transfer(owner, -100)).thenReturn(true);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(p);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              journal.execute(
                  "RENT",
                  List.of(new Journal.Change(p, new ItemStack[36], new ItemStack[36])),
                  List.of(new Journal.Charge(owner, 100)),
                  Map.of(),
                  id -> {
                    throw new IllegalArgumentException("Assignment rejected");
                  }));
    }
    verify(plugin.money).transfer(owner, -100);
    assertEquals("ROLLED_BACK", Store.text(db.one("SELECT * FROM operations"), "state"));
    assertFalse(journal.locked(owner));
  }

  @Test
  void uncertainProviderFailureIsNeverBlindlyRetried() {
    UUID owner = UUID.randomUUID();
    Player p = actor(owner);
    Journal journal = new Journal(plugin);
    when(plugin.money.balance(owner)).thenReturn(10.0);
    when(plugin.money.transfer(owner, 100))
        .thenThrow(new IllegalStateException("Provider stopped after external effect"));
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(p);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              journal.execute(
                  "PURCHASE",
                  List.of(new Journal.Change(p, new ItemStack[36], new ItemStack[36])),
                  List.of(new Journal.Charge(owner, 100)),
                  Map.of(),
                  id -> null));
    }
    verify(plugin.money, never()).transfer(owner, -100);
    assertEquals("REVIEW", Store.text(db.one("SELECT * FROM operations"), "state"));
    assertTrue(journal.locked(owner));
  }

  @Test
  void insufficientMoneyCreatesNoOperationAndChargesNothing() {
    UUID owner = UUID.randomUUID();
    Player p = actor(owner);
    Journal journal = new Journal(plugin);
    when(plugin.money.balance(owner)).thenReturn(0.25);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(p);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              journal.execute(
                  "RENT",
                  List.of(new Journal.Change(p, new ItemStack[36], new ItemStack[36])),
                  List.of(new Journal.Charge(owner, 100)),
                  Map.of(),
                  id -> null));
    }
    verify(plugin.money, never()).transfer(any(), anyLong());
    assertTrue(db.rows("SELECT * FROM operations").isEmpty());
  }

  @Test
  void successfulVaultOperationCommitsOnlyOnce() {
    UUID owner = UUID.randomUUID();
    Player p = actor(owner);
    Journal journal = new Journal(plugin);
    when(plugin.money.balance(owner)).thenReturn(10.0);
    when(plugin.money.transfer(owner, 100)).thenReturn(true);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getPlayer(owner)).thenReturn(p);
      journal.execute(
          "PURCHASE",
          List.of(new Journal.Change(p, new ItemStack[36], new ItemStack[36])),
          List.of(new Journal.Charge(owner, 100)),
          Map.of(),
          id -> {
            journal.credit(owner, Domain.Currency.MONEY, 100);
            return null;
          });
    }
    assertEquals("COMMITTED", Store.text(db.one("SELECT * FROM operations"), "state"));
    verify(plugin.money, times(1)).transfer(owner, 100);
    assertEquals(100, Store.number(db.one("SELECT * FROM wallets"), "money"));
  }
}
