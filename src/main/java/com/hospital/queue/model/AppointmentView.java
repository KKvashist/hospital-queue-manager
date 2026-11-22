package com.hospital.queue.model;

import java.time.Instant;

/** Read-only appointment row joined with patient and doctor names, used by the UI. */
public record AppointmentView(long id, long patientId, String patientName, Long doctorId, String doctorName,
                              EmergencyLevel suggestedLevel, EmergencyLevel finalLevel, String reviewedBy,
                              AppointmentStatus status, Instant requestTime, Instant completedAt) { }
