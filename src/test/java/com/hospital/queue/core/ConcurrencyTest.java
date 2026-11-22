package com.hospital.queue.core;

import com.hospital.queue.model.Appointment;
import com.hospital.queue.model.EmergencyLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Requirements 3 (concurrency) - runs with plain threads, no Spring or database needed. */
class ConcurrencyTest {

    private static final EmergencyLevel[] LEVELS = EmergencyLevel.values();

    /** Registration desk: submits "count" appointments with ids taken from a shared counter. */
    private static Runnable desk(AppointmentQueue queue, AtomicLong ids, int count, CountDownLatch start,
                                 AtomicInteger desksStillRunning, List<Appointment> submitted) {
        return () -> {
            try {
                start.await();
                for (int i = 0; i < count; i++) {
                    long id = ids.incrementAndGet();
                    Appointment a = new Appointment(id, id, LEVELS[(int) (id % LEVELS.length)], Instant.now());
                    submitted.add(a);
                    queue.add(a);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                desksStillRunning.decrementAndGet();
            }
        };
    }

    /** Doctor worker: keeps claiming the next appointment until all desks are done and the queue is empty. */
    private static Runnable doctor(long doctorId, AppointmentQueue queue, CountDownLatch start,
                                   AtomicInteger desksStillRunning, Map<Long, List<Long>> claimedBy) {
        return () -> {
            try {
                start.await();
                while (true) {
                    Appointment a = queue.poll(20, TimeUnit.MILLISECONDS);
                    if (a == null) {
                        if (desksStillRunning.get() == 0 && queue.size() == 0) {
                            return;
                        }
                        continue;
                    }
                    if (a.tryClaim(doctorId)) {
                        claimedBy.computeIfAbsent(a.getId(), k -> Collections.synchronizedList(new ArrayList<>()))
                                .add(doctorId);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static void awaitAll(List<Future<?>> futures) throws Exception {
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);   // rethrows any exception from a worker thread
        }
    }

    @Test
    void concurrentRegistrationDesksKeepPriorityOrderCorrect() throws Exception {
        AppointmentQueue queue = new AppointmentQueue();
        int desks = 5, perDesk = 40;
        AtomicLong ids = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger(desks);
        List<Appointment> submitted = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(desks);
        List<Future<?>> futures = new ArrayList<>();
        for (int d = 0; d < desks; d++) {
            futures.add(pool.submit(desk(queue, ids, perDesk, start, running, submitted)));
        }
        start.countDown();
        awaitAll(futures);
        pool.shutdown();

        assertEquals(desks * perDesk, queue.size());
        List<Appointment> expected = new ArrayList<>(submitted);
        expected.sort(Appointment.PRIORITY_ORDER);
        List<Appointment> drained = new ArrayList<>();
        Appointment a;
        while ((a = queue.poll()) != null) {
            drained.add(a);
        }
        assertEquals(expected.size(), drained.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).getId(), drained.get(i).getId());
        }
    }

    @Test
    void onlyOneThreadCanClaimTheSameAppointment() throws Exception {
        Appointment shared = new Appointment(1, 1, EmergencyLevel.CRITICAL, Instant.now());
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final long doctorId = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (shared.tryClaim(doctorId)) {
                    winners.incrementAndGet();
                }
            }));
        }
        start.countDown();
        awaitAll(futures);
        pool.shutdown();
        assertEquals(1, winners.get());
    }

    @Test
    void multipleDoctorWorkersDrainTheQueueWithoutDuplicates() throws Exception {
        AppointmentQueue queue = new AppointmentQueue();
        for (long id = 1; id <= 60; id++) {
            queue.add(new Appointment(id, id, LEVELS[(int) (id % LEVELS.length)], Instant.now()));
        }
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger(0);          // no desks: queue is pre-filled
        Map<Long, List<Long>> claimedBy = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> futures = new ArrayList<>();
        for (long d = 1; d <= 3; d++) {
            futures.add(pool.submit(doctor(d, queue, start, running, claimedBy)));
        }
        start.countDown();
        awaitAll(futures);
        pool.shutdown();

        assertEquals(60, claimedBy.size());
        claimedBy.forEach((id, doctors) -> assertEquals(1, doctors.size()));
        assertEquals(0, queue.size());
    }

    /** Required stress test: 5 registration desks, 3 doctor workers, 100 appointments. */
    @Test
    void stressTestFiveDesksThreeDoctorsHundredAppointments() throws Exception {
        AppointmentQueue queue = new AppointmentQueue();
        int desks = 5, doctors = 3, perDesk = 20;               // 5 x 20 = 100
        AtomicLong ids = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger(desks);
        List<Appointment> submitted = Collections.synchronizedList(new ArrayList<>());
        Map<Long, List<Long>> claimedBy = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(desks + doctors);
        List<Future<?>> futures = new ArrayList<>();
        for (int d = 0; d < desks; d++) {
            futures.add(pool.submit(desk(queue, ids, perDesk, start, running, submitted)));
        }
        for (long d = 1; d <= doctors; d++) {
            futures.add(pool.submit(doctor(d, queue, start, running, claimedBy)));
        }
        start.countDown();
        awaitAll(futures);
        pool.shutdown();

        assertEquals(100, submitted.size());
        assertEquals(100, claimedBy.size());                    // every appointment was processed
        for (Appointment a : submitted) {
            List<Long> by = claimedBy.get(a.getId());
            assertTrue(by != null && by.size() == 1, "appointment " + a.getId() + " must be assigned exactly once");
        }
        assertEquals(0, queue.size());
    }
}
