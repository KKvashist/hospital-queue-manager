package com.hospital.queue.service;

import com.hospital.queue.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Selected with app.notification.type=email.
 * LIMITATION: this is a SIMULATED email sender (no SMTP server is contacted) so the project needs no
 * extra dependency. It builds the email and keeps it in an in-memory outbox; replacing the log line with
 * JavaMailSender is the only change needed for real delivery.
 */
@Service
@ConditionalOnProperty(name = "app.notification.type", havingValue = "email")
public class EmailNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);
    private final List<String> outbox = new CopyOnWriteArrayList<>();

    @Override
    public void send(Patient patient, String message) {
        String email = "To: " + patient.contact() + " | Subject: Hospital update | Body: Dear " + patient.name()
                + ", " + message;
        outbox.add(email);
        log.info("[EMAIL] {}", email);
    }

    public List<String> getOutbox() {
        return List.copyOf(outbox);
    }
}
