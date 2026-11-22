package com.hospital.queue.repository;

import com.hospital.queue.model.ConsultationRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ConsultationRepository {

    private static final RowMapper<ConsultationRecord> MAPPER = (rs, i) -> new ConsultationRecord(
            rs.getLong("id"), rs.getLong("appointment_id"), rs.getLong("patient_id"), rs.getString("patient_name"),
            rs.getLong("doctor_id"), rs.getString("doctor_name"), rs.getString("diagnosis"), rs.getString("notes"),
            rs.getTimestamp("consulted_at").toInstant());

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public ConsultationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("consultation_history")
                .usingGeneratedKeyColumns("id");
    }

    /** Step 2 of the completion transaction. Returns the new history id. */
    public long insert(long appointmentId, long patientId, long doctorId, String diagnosis, String notes,
                       Instant consultedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("appointment_id", appointmentId);
        row.put("patient_id", patientId);
        row.put("doctor_id", doctorId);
        row.put("diagnosis", diagnosis);
        row.put("notes", notes);
        row.put("consulted_at", Timestamp.from(consultedAt));
        return insert.executeAndReturnKey(row).longValue();
    }

    /** Consultation history, newest first. patientId is optional (null = all patients). */
    public List<ConsultationRecord> findAll(Long patientId) {
        StringBuilder sql = new StringBuilder("SELECT c.id, c.appointment_id, c.patient_id, p.name AS patient_name, "
                + "c.doctor_id, d.name AS doctor_name, c.diagnosis, c.notes, c.consulted_at "
                + "FROM consultation_history c JOIN patients p ON p.id = c.patient_id "
                + "JOIN doctors d ON d.id = c.doctor_id ");
        List<Object> args = new ArrayList<>();
        if (patientId != null) {
            sql.append("WHERE c.patient_id = ? ");
            args.add(patientId);
        }
        sql.append("ORDER BY c.id DESC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}
