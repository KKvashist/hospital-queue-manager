package com.hospital.queue.controller;

import com.hospital.queue.model.AppointmentStatus;
import com.hospital.queue.model.AppointmentView;
import com.hospital.queue.model.ConsultationRecord;
import com.hospital.queue.model.EmergencyLevel;
import com.hospital.queue.service.AppointmentService;
import com.hospital.queue.triage.SymptomAnalyzer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api")
public class AppointmentController {

    public record BookRequest(long patientId, EmergencyLevel suggestedLevel, EmergencyLevel finalLevel,
                              String reviewedBy) { }

    public record CallNextRequest(long doctorId) { }

    public record CompleteRequest(String diagnosis, String notes) { }

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping("/appointments/suggest/{patientId}")
    public SymptomAnalyzer.Suggestion suggest(@PathVariable long patientId) {
        return appointmentService.suggestPriority(patientId);
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentView book(@RequestBody BookRequest r) {
        return appointmentService.submit(r.patientId(), r.suggestedLevel(), r.finalLevel(), r.reviewedBy());
    }

    @GetMapping("/appointments")
    public List<AppointmentView> list(@RequestParam(required = false) AppointmentStatus status,
                                      @RequestParam(required = false) Long patientId) {
        return appointmentService.history(status, patientId);
    }

    @GetMapping("/appointments/queue")
    public List<AppointmentView> queue() {
        return appointmentService.waitingQueue();
    }

    /** 204 No Content when nobody is waiting. */
    @PostMapping("/appointments/call-next")
    public ResponseEntity<AppointmentView> callNext(@RequestBody CallNextRequest r) {
        return appointmentService.callNext(r.doctorId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/appointments/{id}/complete")
    public ConsultationRecord complete(@PathVariable long id, @RequestBody CompleteRequest r) {
        return appointmentService.complete(id, r.diagnosis(), r.notes());
    }

    @GetMapping("/consultations")
    public List<ConsultationRecord> consultations(@RequestParam(required = false) Long patientId) {
        return appointmentService.consultations(patientId);
    }
}
