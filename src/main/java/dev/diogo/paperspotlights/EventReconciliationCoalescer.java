package dev.diogo.paperspotlights;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Collects relevant world-event positions until one next-tick reconciliation flush.
 *
 * <p>This class is intended for server-thread use. The caller supplies scheduling so
 * it does not depend on Bukkit and can be tested deterministically.</p>
 */
public final class EventReconciliationCoalescer<T> {

    private final Function<Collection<T>, Set<T>> relevantPositions;
    private final Consumer<Runnable> scheduleNextTick;
    private final Consumer<Set<T>> reconcile;
    private final LinkedHashSet<T> pending = new LinkedHashSet<>();

    private boolean flushScheduled;

    public EventReconciliationCoalescer(
            Function<Collection<T>, Set<T>> relevantPositions,
            Consumer<Runnable> scheduleNextTick,
            Consumer<Set<T>> reconcile
    ) {
        this.relevantPositions = Objects.requireNonNull(relevantPositions, "relevantPositions");
        this.scheduleNextTick = Objects.requireNonNull(scheduleNextTick, "scheduleNextTick");
        this.reconcile = Objects.requireNonNull(reconcile, "reconcile");
    }

    /** Adds only relevant positions and schedules at most one pending flush. */
    public void offer(Collection<T> positions) {
        Objects.requireNonNull(positions, "positions");
        if (positions.isEmpty()) {
            return;
        }

        Set<T> relevant = Objects.requireNonNull(
                relevantPositions.apply(List.copyOf(positions)),
                "relevantPositions result"
        );
        pending.addAll(relevant);
        if (!pending.isEmpty() && !flushScheduled) {
            flushScheduled = true;
            scheduleNextTick.accept(this::flush);
        }
    }

    private void flush() {
        flushScheduled = false;
        if (pending.isEmpty()) {
            return;
        }

        Set<T> snapshot = Collections.unmodifiableSet(new LinkedHashSet<>(pending));
        pending.clear();
        reconcile.accept(snapshot);
    }
}
