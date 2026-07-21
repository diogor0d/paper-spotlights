package dev.diogo.paperspotlights;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventReconciliationCoalescerTest {

    @Test
    void coalescesManyEventsIntoOneNextTickFlushAndDeduplicatesPositions() {
        Queue<Runnable> nextTick = new ArrayDeque<>();
        List<Set<String>> reconciliations = new ArrayList<>();
        EventReconciliationCoalescer<String> coalescer = new EventReconciliationCoalescer<>(
                positions -> new LinkedHashSet<>(positions),
                nextTick::add,
                reconciliations::add
        );

        coalescer.offer(List.of("first", "shared"));
        coalescer.offer(List.of("shared", "second"));
        coalescer.offer(List.of("first", "third"));

        assertEquals(1, nextTick.size());
        nextTick.remove().run();
        assertEquals(List.of(Set.of("first", "shared", "second", "third")), reconciliations);
    }

    @Test
    void filtersBeforeSchedulingAndDoesNotScheduleWhenNothingIsRelevant() {
        Queue<Runnable> nextTick = new ArrayDeque<>();
        List<Collection<String>> filtered = new ArrayList<>();
        List<Set<String>> reconciliations = new ArrayList<>();
        EventReconciliationCoalescer<String> coalescer = new EventReconciliationCoalescer<>(
                positions -> {
                    filtered.add(positions);
                    return positions.stream()
                            .filter(position -> position.startsWith("managed-"))
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                },
                nextTick::add,
                reconciliations::add
        );

        coalescer.offer(List.of("foreign-a", "foreign-b"));
        assertEquals(List.of(List.of("foreign-a", "foreign-b")), filtered);
        assertEquals(0, nextTick.size());

        coalescer.offer(List.of("foreign-c", "managed-a", "managed-a"));
        assertEquals(1, nextTick.size());
        nextTick.remove().run();
        assertEquals(List.of(Set.of("managed-a")), reconciliations);
    }
}
