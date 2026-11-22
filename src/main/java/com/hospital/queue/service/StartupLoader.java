package com.hospital.queue.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** After the database is ready: load the department map and rebuild the queue from persisted WAITING rows. */
@Component
public class StartupLoader implements ApplicationRunner {

    private final DoctorService doctorService;
    private final AppointmentService appointmentService;

    public StartupLoader(DoctorService doctorService, AppointmentService appointmentService) {
        this.doctorService = doctorService;
        this.appointmentService = appointmentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        doctorService.reloadDepartmentMap();
        appointmentService.reloadQueueFromDatabase();
    }
}
