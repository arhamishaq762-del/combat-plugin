package com.marketrealms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class MoneyTest {
  @Test
  void missingVaultDoesNotCrashStartup() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    Server server = mock(Server.class);
    PluginManager manager = mock(PluginManager.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getPluginManager()).thenReturn(manager);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("MarketRealms-test"));
    when(manager.isPluginEnabled("Vault")).thenReturn(false);
    Money money = new Money(plugin);
    assertFalse(money.available());
    assertThrows(IllegalArgumentException.class, money::require);
  }
}
