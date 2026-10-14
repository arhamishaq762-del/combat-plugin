package com.marketrealms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import org.bukkit.Location;

final class Domain {
  enum Currency {
    EMERALDS,
    MONEY;

    static Currency parse(String text) {
      return switch (text.toLowerCase(Locale.ROOT)) {
        case "emerald", "emeralds" -> EMERALDS;
        case "money", "vault" -> MONEY;
        default -> throw new IllegalArgumentException("Choose emeralds or money");
      };
    }
  }

  record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
    Box {
      if (x1 > x2 || y1 > y2 || z1 > z2)
        throw new IllegalArgumentException("Plot minimum must not exceed maximum");
    }

    int width() {
      return x2 - x1 + 1;
    }

    int height() {
      return y2 - y1 + 1;
    }

    int depth() {
      return z2 - z1 + 1;
    }

    long volume() {
      return (long) width() * height() * depth();
    }

    boolean contains(int x, int y, int z) {
      return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    boolean contains(Location l) {
      return contains(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    boolean intersects(Box b) {
      return x1 <= b.x2 && x2 >= b.x1 && y1 <= b.y2 && y2 >= b.y1 && z1 <= b.z2 && z2 >= b.z1;
    }

    boolean fits(int w, int h, int d) {
      return w <= width() && h <= height() && d <= depth();
    }
  }

  record Price(Long emeralds, Long money) {
    Price {
      if ((emeralds == null && money == null)
          || (emeralds != null && emeralds <= 0)
          || (money != null && money <= 0))
        throw new IllegalArgumentException(
            "At least one positive price is required; '-' disables a currency");
    }

    long amount(Currency c) {
      Long n = c == Currency.EMERALDS ? emeralds : money;
      if (n == null) throw new IllegalArgumentException("This currency is not accepted");
      return n;
    }

    String display(Currency c) {
      return c == Currency.EMERALDS ? amount(c) + " emeralds" : moneyText(amount(c)) + " money";
    }

    List<Currency> methods() {
      List<Currency> c = new ArrayList<>();
      if (emeralds != null) c.add(Currency.EMERALDS);
      if (money != null) c.add(Currency.MONEY);
      return c;
    }
  }

  record Plan(String id, String name, long durationMillis, Price price, boolean enabled) {}

  record Category(
      String id, String name, String description, String icon, int order, boolean enabled) {}

  record Plot(
      String id, String name, String district, Set<String> categories, Box box, boolean enabled) {}

  record Design(
      String id,
      String name,
      String description,
      int width,
      int height,
      int depth,
      Set<String> categories,
      Price price,
      boolean enabled,
      String floor,
      String wall,
      String roof,
      String window) {}

  static long cents(String text) {
    try {
      long n =
          new BigDecimal(text)
              .setScale(2, RoundingMode.UNNECESSARY)
              .movePointRight(2)
              .longValueExact();
      if (n < 0 || n > 1_000_000_000L)
        throw new IllegalArgumentException("Money must be 0..10000000.00");
      return n;
    } catch (ArithmeticException | NumberFormatException e) {
      throw new IllegalArgumentException("Money must have at most two decimals and be in range", e);
    }
  }

  static String moneyText(long cents) {
    return BigDecimal.valueOf(cents, 2).toPlainString();
  }

  static String remaining(long expiry, long now) {
    Duration d = Duration.ofMillis(Math.max(0, expiry - now));
    return d.toDays() + "d " + d.toHoursPart() + "h " + d.toMinutesPart() + "m";
  }

  static String id(String s) {
    if (!s.matches("[a-z0-9_-]{1,48}"))
      throw new IllegalArgumentException(
          "IDs must use 1..48 lowercase letters, digits, underscores or hyphens");
    return s;
  }

  static long renewedExpiry(long expiry, long now, long duration) {
    if (expiry <= now) throw new IllegalArgumentException("Rental has already expired");
    return Math.addExact(expiry, duration);
  }
}
