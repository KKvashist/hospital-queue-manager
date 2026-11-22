package com.hospital.queue.service;

import com.hospital.queue.model.Patient;

/** Spring picks ONE implementation at startup based on the property app.notification.type. */
public interface NotificationService {
    void send(Patient patient, String message);
}
