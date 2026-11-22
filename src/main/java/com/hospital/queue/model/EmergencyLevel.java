package com.hospital.queue.model;

/** Final emergency level assigned by medical staff. A higher weight means more urgent. */
public enum EmergencyLevel {
    CRITICAL(4), HIGH(3), MEDIUM(2), LOW(1);

    private final int weight;

    EmergencyLevel(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }
}
