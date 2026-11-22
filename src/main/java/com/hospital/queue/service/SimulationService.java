package com.hospital.queue.service;

import com.hospital.queue.core.AppointmentQueue;
import com.hospital.queue.model.AppointmentView;
import com.hospital.queue.model.Doctor;
import com.hospital.queue.model.EmergencyLevel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Requirement 3 demo against the REAL services and database: N registration-desk threads book appointments
 * while M doctor-worker threads call and complete them at the same time.
 */
@Service
public class SimulationService {

    public record Result(int desks, int doctors, int submitted, int processed, int duplicateAssignments,
                         long millis) { }

    private static final EmergencyLevel[] LEVELS = EmergencyLevel.values();
    private static final String[] SYMPTOMS = {"Severe chest pain", "Mild fever", "Fracture in arm",
            "Common cold", "Difficulty breathing", "Abdominal pain"};

    private final AppointmentService appointmentService;
    private final PatientService patientService;
    private final DoctorService doctorService;
    private final AppointmentQueue queue;

    public SimulationService(AppointmentService appointmentService, PatientService patientService,
                             DoctorService doctorService, AppointmentQueue queue) {
        this.appointmentService = appointmentService;
        this.patientService = patientService;
        this.doctorService = doctorService;
        this.queue = queue;
    }

    public Result run(int desks, int workers, int total) {
        if (desks < 1 || desks > 20 || workers < 1 || workers > 10 || total < 1 || total > 500) {
            throw new IllegalArgumentException("Use 1-20 desks, 1-10 doctors and 1-500 appointments");
        }
        if (queue.size() > 0) {
            throw new IllegalStateException("The waiting queue must be empty before running a simulation");
        }
        List<Doctor> doctors = doctorService.list().stream().filter(Doctor::available).limit(workers).toList();
        if (doctors.size() < workers) {
            throw new IllegalArgumentException("Add at least " + workers + " available doctors first");
        }

        List<Long> patientIds = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            patientIds.add(patientService.register("Sim Patient " + (i + 1), 20 + i % 50, "Other", "0000000000",
                    SYMPTOMS[i % SYMPTOMS.length]).id());
        }

        AtomicInteger desksRunning = new AtomicInteger(desks);
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        Set<Long> processed = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(desks + workers);
        long began = System.nanoTime();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int d = 0; d < desks; d++) {
                final int desk = d;
                futures.add(pool.submit(() -> {
                    awaitStart(start);
                    try {
                        for (int i = desk; i < total; i += desks) {          // each desk books its own share
                            EmergencyLevel level = LEVELS[i % LEVELS.length];
                            appointmentService.submit(patientIds.get(i), level, level, "Simulated Desk " + (desk + 1));
                            submitted.incrementAndGet();
                        }
                    } finally {
                        desksRunning.decrementAndGet();
                    }
                }));
            }
            for (Doctor doctor : doctors) {
                futures.add(pool.submit(() -> {
                    awaitStart(start);
                    try {
                        while (true) {
                            Optional<AppointmentView> next = appointmentService.callNextWaiting(doctor.id(), 20);
                            if (next.isEmpty()) {
                                if (desksRunning.get() == 0 && queue.size() == 0) {
                                    return;                                  // desks finished and queue drained
                                }
                                continue;
                            }
                            long id = next.get().id();
                            if (!processed.add(id)) {
                                duplicates.incrementAndGet();                // would mean a double assignment
                            }
                            appointmentService.complete(id, "Simulated consultation", "Generated by the simulation");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Simulation failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Simulation interrupted", e);
        } finally {
            pool.shutdownNow();
        }
        return new Result(desks, workers, submitted.get(), processed.size(), duplicates.get(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted before start", e);
        }
    }
}
