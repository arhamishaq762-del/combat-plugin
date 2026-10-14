package dev.combatguard;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import java.util.*;

final class Messages {
    private final CombatGuardPlugin plugin;
    private final Map<UUID, Map<String, Long>> last = new HashMap<>();
    private final Map<UUID, Long> actionbarUntil = new HashMap<>();
    Messages(CombatGuardPlugin plugin) { this.plugin = plugin; }
    static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    void notify(Player player, String key, Map<String, String> placeholders, boolean throttle) {
        long now = System.nanoTime() / 1_000_000;
        var settings = plugin.settings();
        Map<String, Long> notifications = last.computeIfAbsent(player.getUniqueId(), id -> new HashMap<>());
        long previous = notifications.getOrDefault(key, Long.MIN_VALUE / 2);
        if (throttle && now - previous < settings.config.getLong("messages.notification-throttle-ms")) return;
        notifications.put(key, now);
        String root = "messages." + key + ".";
        String chat = render(settings.config.getString(root + "chat", ""), placeholders);
        if (!chat.isEmpty()) player.sendMessage(color(settings.config.getString("messages.prefix", "")) + chat);
        String bar = render(settings.config.getString(root + "actionbar", ""), placeholders);
        if (!bar.isEmpty()) {
            actionbar(player, bar);
            actionbarUntil.put(player.getUniqueId(), now + 2000);
        }
        String title = render(settings.config.getString(root + "title", ""), placeholders);
        String subtitle = render(settings.config.getString(root + "subtitle", ""), placeholders);
        if (!title.isEmpty() || !subtitle.isEmpty()) player.sendTitle(title, subtitle,
            settings.config.getInt("messages.title-fade-in-ticks"), settings.config.getInt("messages.title-stay-ticks"),
            settings.config.getInt("messages.title-fade-out-ticks"));
    }
    void timer(Player player, long seconds) {
        if (System.nanoTime() / 1_000_000 < actionbarUntil.getOrDefault(player.getUniqueId(), 0L)) return;
        String text = render(plugin.settings().config.getString("messages.combat-timer", ""), Map.of("seconds", "" + seconds));
        if (!text.isEmpty()) actionbar(player, text);
    }
    private String render(String text, Map<String, String> values) {
        for (var entry : values.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        return color(text);
    }
    private void actionbar(Player player, String text) { player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text)); }
    void forget(UUID id) { last.remove(id); actionbarUntil.remove(id); }
    void clear() { last.clear(); actionbarUntil.clear(); }
}
