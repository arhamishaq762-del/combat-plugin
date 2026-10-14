package com.marketrealms;

import java.io.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

final class Items {
  private static final int LIMIT = 4 * 1024 * 1024;

  static String encode(ItemStack[] stacks) {
    try (var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes)) {
      out.writeInt(stacks.length);
      for (ItemStack stack : stacks) {
        if (stack == null || stack.getType().isAir()) {
          out.writeInt(0);
          continue;
        }
        byte[] item = stack.serializeAsBytes();
        if (item.length > LIMIT) throw new IllegalArgumentException("Item data too large");
        out.writeInt(item.length);
        out.write(item);
      }
      return Base64.getEncoder().encodeToString(bytes.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static ItemStack[] decode(String encoded) {
    try (var in =
        new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
      int count = in.readInt();
      if (count < 0 || count > 256) throw new IOException("Invalid inventory size");
      ItemStack[] items = new ItemStack[count];
      int total = 0;
      for (int i = 0; i < count; i++) {
        int length = in.readInt();
        total += length;
        if (length < 0 || length > LIMIT || total > LIMIT * 2)
          throw new IOException("Invalid item length");
        if (length > 0) {
          byte[] data = in.readNBytes(length);
          if (data.length != length) throw new EOFException();
          items[i] = ItemStack.deserializeBytes(data);
        }
      }
      if (in.read() != -1) throw new IOException("Trailing inventory bytes");
      return items;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid serialized item inventory", e);
    }
  }

  static ItemStack[] copy(ItemStack[] items) {
    return Arrays.stream(items).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new);
  }

  static ItemStack template(ItemStack item) {
    if (item == null || item.getType().isAir())
      throw new IllegalArgumentException("Hold an item first");
    ItemStack result = item.clone();
    result.setAmount(1);
    return result;
  }

  static int count(ItemStack[] inventory, ItemStack item) {
    int count = 0;
    for (ItemStack stack : inventory)
      if (stack != null && stack.isSimilar(item)) count = Math.addExact(count, stack.getAmount());
    return count;
  }

  static ItemStack[] remove(ItemStack[] inventory, ItemStack item, int amount) {
    if (amount < 0 || count(inventory, item) < amount)
      throw new IllegalArgumentException("Not enough " + item.getType());
    ItemStack[] after = copy(inventory);
    for (int i = 0; i < after.length && amount > 0; i++) {
      ItemStack stack = after[i];
      if (stack == null || !stack.isSimilar(item)) continue;
      int take = Math.min(amount, stack.getAmount());
      stack.setAmount(stack.getAmount() - take);
      amount -= take;
      if (stack.getAmount() == 0) after[i] = null;
    }
    return after;
  }

  static ItemStack[] add(ItemStack[] inventory, ItemStack item, int amount) {
    if (amount < 0) throw new IllegalArgumentException("Invalid quantity");
    ItemStack[] after = copy(inventory);
    for (int i = 0; i < after.length && amount > 0; i++) {
      ItemStack stack = after[i];
      if (stack == null || !stack.isSimilar(item)) continue;
      int add = Math.min(amount, Math.max(0, item.getMaxStackSize() - stack.getAmount()));
      stack.setAmount(stack.getAmount() + add);
      amount -= add;
    }
    for (int i = 0; i < after.length && amount > 0; i++)
      if (after[i] == null || after[i].getType().isAir()) {
        int add = Math.min(amount, item.getMaxStackSize());
        after[i] = item.clone();
        after[i].setAmount(add);
        amount -= add;
      }
    if (amount > 0) throw new IllegalArgumentException("Not enough inventory space");
    return after;
  }

  static ItemStack[] addAll(ItemStack[] inventory, ItemStack[] items) {
    ItemStack[] result = copy(inventory);
    for (ItemStack i : items)
      if (i != null && !i.getType().isAir()) result = add(result, i, i.getAmount());
    return result;
  }

  static ItemStack emerald() {
    return new ItemStack(Material.EMERALD);
  }

  static boolean equal(ItemStack[] a, ItemStack[] b) {
    return Arrays.equals(a, b);
  }
}
