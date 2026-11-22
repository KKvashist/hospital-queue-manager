package com.hospital.queue.service;

import com.hospital.queue.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Default implementation (app.notification.type=console or property missing): writes to the log. */
@Service
@ConditionalOnProperty(name = "app.notification.type", havingValue = "console", matchIfMissing = true)
public class ConsoleNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleNotificationService.class);

    @Override
    public void send(Patient patient, String message) {
        log.info("[CONSOLE] Notify {} (patient #{}): {}", patient.name(), patient.id(), message);
    }
}
