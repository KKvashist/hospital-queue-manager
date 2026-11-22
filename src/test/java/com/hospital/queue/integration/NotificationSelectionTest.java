package com.hospital.queue.integration;

import com.hospital.queue.service.ConsoleNotificationService;
import com.hospital.queue.service.EmailNotificationService;
import com.hospital.queue.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Requirement 5: both NotificationService implementations exist and configuration chooses between them. */
class NotificationSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConsoleNotificationService.class, EmailNotificationService.class);

    @Test
    void consoleImplementationIsTheDefault() {
        runner.run(ctx -> {
            assertEquals(1, ctx.getBeansOfType(NotificationService.class).size());
            assertInstanceOf(ConsoleNotificationService.class, ctx.getBean(NotificationService.class));
        });
    }

    @Test
    void emailImplementationIsUsedWhenConfigured() {
        runner.withPropertyValues("app.notification.type=email").run(ctx -> {
            assertEquals(1, ctx.getBeansOfType(NotificationService.class).size());
            assertInstanceOf(EmailNotificationService.class, ctx.getBean(NotificationService.class));
        });
    }
}
