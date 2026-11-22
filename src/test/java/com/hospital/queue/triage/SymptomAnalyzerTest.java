package com.hospital.queue.triage;

import com.hospital.queue.model.EmergencyLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymptomAnalyzerTest {

    private final SymptomAnalyzer analyzer = new SymptomAnalyzer();

    @Test
    void emergencySymptomsSuggestCritical() {
        assertEquals(EmergencyLevel.CRITICAL, analyzer.analyze("Severe chest pain since morning").level());
        assertEquals(EmergencyLevel.CRITICAL, analyzer.analyze("difficulty breathing").level());
        assertEquals(EmergencyLevel.CRITICAL, analyzer.analyze("Patient found unconscious").level());
        assertEquals(EmergencyLevel.CRITICAL, analyzer.analyze("severe bleeding from leg").level());
    }

    @Test
    void normalSymptomsSuggestLow() {
        assertEquals(EmergencyLevel.LOW, analyzer.analyze("mild fever").level());
        assertEquals(EmergencyLevel.LOW, analyzer.analyze("common cold and cough").level());
    }

    @Test
    void mostSevereMatchWinsWhenSymptomsAreMixed() {
        assertEquals(EmergencyLevel.CRITICAL, analyzer.analyze("mild fever and chest pain").level());
    }

    @Test
    void blankSymptomsStillReturnASuggestionThatNeedsReview() {
        assertEquals(EmergencyLevel.LOW, analyzer.analyze("  ").level());
        assertEquals(EmergencyLevel.LOW, analyzer.analyze(null).level());
        assertTrue(analyzer.analyze(null).reason().contains("staff"));
    }
}
