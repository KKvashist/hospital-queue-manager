package com.hospital.queue.triage;

import com.hospital.queue.model.EmergencyLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Very simple keyword-based priority SUGGESTION.
 * This is a software demonstration, not a medical tool: a nurse/doctor must always review the
 * suggestion and assign the final level. Rules are checked from most to least severe, and the
 * first match wins, so ambiguous text is deliberately triaged upwards rather than downwards.
 */
public class SymptomAnalyzer {

    public record Suggestion(EmergencyLevel level, String reason) { }

    private static final Map<EmergencyLevel, List<String>> RULES = new LinkedHashMap<>();

    static {
        RULES.put(EmergencyLevel.CRITICAL, List.of("chest pain", "difficulty breathing", "shortness of breath",
                "can't breathe", "cannot breathe", "unconscious", "unconsciousness", "fainted",
                "severe bleeding", "heavy bleeding", "seizure", "stroke"));
        RULES.put(EmergencyLevel.HIGH, List.of("high fever", "fracture", "broken bone", "severe pain",
                "vomiting blood", "burn"));
        RULES.put(EmergencyLevel.MEDIUM, List.of("vomiting", "dehydration", "infection", "abdominal pain",
                "moderate fever"));
        RULES.put(EmergencyLevel.LOW, List.of("mild fever", "common cold", "cold", "cough", "headache",
                "sore throat"));
    }

    public Suggestion analyze(String symptoms) {
        if (symptoms == null || symptoms.isBlank()) {
            return new Suggestion(EmergencyLevel.LOW, "No symptoms recorded - staff must assess the patient.");
        }
        String text = symptoms.toLowerCase().replace('\u2019', '\'');
        for (Map.Entry<EmergencyLevel, List<String>> rule : RULES.entrySet()) {
            for (String keyword : rule.getValue()) {
                if (text.contains(keyword)) {
                    return new Suggestion(rule.getKey(), "Matched symptom keyword: \"" + keyword + "\"");
                }
            }
        }
        return new Suggestion(EmergencyLevel.LOW, "No known keyword matched - staff must assess the patient.");
    }
}
