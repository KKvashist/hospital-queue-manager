package com.hospital.queue.controller;

import com.hospital.queue.model.Doctor;
import com.hospital.queue.service.DoctorService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/doctors")
public class DoctorController {

    public record DoctorRequest(String name, String specialization, String department) { }

    public record AvailabilityRequest(boolean available) { }

    private final DoctorService doctorService;

    public DoctorController(DoctorService doctorService) {
        this.doctorService = doctorService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Doctor add(@RequestBody DoctorRequest r) {
        return doctorService.addDoctor(r.name(), r.specialization(), r.department());
    }

    @GetMapping
    public List<Doctor> list() {
        return doctorService.list();
    }

    @GetMapping("/by-department")
    public Map<String, List<Doctor>> byDepartment() {
        return doctorService.getDoctorsByDepartment();
    }

    @PutMapping("/{id}/availability")
    public Doctor setAvailability(@PathVariable long id, @RequestBody AvailabilityRequest r) {
        return doctorService.setAvailability(id, r.available());
    }
}
