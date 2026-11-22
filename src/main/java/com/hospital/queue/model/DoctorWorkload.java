package com.hospital.queue.model;

public record DoctorWorkload(long doctorId, String name, String department, boolean available,
                             long inProgress, long treated) { }
