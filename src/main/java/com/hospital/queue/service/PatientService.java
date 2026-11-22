package com.hospital.queue.service;

import com.hospital.queue.model.Patient;
import com.hospital.queue.repository.PatientRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class PatientService {

    private final PatientRepository patientRepository;

    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    public Patient register(String name, int age, String gender, String contact, String symptoms) {
        name = require(name, "Name", 100);
        gender = require(gender, "Gender", 20);
        contact = require(contact, "Contact details", 100);
        symptoms = require(symptoms, "Symptoms", 500);
        if (age < 0 || age > 130) {
            throw new IllegalArgumentException("Age must be between 0 and 130");
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return patientRepository.save(new Patient(null, name, age, gender, contact, symptoms, now));
    }

    public Patient get(long id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + id));
    }

    public List<Patient> search(String query) {
        return (query == null || query.isBlank()) ? patientRepository.findAll() : patientRepository.search(query);
    }

    private static String require(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }
}
