package dev.combatguard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import java.util.*;

/** Modify existing viewer scoreboards without replacing scoreboards from other plugins. */
final class NameTags {
    private static final String TEAM = "combatguard_red";
    private final Map<Scoreboard, Map<String, String>> originals = new IdentityHashMap<>();
    void update(Set<String> tagged) {
        Set<Scoreboard> boards = Collections.newSetFromMap(new IdentityHashMap<>());
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        Bukkit.getOnlinePlayers().forEach(p -> boards.add(p.getScoreboard()));
        // Release old viewer scoreboards so disconnected players do not retain them indefinitely.
        for (Scoreboard old : new ArrayList<>(originals.keySet())) if (!boards.contains(old)) restoreBoard(old);
        for (Scoreboard board : boards) {
            Map<String, String> prior = originals.computeIfAbsent(board, b -> new HashMap<>());
            Team team = board.getTeam(TEAM);
            if (team == null && !tagged.isEmpty()) {
                team = board.registerNewTeam(TEAM);
                team.setColor(ChatColor.RED);
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            }
            for (String entry : new HashSet<>(prior.keySet())) if (!tagged.contains(entry)) restore(board, entry, prior);
            for (String entry : tagged) {
                Team existing = board.getEntryTeam(entry);
                if (existing == team) continue;
                // Capture the latest team if another plugin moved this entry during combat.
                prior.put(entry, existing == null ? "" : existing.getName());
                team.addEntry(entry);
            }
        }
    }
    private void restore(Scoreboard board, String entry, Map<String, String> prior) {
        Team current = board.getEntryTeam(entry);
        String name = prior.remove(entry);
        if (current == null || !TEAM.equals(current.getName())) return;
        current.removeEntry(entry);
        Team old = name == null || name.isEmpty() ? null : board.getTeam(name);
        if (old != null) old.addEntry(entry);
    }
    private void restoreBoard(Scoreboard board) {
        Map<String, String> prior = originals.remove(board);
        for (String entry : new HashSet<>(prior.keySet())) restore(board, entry, prior);
        Team team = board.getTeam(TEAM);
        if (team != null && team.getEntries().isEmpty()) team.unregister();
    }
    void clear() { for (Scoreboard board : new ArrayList<>(originals.keySet())) restoreBoard(board); }
}
