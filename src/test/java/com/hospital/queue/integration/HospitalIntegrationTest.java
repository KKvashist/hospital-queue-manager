package com.hospital.queue.integration;

import com.hospital.queue.core.AppointmentQueue;
import com.hospital.queue.model.AppointmentStatus;
import com.hospital.queue.model.AppointmentView;
import com.hospital.queue.model.DashboardStats;
import com.hospital.queue.model.Doctor;
import com.hospital.queue.model.EmergencyLevel;
import com.hospital.queue.model.Patient;
import com.hospital.queue.service.AppointmentService;
import com.hospital.queue.service.DashboardService;
import com.hospital.queue.service.DoctorService;
import com.hospital.queue.service.PatientService;
import com.hospital.queue.service.SimulationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real Spring context and real JDBC code against an H2 database in MySQL mode (see test application.properties). */
@SpringBootTest
class HospitalIntegrationTest {

    @Autowired PatientService patientService;
    @Autowired DoctorService doctorService;
    @Autowired AppointmentService appointmentService;
    @Autowired DashboardService dashboardService;
    @Autowired SimulationService simulationService;
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

    private Patient newPatient(String name, String symptoms) {
        return patientService.register(name, 40, "Female", "9800000000", symptoms);
    }

    private AppointmentView book(Patient p, EmergencyLevel level) {
        return appointmentService.submit(p.id(), level, level, "Nurse Joy");
    }

    @Test
    void patientRegistrationGeneratesUniqueIdsAndIsSearchable() {
        Patient a = newPatient("Asha Menon", "mild fever");
        Patient b = newPatient("Bala Iyer", "severe chest pain");

        assertNotNull(a.id());
        assertNotEquals(a.id(), b.id());
        assertEquals("Asha Menon", patientService.get(a.id()).name());
        assertEquals(1, patientService.search("bala").size());
        assertEquals(a.id(), patientService.search(String.valueOf(a.id())).get(0).id());
    }

    @Test
    void doctorsAreGroupedByDepartmentInAMap() {
        doctorService.addDoctor("Dr. A", "Cardiologist", "Cardiology");
        doctorService.addDoctor("Dr. B", "Cardiologist", "Cardiology");
        doctorService.addDoctor("Dr. C", "Physician", "General");

        assertEquals(2, doctorService.getDoctorsByDepartment().get("Cardiology").size());
        assertEquals(1, doctorService.getDoctorsByDepartment().get("General").size());
    }

    @Test
    void appointmentRequiresNurseOrDoctorReview() {
        Patient p = newPatient("No Review", "cough");
        assertThrows(IllegalArgumentException.class,
                () -> appointmentService.submit(p.id(), EmergencyLevel.LOW, EmergencyLevel.LOW, "  "));
    }

    @Test
    void completionMarksAppointmentCompletedAndStoresHistoryTogether() {
        Doctor doctor = doctorService.addDoctor("Dr. Rao", "Cardiologist", "Cardiology");
        Patient p = newPatient("Rohit", "chest pain");
        AppointmentView booked = book(p, EmergencyLevel.CRITICAL);

        AppointmentView inProgress = appointmentService.callNext(doctor.id()).orElseThrow();
        assertEquals(booked.id(), inProgress.id());
        assertEquals(AppointmentStatus.IN_PROGRESS, inProgress.status());

        appointmentService.complete(booked.id(), "Angina", "Prescribed rest");

        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, booked.id()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM consultation_history WHERE appointment_id = ?",
                Long.class, booked.id()));
        assertEquals(1, appointmentService.consultations(p.id()).size());
    }

    @Test
    void failedHistoryInsertRollsBackTheStatusUpdate() {
        assertTrue(AopUtils.isAopProxy(appointmentService), "AppointmentService must be a Spring transactional proxy");

        Doctor doctor = doctorService.addDoctor("Dr. Rao", "Cardiologist", "Cardiology");
        Patient p = newPatient("Rollback Case", "fracture");
        AppointmentView booked = book(p, EmergencyLevel.HIGH);
        appointmentService.callNext(doctor.id()).orElseThrow();

        // Plant a history row for the same appointment: the UNIQUE(appointment_id) constraint makes the
        // history insert (operation 2) fail AFTER the status update (operation 1) already ran.
        jdbc.update("INSERT INTO consultation_history (appointment_id, patient_id, doctor_id, diagnosis, notes, consulted_at) "
                + "VALUES (?, ?, ?, 'planted', '', ?)", booked.id(), p.id(), doctor.id(), Timestamp.from(Instant.now()));

        assertThrows(DataAccessException.class, () -> appointmentService.complete(booked.id(), "Fracture", "cast"));

        assertEquals("IN_PROGRESS", jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, booked.id()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE id = ? AND completed_at IS NULL",
                Long.class, booked.id()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM consultation_history", Long.class));
    }

    @Test
    void cannotCompleteAnAppointmentThatIsStillWaiting() {
        AppointmentView waiting = book(newPatient("Waiting", "cough"), EmergencyLevel.LOW);
        assertThrows(IllegalStateException.class, () -> appointmentService.complete(waiting.id(), "x", "y"));
    }

    @Test
    void dashboardStatisticsComeFromTheDatabase() {
        Doctor d1 = doctorService.addDoctor("Dr. One", "Physician", "General");
        doctorService.addDoctor("Dr. Two", "Physician", "General");
        Patient p1 = newPatient("P1", "cold");
        Patient p2 = newPatient("P2", "chest pain");
        Patient p3 = newPatient("P3", "fracture");
        book(p1, EmergencyLevel.LOW);
        AppointmentView critical = book(p2, EmergencyLevel.CRITICAL);
        AppointmentView high = book(p3, EmergencyLevel.HIGH);

        Optional<AppointmentView> called = appointmentService.callNext(d1.id());
        assertEquals(critical.id(), called.orElseThrow().id());      // CRITICAL goes first
        appointmentService.complete(critical.id(), "Checked", "ok");

        DashboardStats s = dashboardService.stats();
        assertEquals(3, s.totalPatients());
        assertEquals(3, s.totalAppointments());
        assertEquals(2, s.waiting());
        assertEquals(1, s.treated());
        assertEquals(0, s.inProgress());
        assertEquals(2, s.emergencyPatients());                       // CRITICAL + HIGH appointments
        assertEquals(2, s.availableDoctors());
        assertEquals(high.id(), s.highestPriorityPending().id());     // HIGH is now the top pending case
        assertEquals(1L, s.workload().stream().filter(w -> w.doctorId() == d1.id()).findFirst().orElseThrow().treated());
    }

    /** Requirement 3, end to end: 5 desk threads + 3 doctor threads + 100 appointments through services and the database. */
    @Test
    void stressTestWithDatabaseFiveDesksThreeDoctorsHundredAppointments() {
        for (int i = 1; i <= 3; i++) {
            doctorService.addDoctor("Dr. Worker " + i, "Physician", "General");
        }

        SimulationService.Result result = simulationService.run(5, 3, 100);

        assertEquals(100, result.submitted());
        assertEquals(100, result.processed());
        assertEquals(0, result.duplicateAssignments());
        assertEquals(100L, jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status = 'COMPLETED'", Long.class));
        assertEquals(100L, jdbc.queryForObject("SELECT COUNT(*) FROM consultation_history", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT appointment_id FROM consultation_history "
                + "GROUP BY appointment_id HAVING COUNT(*) > 1) t", Long.class));
        List<AppointmentView> all = appointmentService.history(null, null);
        assertEquals(100, all.stream().filter(a -> a.doctorId() != null).count());
        assertEquals(0, queue.size());
    }
}
