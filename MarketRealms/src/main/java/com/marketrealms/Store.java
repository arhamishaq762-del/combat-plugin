package com.marketrealms;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** A single connection, confined to the server thread. WAL + FULL synchronous journals. */
final class Store implements AutoCloseable {
  private final Connection connection;
  private final Thread owner = Thread.currentThread();

  Store(Path file) throws Exception {
    Files.createDirectories(file.getParent());
    Class.forName("org.sqlite.JDBC");
    connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
    try (Statement s = connection.createStatement()) {
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA synchronous=FULL");
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA busy_timeout=5000");
    }
    migrate();
  }

  private void check() {
    if (Thread.currentThread() != owner)
      throw new IllegalStateException("Database must be used on its owning server thread");
  }

  int update(String sql, Object... args) {
    check();
    try (var s = connection.prepareStatement(sql)) {
      bind(s, args);
      return s.executeUpdate();
    } catch (SQLException e) {
      throw new StorageFailure(sql, e);
    }
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    check();
    try (var s = connection.prepareStatement(sql)) {
      bind(s, args);
      try (var r = s.executeQuery()) {
        List<Map<String, Object>> result = new ArrayList<>();
        var md = r.getMetaData();
        while (r.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= md.getColumnCount(); i++)
            row.put(md.getColumnLabel(i), r.getObject(i));
          result.add(row);
        }
        return result;
      }
    } catch (SQLException e) {
      throw new StorageFailure(sql, e);
    }
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = rows(sql, args);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  <T> T transaction(java.util.concurrent.Callable<T> work) {
    check();
    try {
      if (!connection.getAutoCommit()) return work.call();
      connection.setAutoCommit(false);
      try {
        T result = work.call();
        connection.commit();
        return result;
      } catch (Exception e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (Exception e) {
      if (e instanceof RuntimeException r) throw r;
      throw new StorageFailure("transaction", e);
    }
  }

  private void migrate() {
    int version =
        ((Number) Objects.requireNonNull(one("PRAGMA user_version")).get("user_version"))
            .intValue();
    if (version > 2) throw new IllegalStateException("Database schema is newer than this plugin");
    if (version == 0)
      transaction(
          () -> {
            for (String ddl : SCHEMA) update(ddl);
            update("PRAGMA user_version=1");
            return null;
          });
    if (version < 2)
      transaction(
          () -> {
            update("ALTER TABLE archives ADD COLUMN used_at INTEGER");
            update("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            update("PRAGMA user_version=2");
            return null;
          });
  }

  private static void bind(PreparedStatement s, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
  }

  static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  static long number(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? 0 : ((Number) value).longValue();
  }

  @Override
  public void close() throws SQLException {
    check();
    connection.close();
  }

  static final class StorageFailure extends RuntimeException {
    StorageFailure(String operation, Throwable cause) {
      super("Storage failure during " + operation, cause);
    }
  }

  private static final List<String> SCHEMA =
      List.of(
          "CREATE TABLE plots(id TEXT PRIMARY KEY, definition TEXT NOT NULL, state TEXT NOT NULL"
              + " DEFAULT 'AVAILABLE')",
          "CREATE TABLE rentals(id TEXT PRIMARY KEY, owner TEXT NOT NULL, plot TEXT NOT NULL"
              + " REFERENCES plots(id), plan TEXT NOT NULL, category TEXT NOT NULL, expires INTEGER"
              + " NOT NULL, state TEXT NOT NULL)",
          "CREATE UNIQUE INDEX one_owner ON rentals(owner) WHERE state IN"
              + " ('ACTIVE','ARCHIVING','RESETTING')",
          "CREATE UNIQUE INDEX one_plot ON rentals(plot) WHERE state IN"
              + " ('ACTIVE','ARCHIVING','RESETTING')",
          "CREATE TABLE operations(id TEXT PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL,"
              + " payload TEXT NOT NULL, created INTEGER NOT NULL, error TEXT)",
          "CREATE TABLE participants(operation TEXT NOT NULL REFERENCES operations(id), player TEXT"
              + " NOT NULL, PRIMARY KEY(operation,player))",
          "CREATE TABLE wallets(owner TEXT PRIMARY KEY, emeralds INTEGER NOT NULL DEFAULT 0"
              + " CHECK(emeralds>=0), money INTEGER NOT NULL DEFAULT 0 CHECK(money>=0))",
          "CREATE TABLE listings(id TEXT PRIMARY KEY, owner TEXT NOT NULL, rental TEXT NOT NULL"
              + " REFERENCES rentals(id), plot TEXT NOT NULL, kind TEXT NOT NULL, item TEXT NOT"
              + " NULL, quantity INTEGER NOT NULL CHECK(quantity>0), emeralds INTEGER, money"
              + " INTEGER, stock INTEGER NOT NULL DEFAULT 0 CHECK(stock>=0), paused INTEGER NOT"
              + " NULL DEFAULT 0, x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL, display"
              + " TEXT, interaction TEXT)",
          "CREATE TABLE sales(id TEXT PRIMARY KEY, listing TEXT NOT NULL, seller TEXT NOT NULL,"
              + " buyer TEXT NOT NULL, quantity INTEGER NOT NULL, currency TEXT NOT NULL, amount"
              + " INTEGER NOT NULL, created INTEGER NOT NULL)",
          "CREATE TABLE deliveries(id TEXT PRIMARY KEY, owner TEXT NOT NULL, items TEXT NOT NULL,"
              + " claimed INTEGER NOT NULL DEFAULT 0)",
          "CREATE TABLE archives(id TEXT PRIMARY KEY, owner TEXT NOT NULL, rental TEXT NOT NULL,"
              + " plot TEXT NOT NULL, path TEXT NOT NULL, checksum TEXT, state TEXT NOT NULL,"
              + " created INTEGER NOT NULL, destination TEXT, error TEXT)",
          "CREATE TABLE bells(plot TEXT PRIMARY KEY, owner TEXT NOT NULL, x INTEGER NOT NULL,y"
              + " INTEGER NOT NULL,z INTEGER NOT NULL)",
          "CREATE TABLE notifications(id TEXT PRIMARY KEY, owner TEXT NOT NULL, message TEXT NOT"
              + " NULL, created INTEGER NOT NULL)",
          "CREATE TABLE warnings(rental TEXT NOT NULL, hours INTEGER NOT NULL, PRIMARY"
              + " KEY(rental,hours))",
          "CREATE TABLE trades(id TEXT PRIMARY KEY, first TEXT NOT NULL, second TEXT NOT NULL,"
              + " state TEXT NOT NULL, payload TEXT NOT NULL, created INTEGER NOT NULL)");
}
