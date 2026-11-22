-- Run once as a MySQL admin:  mysql -u root -p < database/create_database.sql
-- (The application also creates the tables itself on startup from src/main/resources/schema.sql.)
CREATE DATABASE IF NOT EXISTS hospital_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE hospital_db;

-- Tables for the Hospital Appointment and Emergency Queue Manager (MySQL / InnoDB).
-- Safe to run repeatedly: every statement uses IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS patients (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    age           INT          NOT NULL,
    gender        VARCHAR(20)  NOT NULL,
    contact       VARCHAR(100) NOT NULL,
    symptoms      VARCHAR(500) NOT NULL,
    registered_at DATETIME(6)  NOT NULL
);

CREATE TABLE IF NOT EXISTS doctors (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    specialization VARCHAR(100) NOT NULL,
    department     VARCHAR(100) NOT NULL,
    available      BOOLEAN      NOT NULL
);

CREATE TABLE IF NOT EXISTS appointments (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    patient_id      BIGINT       NOT NULL,
    doctor_id       BIGINT       NULL,
    suggested_level VARCHAR(10)  NOT NULL,
    final_level     VARCHAR(10)  NOT NULL,
    reviewed_by     VARCHAR(100) NOT NULL,
    status          VARCHAR(15)  NOT NULL,
    request_time    DATETIME(6)  NOT NULL,
    completed_at    DATETIME(6)  NULL,
    FOREIGN KEY (patient_id) REFERENCES patients (id),
    FOREIGN KEY (doctor_id)  REFERENCES doctors (id)
);

CREATE TABLE IF NOT EXISTS consultation_history (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id BIGINT       NOT NULL UNIQUE,
    patient_id     BIGINT       NOT NULL,
    doctor_id      BIGINT       NOT NULL,
    diagnosis      VARCHAR(255) NOT NULL,
    notes          VARCHAR(500) NOT NULL,
    consulted_at   DATETIME(6)  NOT NULL,
    FOREIGN KEY (appointment_id) REFERENCES appointments (id),
    FOREIGN KEY (patient_id)     REFERENCES patients (id),
    FOREIGN KEY (doctor_id)      REFERENCES doctors (id)
);
