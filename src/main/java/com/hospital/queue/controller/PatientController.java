package com.hospital.queue.controller;

import com.hospital.queue.model.AppointmentView;
import com.hospital.queue.model.Patient;
import com.hospital.queue.service.AppointmentService;
import com.hospital.queue.service.PatientService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    public record PatientRequest(String name, int age, String gender, String contact, String symptoms) { }

    private final PatientService patientService;
    private final AppointmentService appointmentService;

    public PatientController(PatientService patientService, AppointmentService appointmentService) {
        this.patientService = patientService;
        this.appointmentService = appointmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Patient register(@RequestBody PatientRequest r) {
        return patientService.register(r.name(), r.age(), r.gender(), r.contact(), r.symptoms());
    }

    @GetMapping
    public List<Patient> search(@RequestParam(required = false) String q) {
        return patientService.search(q);
    }

    @GetMapping("/{id}")
    public Patient get(@PathVariable long id) {
        return patientService.get(id);
    }

    @GetMapping("/{id}/appointments")
    public List<AppointmentView> appointmentHistory(@PathVariable long id) {
        patientService.get(id);   // 404 if the patient does not exist
        return appointmentService.history(null, id);
    }
}
