package com.hospital.queue.service;

import com.hospital.queue.core.AppointmentQueue;
import com.hospital.queue.model.Appointment;
import com.hospital.queue.model.AppointmentStatus;
import com.hospital.queue.model.AppointmentView;
import com.hospital.queue.model.ConsultationRecord;
import com.hospital.queue.model.Doctor;
import com.hospital.queue.model.EmergencyLevel;
import com.hospital.queue.model.Patient;
import com.hospital.queue.repository.AppointmentRepository;
import com.hospital.queue.repository.ConsultationRepository;
import com.hospital.queue.repository.PatientRepository;
import com.hospital.queue.triage.SymptomAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core business logic: book (desk), call next (doctor), complete (doctor).
 *
 * HOW A DUPLICATE ASSIGNMENT IS PREVENTED (three independent layers):
 *  1. AppointmentQueue.poll() removes the head while holding a ReentrantLock, so two threads can never
 *     receive the same appointment object.
 *  2. Appointment.tryClaim() is an AtomicBoolean compare-and-set: only one caller can ever win.
 *  3. The SQL "UPDATE ... WHERE id = ? AND status = 'WAITING'" changes the row only once; the row count is checked.
 *
 * HOW A DOCTOR IS PREVENTED FROM GETTING TWO PATIENTS AT ONCE:
 *  4. callNext / callNextWaiting run "is the doctor busy? -> poll -> assign" while holding a per-doctor lock.
 *     Without it, a double-click (two simultaneous call-next requests for the same doctor) lets both requests pass the
 *     "not busy" check before either one has assigned a patient, and the doctor ends up with two IN_PROGRESS patients.
 *     Different doctors use different locks, so they never wait for each other.
 */
@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);

    private final AppointmentRepository appointmentRepository;
    private final ConsultationRepository consultationRepository;
    private final PatientRepository patientRepository;
    private final DoctorService doctorService;
    private final AppointmentQueue queue;
    private final SymptomAnalyzer symptomAnalyzer;
    private final NotificationService notificationService;

    /** One lock per doctor id: serialises "check busy + take next patient + assign" for that doctor only. */
    private final ConcurrentHashMap<Long, ReentrantLock> doctorLocks = new ConcurrentHashMap<>();

    public AppointmentService(AppointmentRepository appointmentRepository,
                              ConsultationRepository consultationRepository,
                              PatientRepository patientRepository,
                              DoctorService doctorService,
                              AppointmentQueue queue,
                              SymptomAnalyzer symptomAnalyzer,
                              NotificationService notificationService) {
        this.appointmentRepository = appointmentRepository;
        this.consultationRepository = consultationRepository;
        this.patientRepository = patientRepository;
        this.doctorService = doctorService;
        this.queue = queue;
        this.symptomAnalyzer = symptomAnalyzer;
        this.notificationService = notificationService;
    }

    /** Priority SUGGESTION only - never a final decision. */
    public SymptomAnalyzer.Suggestion suggestPriority(long patientId) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + patientId));
        return symptomAnalyzer.analyze(patient.symptoms());
    }

    /**
     * Called by registration desks (possibly many threads at once). The final level must come from a
     * nurse/doctor, who confirms or overrides the suggestion; the reviewer's name is stored with the appointment.
     */
    public AppointmentView submit(long patientId, EmergencyLevel suggested, EmergencyLevel finalLevel,
                                  String reviewedBy) {
        if (suggested == null || finalLevel == null) {
            throw new IllegalArgumentException("Suggested level and final level are required");
        }
        if (reviewedBy == null || reviewedBy.isBlank() || reviewedBy.trim().length() > 100) {
            throw new IllegalArgumentException("A nurse/doctor must review the priority and enter their name");
        }
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + patientId));

        Instant requestTime = Instant.now().truncatedTo(ChronoUnit.MICROS);   // MySQL DATETIME(6) precision
        long id = appointmentRepository.insert(patientId, suggested, finalLevel, reviewedBy.trim(), requestTime);
        queue.add(new Appointment(id, patientId, finalLevel, requestTime));

        notificationService.send(patient, "your appointment #" + id + " is registered with priority " + finalLevel + ".");
        return appointmentRepository.findViewById(id).orElseThrow();
    }

    /**
     * A doctor asks for the highest-priority waiting patient. Empty if nobody is waiting.
     * If the same doctor already has a patient (e.g. a double-click), the second call fails with IllegalStateException (HTTP 409).
     */
    public Optional<AppointmentView> callNext(long doctorId) {
        ReentrantLock lock = doctorLocks.computeIfAbsent(doctorId, id -> new ReentrantLock());
        lock.lock();
        try {
            Doctor doctor = requireReadyDoctor(doctorId);
            return assign(doctor, queue.poll());
        } finally {
            lock.unlock();
        }
    }

    /** Same as callNext but waits up to waitMillis for a patient to arrive (used by doctor worker threads). */
    public Optional<AppointmentView> callNextWaiting(long doctorId, long waitMillis) throws InterruptedException {
        ReentrantLock lock = doctorLocks.computeIfAbsent(doctorId, id -> new ReentrantLock());
        lock.lockInterruptibly();
        try {
            Doctor doctor = requireReadyDoctor(doctorId);
            return assign(doctor, queue.poll(waitMillis, TimeUnit.MILLISECONDS));
        } finally {
            lock.unlock();
        }
    }

    private Doctor requireReadyDoctor(long doctorId) {
        Doctor doctor = doctorService.getById(doctorId);
        if (!doctor.available()) {
            throw new IllegalStateException(doctor.name() + " is not on duty");
        }
        if (appointmentRepository.doctorHasInProgress(doctorId)) {
            throw new IllegalStateException(doctor.name() + " is still with a patient - complete that consultation first");
        }
        return doctor;
    }

    private Optional<AppointmentView> assign(Doctor doctor, Appointment candidate) {
        if (candidate == null || !candidate.tryClaim(doctor.id())) {
            return Optional.empty();
        }
        int rows;
        try {
            rows = appointmentRepository.assignToDoctor(candidate.getId(), doctor.id());
        } catch (RuntimeException e) {
            candidate.release();          // database problem: put the patient back so nobody is lost
            queue.add(candidate);
            throw e;
        }
        if (rows != 1) {
            log.warn("Appointment {} was not WAITING in the database; it is not handed out again", candidate.getId());
            return Optional.empty();
        }
        AppointmentView view = appointmentRepository.findViewById(candidate.getId()).orElseThrow();
        patientRepository.findById(view.patientId()).ifPresent(p ->
                notificationService.send(p, "please proceed to " + doctor.name() + " (" + doctor.department() + ")."));
        return Optional.of(view);
    }

    /**
     * Requirement 4: completing an appointment and writing its consultation history happen in ONE transaction.
     * If the history insert fails, the status update is rolled back too.
     *
     * This method must be called through the Spring-managed bean (the proxy) - controllers and the simulation
     * inject AppointmentService, so they do. Calling it from another method of this same class would bypass
     * the proxy and silently skip the transaction, so the guard below fails loudly instead.
     */
    @Transactional(rollbackFor = Exception.class)
    public ConsultationRecord complete(long appointmentId, String diagnosis, String notes) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("complete() must run inside a transaction (call it via the Spring bean)");
        }
        if (diagnosis == null || diagnosis.isBlank()) {
            throw new IllegalArgumentException("Diagnosis is required");
        }
        AppointmentView view = appointmentRepository.findViewById(appointmentId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        if (view.status() != AppointmentStatus.IN_PROGRESS || view.doctorId() == null) {
            throw new IllegalStateException("Appointment " + appointmentId + " is not in progress");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (appointmentRepository.markCompleted(appointmentId, now) != 1) {                 // operation 1
            throw new IllegalStateException("Appointment " + appointmentId + " could not be completed");
        }
        String cleanNotes = notes == null ? "" : notes.trim();
        long historyId = consultationRepository.insert(appointmentId, view.patientId(), view.doctorId(),   // operation 2
                diagnosis.trim(), cleanNotes, now);

        // Notify only after a successful commit, never for a rolled-back transaction.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                patientRepository.findById(view.patientId()).ifPresent(p ->
                        notificationService.send(p, "your consultation for appointment #" + appointmentId + " is complete."));
            }
        });
        return new ConsultationRecord(historyId, appointmentId, view.patientId(), view.patientName(),
                view.doctorId(), view.doctorName(), diagnosis.trim(), cleanNotes, now);
    }

    /** Waiting appointments in true priority order, taken from the in-memory PriorityQueue. */
    public List<AppointmentView> waitingQueue() {
        Map<Long, AppointmentView> waiting = new HashMap<>();
        for (AppointmentView v : appointmentRepository.findViews(AppointmentStatus.WAITING, null)) {
            waiting.put(v.id(), v);
        }
        List<AppointmentView> ordered = new ArrayList<>();
        for (Appointment a : queue.snapshot()) {
            AppointmentView v = waiting.get(a.getId());
            if (v != null) {
                ordered.add(v);
            }
        }
        return ordered;
    }

    public List<AppointmentView> history(AppointmentStatus status, Long patientId) {
        return appointmentRepository.findViews(status, patientId);
    }

    public List<ConsultationRecord> consultations(Long patientId) {
        return consultationRepository.findAll(patientId);
    }

    /** Rebuilds the in-memory queue from WAITING rows (called at startup so a restart loses nobody). */
    public void reloadQueueFromDatabase() {
        queue.clear();
        appointmentRepository.findWaiting().forEach(queue::add);
    }
}
