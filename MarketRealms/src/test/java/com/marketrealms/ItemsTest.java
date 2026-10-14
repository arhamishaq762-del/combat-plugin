package com.marketrealms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class ItemsTest {
  private ItemStack stack(String identity, int amount, int maximum) {
    ItemStack item = mock(ItemStack.class);
    Material type = mock(Material.class);
    AtomicInteger count = new AtomicInteger(amount);
    when(type.isAir()).thenReturn(false);
    when(item.getType()).thenReturn(type);
    when(item.getAmount()).thenAnswer(i -> count.get());
    when(item.getMaxStackSize()).thenReturn(maximum);
    when(item.isSimilar(any(ItemStack.class)))
        .thenAnswer(i -> identity.equals(i.<ItemStack>getArgument(0).getTranslationKey()));
    when(item.getTranslationKey()).thenReturn(identity);
    doAnswer(
            i -> {
              count.set(i.getArgument(0));
              return null;
            })
        .when(item)
        .setAmount(anyInt());
    when(item.clone()).thenAnswer(i -> stack(identity, count.get(), maximum));
    return item;
  }

  @Test
  void removalAcrossStacksPreservesOriginal() {
    ItemStack template = stack("diamond", 1, 64);
    ItemStack[] before = {stack("diamond", 3, 64), stack("stone", 12, 64), stack("diamond", 6, 64)};
    ItemStack[] after = Items.remove(before, template, 7);
    assertNull(after[0]);
    assertEquals(2, after[2].getAmount());
    assertEquals(3, before[0].getAmount());
    assertEquals(6, before[2].getAmount());
    assertEquals(12, after[1].getAmount());
  }

  @Test
  void stockInsufficientBeforeMutation() {
    ItemStack template = stack("diamond", 1, 64);
    ItemStack[] before = {stack("diamond", 2, 64)};
    assertThrows(IllegalArgumentException.class, () -> Items.remove(before, template, 3));
    assertEquals(2, before[0].getAmount());
  }

  @Test
  void fullInventoryRejectsDelivery() {
    ItemStack[] before = {stack("stone", 64, 64)};
    assertThrows(
        IllegalArgumentException.class, () -> Items.add(before, stack("diamond", 1, 64), 1));
    assertEquals(64, before[0].getAmount());
  }

  @Test
  void fillsStacksThenEmptySlots() {
    ItemStack[] before = {stack("stone", 60, 64), null};
    ItemStack[] after = Items.add(before, stack("stone", 1, 64), 10);
    assertEquals(64, after[0].getAmount());
    assertEquals(6, after[1].getAmount());
    assertEquals(60, before[0].getAmount());
    assertNull(before[1]);
  }

  @Test
  void unstackableItemsNeedSeparateSlots() {
    ItemStack sword = stack("sword", 1, 1);
    assertThrows(IllegalArgumentException.class, () -> Items.add(new ItemStack[1], sword, 2));
    assertEquals(1, Items.add(new ItemStack[2], sword, 2)[1].getAmount());
  }

  @Test
  void malformedInventoryCodecRejected() {
    assertThrows(IllegalArgumentException.class, () -> Items.decode("notbase64"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Items.decode(java.util.Base64.getEncoder().encodeToString(new byte[] {0, 0, 2, 0})));
  }

  @Test
  void zeroQuantityDoesNotMintItems() {
    ItemStack[] before = new ItemStack[2];
    assertEquals(
        0, Items.count(Items.add(before, stack("diamond", 1, 64), 0), stack("diamond", 1, 64)));
  }
}
