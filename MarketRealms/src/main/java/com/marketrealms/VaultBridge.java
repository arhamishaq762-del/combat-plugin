package com.marketrealms;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

/** Kept out of optional-dependency startup paths until Vault is present. */
final class VaultBridge {
  static Money.Provider find(JavaPlugin plugin) {
    var registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
    if (registration == null) return null;
    Economy economy = registration.getProvider();
    return new Money.Provider() {
      public double balance(OfflinePlayer p) {
        return economy.getBalance(p);
      }

      public boolean withdraw(OfflinePlayer p, double n) {
        return economy.withdrawPlayer(p, n).transactionSuccess();
      }

      public boolean deposit(OfflinePlayer p, double n) {
        return economy.depositPlayer(p, n).transactionSuccess();
      }
    };
  }
}
