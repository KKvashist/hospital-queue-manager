package com.hospital.queue.config;

import com.hospital.queue.core.AppointmentQueue;
import com.hospital.queue.triage.SymptomAnalyzer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the framework-free core classes as Spring singletons. */
@Configuration
public class AppConfig {

    /** One shared queue for the whole application - every desk and doctor thread uses this instance. */
    @Bean
    public AppointmentQueue appointmentQueue() {
        return new AppointmentQueue();
    }

    @Bean
    public SymptomAnalyzer symptomAnalyzer() {
        return new SymptomAnalyzer();
    }
}
