package com.hospital.queue.repository;

import com.hospital.queue.model.Patient;

import java.util.List;
import java.util.Optional;

public interface PatientRepository {
    Patient save(Patient patient);
    Optional<Patient> findById(long id);
    List<Patient> findAll();
    /** Matches id, or part of name / contact / symptoms. */
    List<Patient> search(String query);
    long count();
}
