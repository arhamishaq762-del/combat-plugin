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
}
