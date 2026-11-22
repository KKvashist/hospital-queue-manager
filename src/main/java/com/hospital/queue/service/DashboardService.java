package com.hospital.queue.service;

import com.hospital.queue.core.AppointmentQueue;
import com.hospital.queue.model.Appointment;
import com.hospital.queue.model.AppointmentStatus;
import com.hospital.queue.model.AppointmentView;
import com.hospital.queue.model.DashboardStats;
import com.hospital.queue.repository.AppointmentRepository;
import com.hospital.queue.repository.DoctorRepository;
import com.hospital.queue.repository.PatientRepository;
import org.springframework.stereotype.Service;

/** Requirement 6: every figure is computed from the database (the top pending case from the live queue). */
@Service
public class DashboardService {

    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentQueue queue;

    public DashboardService(PatientRepository patientRepository, DoctorRepository doctorRepository,
                            AppointmentRepository appointmentRepository, AppointmentQueue queue) {
        this.patientRepository = patientRepository;
        this.doctorRepository = doctorRepository;
        this.appointmentRepository = appointmentRepository;
        this.queue = queue;
    }

    public DashboardStats stats() {
        Appointment top = queue.peek();
        AppointmentView highest = top == null ? null : appointmentRepository.findViewById(top.getId()).orElse(null);
        return new DashboardStats(
                patientRepository.count(),
                appointmentRepository.count(),
                appointmentRepository.countByStatus(AppointmentStatus.WAITING),
                appointmentRepository.countByStatus(AppointmentStatus.IN_PROGRESS),
                appointmentRepository.countByStatus(AppointmentStatus.COMPLETED),
                appointmentRepository.countEmergency(),
                doctorRepository.countFreeDoctors(),
                appointmentRepository.workloadPerDoctor(),
                highest);
    }
}
