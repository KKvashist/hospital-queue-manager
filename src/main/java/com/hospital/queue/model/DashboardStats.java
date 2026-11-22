package com.hospital.queue.model;

import java.util.List;

public record DashboardStats(long totalPatients, long totalAppointments, long waiting, long inProgress,
                             long treated, long emergencyPatients, long availableDoctors,
                             List<DoctorWorkload> workload, AppointmentView highestPriorityPending) { }
