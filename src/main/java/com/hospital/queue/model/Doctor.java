package com.hospital.queue.model;

/** "available" means the doctor is on duty; a doctor with a patient in progress is additionally "busy". */
public record Doctor(Long id, String name, String specialization, String department, boolean available) { }
