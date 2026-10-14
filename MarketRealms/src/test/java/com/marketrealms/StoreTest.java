package com.marketrealms;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {
  @TempDir Path dir;
  Store db;

  @BeforeEach
  void setup() throws Exception {
    db = new Store(dir.resolve("test.db"));
  }

  @AfterEach
  void close() throws Exception {
    db.close();
  }

  private void plot(String id) {
    db.update("INSERT INTO plots(id,definition) VALUES(?,?)", id, id);
  }

  private void rent(String id, String owner, String plot) {
    db.update(
        "INSERT INTO rentals VALUES(?,?,?,'weekly','general',9999999999999,'ACTIVE')",
        id,
        owner,
        plot);
  }

  @Test
  void schemaVersionPersisted() {
    assertEquals(2, Store.number(db.one("PRAGMA user_version"), "user_version"));
  }

  @Test
  void oneActiveRentalPerOwner() {
    plot("p1");
    plot("p2");
    rent("r1", "owner", "p1");
    assertThrows(Store.StorageFailure.class, () -> rent("r2", "owner", "p2"));
  }

  @Test
  void oneConcurrentRenterPerPlot() {
    plot("p1");
    rent("r1", "owner1", "p1");
    assertThrows(Store.StorageFailure.class, () -> rent("r2", "owner2", "p1"));
  }

  @Test
  void failedRentalTransactionRollsBackAssignment() {
    plot("p1");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            db.transaction(
                () -> {
                  db.update("UPDATE plots SET state='RENTED' WHERE id='p1'");
                  throw new IllegalArgumentException("Payment failed");
                }));
    assertEquals("AVAILABLE", Store.text(db.one("SELECT * FROM plots WHERE id='p1'"), "state"));
  }

  @Test
  void stockAndPaymentPersistTogether() {
    plot("p");
    rent("r", "seller", "p");
    db.update(
        "INSERT INTO listings(id,owner,rental,plot,kind,item,quantity,stock,x,y,z)"
            + " VALUES('l','seller','r','p','DISPLAY','item',1,10,0,65,0)");
    assertThrows(
        IllegalStateException.class,
        () ->
            db.transaction(
                () -> {
                  db.update("UPDATE listings SET stock=stock-1 WHERE id='l'");
                  db.update("INSERT INTO wallets VALUES('seller',3,0)");
                  throw new IllegalStateException("Interrupted before commit");
                }));
    assertEquals(10, Store.number(db.one("SELECT * FROM listings WHERE id='l'"), "stock"));
    assertNull(db.one("SELECT * FROM wallets WHERE owner='seller'"));
  }

  @Test
  void negativeWalletCannotBeCreated() {
    assertThrows(
        Store.StorageFailure.class, () -> db.update("INSERT INTO wallets VALUES('seller',-1,0)"));
  }

  @Test
  void transactionIdentifiersAreUnique() {
    db.update("INSERT INTO operations VALUES('id','RENT','COMMITTED','',0,NULL)");
    assertThrows(
        Store.StorageFailure.class,
        () -> db.update("INSERT INTO operations VALUES('id','RENT','COMMITTED','',0,NULL)"));
  }

  @Test
  void consumedArchiveCannotBeClaimedAgain() {
    db.update(
        "INSERT INTO archives(id,owner,rental,plot,path,state,created)"
            + " VALUES('a','owner','r','p','a','AVAILABLE',0)");
    assertEquals(
        1, db.update("UPDATE archives SET state='RESTORING' WHERE id='a' AND state='AVAILABLE'"));
    assertEquals(
        0, db.update("UPDATE archives SET state='RESTORING' WHERE id='a' AND state='AVAILABLE'"));
  }

  @Test
  void restartKeepsRentalAndProceeds() throws Exception {
    plot("p");
    rent("r", "owner", "p");
    db.update("INSERT INTO wallets VALUES('seller',9,101)");
    db.close();
    db = new Store(dir.resolve("test.db"));
    assertEquals("owner", Store.text(db.one("SELECT * FROM rentals WHERE id='r'"), "owner"));
    assertEquals(101, Store.number(db.one("SELECT * FROM wallets WHERE owner='seller'"), "money"));
  }

  @Test
  void threadConfinementEnforced() throws Exception {
    var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread t =
        new Thread(
            () -> {
              try {
                db.rows("SELECT 1");
              } catch (Throwable e) {
                error.set(e);
              }
            });
    t.start();
    t.join();
    assertInstanceOf(IllegalStateException.class, error.get());
  }
}
