package com.hospital.queue.model;

import java.time.Instant;

public record ConsultationRecord(long id, long appointmentId, long patientId, String patientName,
                                 long doctorId, String doctorName, String diagnosis, String notes,
                                 Instant consultedAt) { }
