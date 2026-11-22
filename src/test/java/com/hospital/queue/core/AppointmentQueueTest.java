package com.hospital.queue.core;

import com.hospital.queue.model.Appointment;
import com.hospital.queue.model.EmergencyLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Requirement 2: emergency level descending, then request time ascending. */
class AppointmentQueueTest {

    private static final Instant BASE = Instant.parse("2026-01-01T10:00:00Z");
    private AppointmentQueue queue;

    @BeforeEach
    void setUp() {
        queue = new AppointmentQueue();
    }

    private static Appointment appt(long id, EmergencyLevel level, int secondsAfterBase) {
        return new Appointment(id, 100 + id, level, BASE.plusSeconds(secondsAfterBase));
    }

    @Test
    void higherEmergencyLevelIsServedFirst() {
        queue.add(appt(1, EmergencyLevel.LOW, 0));
        queue.add(appt(2, EmergencyLevel.MEDIUM, 1));
        queue.add(appt(3, EmergencyLevel.CRITICAL, 2));
        queue.add(appt(4, EmergencyLevel.HIGH, 3));

        assertEquals(3L, queue.poll().getId());
        assertEquals(4L, queue.poll().getId());
        assertEquals(2L, queue.poll().getId());
        assertEquals(1L, queue.poll().getId());
        assertNull(queue.poll());
    }

    @Test
    void sameLevelIsServedByEarlierRequestTime() {
        queue.add(appt(1, EmergencyLevel.HIGH, 30));
        queue.add(appt(2, EmergencyLevel.HIGH, 10));
        queue.add(appt(3, EmergencyLevel.HIGH, 20));

        assertEquals(2L, queue.poll().getId());
        assertEquals(3L, queue.poll().getId());
        assertEquals(1L, queue.poll().getId());
    }

    @Test
    void levelBeatsTimeAndTimeBreaksTies() {
        queue.add(appt(1, EmergencyLevel.LOW, 0));        // oldest but least urgent
        queue.add(appt(2, EmergencyLevel.CRITICAL, 50));
        queue.add(appt(3, EmergencyLevel.HIGH, 10));
        queue.add(appt(4, EmergencyLevel.CRITICAL, 20));  // earlier than #2, same level

        List<Long> order = new ArrayList<>();
        Appointment a;
        while ((a = queue.poll()) != null) {
            order.add(a.getId());
        }
        assertEquals(List.of(4L, 2L, 3L, 1L), order);
    }

    @Test
    void identicalLevelAndTimeFallBackToIdForStableOrder() {
        queue.add(appt(7, EmergencyLevel.MEDIUM, 5));
        queue.add(appt(3, EmergencyLevel.MEDIUM, 5));
        assertEquals(3L, queue.poll().getId());
        assertEquals(7L, queue.poll().getId());
    }

    @Test
    void peekShowsHighestPriorityWithoutRemovingIt() {
        queue.add(appt(1, EmergencyLevel.LOW, 0));
        queue.add(appt(2, EmergencyLevel.CRITICAL, 1));
        assertEquals(2L, queue.peek().getId());
        assertEquals(2, queue.size());
    }

    @Test
    void snapshotIsInPriorityOrder() {
        queue.add(appt(1, EmergencyLevel.LOW, 0));
        queue.add(appt(2, EmergencyLevel.HIGH, 1));
        queue.add(appt(3, EmergencyLevel.CRITICAL, 2));
        List<Long> ids = queue.snapshot().stream().map(Appointment::getId).toList();
        assertEquals(List.of(3L, 2L, 1L), ids);
    }

    @Test
    void timedPollReturnsNullOnEmptyQueue() throws InterruptedException {
        assertNull(queue.poll(30, TimeUnit.MILLISECONDS));
        assertTrue(queue.size() == 0);
    }
}
