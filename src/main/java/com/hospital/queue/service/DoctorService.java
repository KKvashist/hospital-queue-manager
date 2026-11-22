package com.hospital.queue.service;

import com.hospital.queue.model.Doctor;
import com.hospital.queue.repository.DoctorRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class DoctorService {

    private final DoctorRepository doctorRepository;

    /** Requirement 1: department-wise doctors kept in a Map (department name -> doctors). Rebuilt after every change. */
    private volatile Map<String, List<Doctor>> doctorsByDepartment = Map.of();

    public DoctorService(DoctorRepository doctorRepository) {
        this.doctorRepository = doctorRepository;
    }

    public Doctor addDoctor(String name, String specialization, String department) {
        Doctor saved = doctorRepository.save(new Doctor(null, require(name, "Name"),
                require(specialization, "Specialization"), require(department, "Department"), true));
        reloadDepartmentMap();
        return saved;
    }

    public Doctor getById(long id) {
        return doctorRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Doctor not found: " + id));
    }

    public List<Doctor> list() {
        return doctorRepository.findAll();
    }

    public Map<String, List<Doctor>> getDoctorsByDepartment() {
        return doctorsByDepartment;
    }

    public Doctor setAvailability(long id, boolean available) {
        if (doctorRepository.updateAvailability(id, available) == 0) {
            throw new NoSuchElementException("Doctor not found: " + id);
        }
        reloadDepartmentMap();
        return getById(id);
    }

    /** Rebuilds the department Map from the database and swaps it in atomically. */
    public void reloadDepartmentMap() {
        Map<String, List<Doctor>> map = new HashMap<>();
        for (Doctor d : doctorRepository.findAll()) {
            map.computeIfAbsent(d.department(), k -> new java.util.ArrayList<>()).add(d);
        }
        map.replaceAll((dept, doctors) -> Collections.unmodifiableList(doctors));
        doctorsByDepartment = Collections.unmodifiableMap(map);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank() || value.trim().length() > 100) {
            throw new IllegalArgumentException(field + " is required (max 100 characters)");
        }
        return value.trim();
    }
}
