package com.hospital.queue.integration;

import com.hospital.queue.core.AppointmentQueue;
import com.hospital.queue.model.AppointmentView;
import com.hospital.queue.model.Doctor;
import com.hospital.queue.model.EmergencyLevel;
import com.hospital.queue.model.Patient;
import com.hospital.queue.service.AppointmentService;
import com.hospital.queue.service.DoctorService;
import com.hospital.queue.service.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug 2: one doctor must never hold two IN_PROGRESS patients, even when call-next arrives twice at the same moment
 * (double-click) - requirement 3. Real Spring context + H2 (MySQL mode), same setup as HospitalIntegrationTest.
 */
@SpringBootTest
class DoubleAssignmentTest {

    @Autowired PatientService patientService;
    @Autowired DoctorService doctorService;
    @Autowired AppointmentService appointmentService;
    @Autowired AppointmentQueue queue;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM consultation_history");
        jdbc.update("DELETE FROM appointments");
        jdbc.update("DELETE FROM doctors");
        jdbc.update("DELETE FROM patients");
        queue.clear();
        doctorService.reloadDepartmentMap();
    }

    private void bookWaiting(int count) {
        for (int i = 1; i <= count; i++) {
            Patient p = patientService.register("Patient " + i, 30, "Female", "9800000000", "cough");
            appointmentService.submit(p.id(), EmergencyLevel.MEDIUM, EmergencyLevel.MEDIUM, "Nurse Joy");
        }
    }

    private long inProgressFor(long doctorId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE doctor_id = ? AND status = 'IN_PROGRESS'",
                Long.class, doctorId);
    }

    /** Result of one call-next attempt: the assigned appointment id, or the exception message when it was refused. */
    private record Attempt(Long appointmentId, String refusal) { }

    private Attempt attempt(long doctorId, CyclicBarrier gate) throws Exception {
        gate.await(10, TimeUnit.SECONDS);                    // all threads fire at the same instant
        try {
            Optional<AppointmentView> v = appointmentService.callNext(doctorId);
            return new Attempt(v.map(AppointmentView::id).orElse(null), null);
        } catch (IllegalStateException e) {
            return new Attempt(null, e.getMessage());
        }
    }

    @Test
    void simultaneousCallNextForSameDoctorAssignsExactlyOnePatient() throws Exception {
        Doctor doctor = doctorService.addDoctor("Dr. Rao", "Physician", "General");
        bookWaiting(10);

        int clicks = 8;                                      // 8 simultaneous requests for the same doctor
        CyclicBarrier gate = new CyclicBarrier(clicks);
        ExecutorService pool = Executors.newFixedThreadPool(clicks);
        List<Future<Attempt>> futures = new ArrayList<>();
        for (int i = 0; i < clicks; i++) {
            futures.add(pool.submit((Callable<Attempt>) () -> attempt(doctor.id(), gate)));
        }
        List<Attempt> attempts = new ArrayList<>();
        for (Future<Attempt> f : futures) {
            attempts.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long assigned = attempts.stream().filter(a -> a.appointmentId() != null).count();
        long refused = attempts.stream().filter(a -> a.refusal() != null).count();
        assertEquals(1, assigned, "exactly one call-next may succeed for one doctor");
        assertEquals(clicks - 1, refused, "every other simultaneous call must be refused");
        assertEquals(1, inProgressFor(doctor.id()), "doctor must have exactly one IN_PROGRESS patient in the database");
        assertEquals(9, queue.size(), "the patients of refused calls must stay in the queue, not disappear");
        assertEquals(9L, jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status = 'WAITING'", Long.class));
    }

    @Test
    void secondCallNextWhileDoctorIsBusyIsRefusedAndNobodyIsLost() {
        Doctor doctor = doctorService.addDoctor("Dr. Rao", "Physician", "General");
        bookWaiting(3);

        appointmentService.callNext(doctor.id()).orElseThrow();
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> appointmentService.callNext(doctor.id()));

        assertTrue(e.getMessage().contains("still with a patient"));
        assertEquals(1, inProgressFor(doctor.id()));
        assertEquals(2, queue.size());
    }

    @Test
    void doctorCanCallNextAgainAfterCompletingTheFirstPatient() {
        Doctor doctor = doctorService.addDoctor("Dr. Rao", "Physician", "General");
        bookWaiting(2);

        AppointmentView first = appointmentService.callNext(doctor.id()).orElseThrow();
        appointmentService.complete(first.id(), "Checked", "ok");
        AppointmentView second = appointmentService.callNext(doctor.id()).orElseThrow();

        assertTrue(second.id() != first.id());
        assertEquals(1, inProgressFor(doctor.id()));
    }

    @Test
    void differentDoctorsCallingAtTheSameTimeEachGetOneDistinctPatient() throws Exception {
        int doctors = 3, clicksPerDoctor = 4;
        List<Doctor> docs = new ArrayList<>();
        for (int i = 1; i <= doctors; i++) {
            docs.add(doctorService.addDoctor("Dr. " + i, "Physician", "General"));
        }
        bookWaiting(12);

        int threads = doctors * clicksPerDoctor;
        CyclicBarrier gate = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Attempt>> futures = new ArrayList<>();
        for (Doctor d : docs) {
            for (int i = 0; i < clicksPerDoctor; i++) {
                futures.add(pool.submit((Callable<Attempt>) () -> attempt(d.id(), gate)));
            }
        }
        Set<Long> assignedIds = new HashSet<>();
        for (Future<Attempt> f : futures) {
            try {
                Attempt a = f.get(30, TimeUnit.SECONDS);
                if (a.appointmentId() != null) {
                    assertTrue(assignedIds.add(a.appointmentId()), "appointment " + a.appointmentId() + " assigned twice");
                }
            } catch (ExecutionException e) {
                throw new AssertionError("call-next failed unexpectedly", e.getCause());
            }
        }
        pool.shutdown();

        assertEquals(doctors, assignedIds.size(), "one patient per doctor, all different");
        for (Doctor d : docs) {
            assertEquals(1, inProgressFor(d.id()), d.name() + " must have exactly one patient");
        }
        assertEquals(12 - doctors, queue.size());
    }
}
