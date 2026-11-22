package com.hospital.queue.model;

import java.time.Instant;

public record Patient(Long id, String name, int age, String gender, String contact,
                      String symptoms, Instant registeredAt) { }
