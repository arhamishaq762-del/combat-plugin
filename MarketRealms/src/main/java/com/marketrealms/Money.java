package com.marketrealms;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

final class Money {
  interface Provider {
    double balance(OfflinePlayer player);

    boolean withdraw(OfflinePlayer player, double amount);

    boolean deposit(OfflinePlayer player, double amount);
  }

  private Provider provider;
  private final JavaPlugin plugin;

  Money(JavaPlugin plugin) {
    this.plugin = plugin;
    refresh();
  }

  void refresh() {
    provider = null;
    if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
      plugin.getLogger().info("Vault missing: money payments disabled; emeralds remain available.");
      return;
    }
    try {
      provider = VaultBridge.find(plugin);
      if (provider == null)
        plugin
            .getLogger()
            .warning(
                "Vault has no economy provider. Install EssentialsX Economy or another provider;"
                    + " emeralds remain available.");
    } catch (LinkageError | RuntimeException e) {
      plugin.getLogger().warning("Vault unavailable: " + e.getMessage());
    }
  }

  boolean available() {
    return provider != null
        && plugin.getServer().getPluginManager().isPluginEnabled("Vault")
        && (!(plugin instanceof MarketRealmsPlugin market)
            || market.config.file("config").getBoolean("economy.money-enabled"));
  }

  double balance(UUID owner) {
    require();
    return provider.balance(Bukkit.getOfflinePlayer(owner));
  }

  void require() {
    if (!available())
      throw new IllegalArgumentException(
          "Vault money unavailable. Install Vault and an economy provider, or choose emeralds.");
  }

  boolean transfer(UUID owner, long signedCents) {
    require();
    double amount = Math.abs(signedCents) / 100.0;
    return signedCents >= 0
        ? provider.withdraw(Bukkit.getOfflinePlayer(owner), amount)
        : provider.deposit(Bukkit.getOfflinePlayer(owner), amount);
  }
}
