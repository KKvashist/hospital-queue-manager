package com.hospital.queue.model;

import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The in-memory appointment kept in the priority queue.
 *
 * Ordering (requirement 2): emergency level DESCENDING, then request time ASCENDING.
 * The id is only a last tie-breaker so two appointments with identical timestamps still have a stable order.
 *
 * Duplicate-assignment guard: {@link #tryClaim(long)} uses an AtomicBoolean compare-and-set,
 * so out of any number of competing threads exactly one can win.
 */
public class Appointment {

    public static final Comparator<Appointment> PRIORITY_ORDER =
            Comparator.comparingInt((Appointment a) -> a.getEmergencyLevel().getWeight()).reversed()
                    .thenComparing(Appointment::getRequestTime)
                    .thenComparingLong(Appointment::getId);

    private final long id;
    private final long patientId;
    private final EmergencyLevel emergencyLevel;
    private final Instant requestTime;

    private final AtomicBoolean claimed = new AtomicBoolean(false);
    private volatile AppointmentStatus status = AppointmentStatus.WAITING;
    private volatile Long doctorId;

    public Appointment(long id, long patientId, EmergencyLevel emergencyLevel, Instant requestTime) {
        this.id = id;
        this.patientId = patientId;
        this.emergencyLevel = emergencyLevel;
        this.requestTime = requestTime;
    }

    /** Atomically claim this appointment for a doctor. Returns true for exactly one caller. */
    public boolean tryClaim(long doctorId) {
        if (claimed.compareAndSet(false, true)) {
            this.doctorId = doctorId;
            this.status = AppointmentStatus.IN_PROGRESS;
            return true;
        }
        return false;
    }

    /** Undo a claim (only used when the database update for the claim failed). */
    public void release() {
        this.doctorId = null;
        this.status = AppointmentStatus.WAITING;
        claimed.set(false);
    }

    public void markCompleted() {
        this.status = AppointmentStatus.COMPLETED;
    }

    public long getId() { return id; }
    public long getPatientId() { return patientId; }
    public EmergencyLevel getEmergencyLevel() { return emergencyLevel; }
    public Instant getRequestTime() { return requestTime; }
    public AppointmentStatus getStatus() { return status; }
    public Long getDoctorId() { return doctorId; }
}
