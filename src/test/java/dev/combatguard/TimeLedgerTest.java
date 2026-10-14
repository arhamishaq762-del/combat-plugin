package dev.combatguard;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class TimeLedgerTest {
    private final AtomicLong now = new AtomicLong(1000);
    private final TimeLedger<String> ledger = new TimeLedger<>(now::get);
    @Test void deadlineExpiresExactly() {
        ledger.start("player", 30);
        now.addAndGet(29999);
        assertTrue(ledger.active("player")); assertEquals(1, ledger.remainingSeconds("player"));
        now.incrementAndGet(); assertFalse(ledger.active("player"));
    }
    @Test void successfulHitRefreshesDeadline() {
        ledger.start("player", 30); now.addAndGet(10000); ledger.start("player", 30);
        assertEquals(30, ledger.remainingSeconds("player"));
    }
    @Test void itemDeadlinesAreIndependent() {
        ledger.start("pearl", 15); ledger.start("apple", 10); now.addAndGet(10000);
        assertTrue(ledger.active("pearl")); assertFalse(ledger.active("apple"));
    }
    @Test void zeroDisablesExistingDeadline() {
        ledger.start("player", 10); ledger.start("player", 0); assertFalse(ledger.active("player"));
    }
    @Test void fractionalCooldownRoundsUp() {
        ledger.start("pearl", 0.001); assertEquals(1, ledger.remainingSeconds("pearl"));
        now.incrementAndGet(); assertFalse(ledger.active("pearl"));
    }
    @Test void clearingRemovesAllDeadlines() {
        ledger.start("a", 10); ledger.start("b", 10); ledger.clear();
        assertFalse(ledger.active("a")); assertFalse(ledger.active("b"));
    }
    @Test void pruningDoesNotRemoveActiveDeadlines() {
        ledger.start("a", 1); ledger.start("b", 10); now.addAndGet(1000); ledger.prune();
        assertFalse(ledger.active("a")); assertTrue(ledger.active("b"));
    }
    @Test void removingOneEntryDoesNotAffectAnother() {
        ledger.start("a", 10); ledger.start("b", 10); ledger.remove("a");
        assertFalse(ledger.active("a")); assertTrue(ledger.active("b"));
    }
}
