package com.marketrealms;

import static com.marketrealms.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DomainTest {
  @Test
  void centsAreExact() {
    assertEquals(29, cents("0.29"));
    assertEquals(10001, cents("100.01"));
    assertEquals("100.01", moneyText(10001));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "1.001", "NaN", "Infinity", "10000000.01", "99999999999999999"})
  void invalidMoneyRejected(String value) {
    assertThrows(IllegalArgumentException.class, () -> cents(value));
  }

  @Test
  void dualPricesAreIndependent() {
    Price price = new Price(7L, 999L);
    assertEquals(7, price.amount(Currency.EMERALDS));
    assertEquals(999, price.amount(Currency.MONEY));
  }

  @Test
  void unsupportedCurrencyDoesNotConvert() {
    Price p = new Price(2L, null);
    assertThrows(IllegalArgumentException.class, () -> p.amount(Currency.MONEY));
  }

  @Test
  void pricesMustBePositive() {
    assertThrows(IllegalArgumentException.class, () -> new Price(0L, null));
    assertThrows(IllegalArgumentException.class, () -> new Price(null, -1L));
    assertThrows(IllegalArgumentException.class, () -> new Price(null, null));
  }

  @Test
  void inclusivePlotBoundaries() {
    Box b = new Box(1, 65, 1, 16, 80, 16);
    assertTrue(b.contains(1, 65, 1));
    assertTrue(b.contains(16, 80, 16));
    assertFalse(b.contains(17, 80, 16));
    assertFalse(b.contains(1, 64, 1));
    assertEquals(4096, b.volume());
  }

  @Test
  void plotsCannotOverlap() {
    Box a = new Box(0, 0, 0, 15, 15, 15);
    assertTrue(a.intersects(new Box(15, 0, 0, 30, 15, 15)));
    assertFalse(a.intersects(new Box(16, 0, 0, 31, 15, 15)));
  }

  @Test
  void buildMustFitEveryDimension() {
    Box b = new Box(0, 0, 0, 7, 7, 7);
    assertTrue(b.fits(8, 8, 8));
    assertFalse(b.fits(9, 8, 8));
    assertFalse(b.fits(8, 9, 8));
  }

  @Test
  void renewalExtendsExpiryNotNow() {
    assertEquals(16000, renewedExpiry(10000, 2000, 6000));
    assertThrows(IllegalArgumentException.class, () -> renewedExpiry(2000, 2000, 6000));
  }

  @Test
  void offlineClockUsesAbsoluteTime() {
    assertEquals("0d 23h 0m", remaining(86400000, 3600000));
    assertEquals("0d 0h 0m", remaining(100, 200));
  }

  @ParameterizedTest
  @ValueSource(strings = {"../escape", "ABC", "a.b", "", "a/b"})
  void idsRejectUnsafePaths(String s) {
    assertThrows(IllegalArgumentException.class, () -> id(s));
  }

  @Test
  void designShellWithinDeclaredDimensions() {
    Design d =
        new Design(
            "stall",
            "Stall",
            "",
            7,
            5,
            7,
            java.util.Set.of(),
            new Price(1L, null),
            true,
            "stone",
            "stone",
            "stone",
            "glass");
    for (var block : Designs.shape(d)) {
      assertTrue(Blocks.integer(block, "x") < 7);
      assertTrue(Blocks.integer(block, "y") < 5);
      assertTrue(Blocks.integer(block, "z") < 7);
    }
    assertTrue(
        Designs.shape(d).stream()
            .noneMatch(
                b ->
                    Blocks.integer(b, "z") == 0
                        && Blocks.integer(b, "x") == 3
                        && Blocks.integer(b, "y") == 1));
  }
}
