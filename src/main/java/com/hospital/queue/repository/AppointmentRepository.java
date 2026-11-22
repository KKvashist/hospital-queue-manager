package com.hospital.queue.repository;

import com.hospital.queue.model.Appointment;
import com.hospital.queue.model.AppointmentStatus;
import com.hospital.queue.model.AppointmentView;
import com.hospital.queue.model.DoctorWorkload;
import com.hospital.queue.model.EmergencyLevel;
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
import java.util.Optional;

@Repository
public class AppointmentRepository {

    private static final String VIEW_SQL =
            "SELECT a.id, a.patient_id, p.name AS patient_name, a.doctor_id, d.name AS doctor_name, "
          + "a.suggested_level, a.final_level, a.reviewed_by, a.status, a.request_time, a.completed_at "
          + "FROM appointments a JOIN patients p ON p.id = a.patient_id "
          + "LEFT JOIN doctors d ON d.id = a.doctor_id ";

    private static final RowMapper<AppointmentView> VIEW_MAPPER = (rs, i) -> {
        long doctorId = rs.getLong("doctor_id");
        Long doctor = rs.wasNull() ? null : doctorId;
        Timestamp done = rs.getTimestamp("completed_at");
        return new AppointmentView(rs.getLong("id"), rs.getLong("patient_id"), rs.getString("patient_name"),
                doctor, rs.getString("doctor_name"),
                EmergencyLevel.valueOf(rs.getString("suggested_level")),
                EmergencyLevel.valueOf(rs.getString("final_level")), rs.getString("reviewed_by"),
                AppointmentStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("request_time").toInstant(), done == null ? null : done.toInstant());
    };

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public AppointmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("appointments").usingGeneratedKeyColumns("id");
    }

    /** Inserts a new WAITING appointment and returns its generated id. */
    public long insert(long patientId, EmergencyLevel suggested, EmergencyLevel finalLevel,
                       String reviewedBy, Instant requestTime) {
        Map<String, Object> row = new HashMap<>();
        row.put("patient_id", patientId);
        row.put("suggested_level", suggested.name());
        row.put("final_level", finalLevel.name());
        row.put("reviewed_by", reviewedBy);
        row.put("status", AppointmentStatus.WAITING.name());
        row.put("request_time", Timestamp.from(requestTime));
        return insert.executeAndReturnKey(row).longValue();
    }

    /** Third guard against double assignment: only a row that is still WAITING can be updated (returns 1 or 0). */
    public int assignToDoctor(long appointmentId, long doctorId) {
        return jdbc.update("UPDATE appointments SET status = 'IN_PROGRESS', doctor_id = ? "
                + "WHERE id = ? AND status = 'WAITING'", doctorId, appointmentId);
    }

    /** Step 1 of the completion transaction. Only an IN_PROGRESS row can be completed (returns 1 or 0). */
    public int markCompleted(long appointmentId, Instant completedAt) {
        return jdbc.update("UPDATE appointments SET status = 'COMPLETED', completed_at = ? "
                + "WHERE id = ? AND status = 'IN_PROGRESS'", Timestamp.from(completedAt), appointmentId);
    }

    public Optional<AppointmentView> findViewById(long id) {
        return jdbc.query(VIEW_SQL + "WHERE a.id = ?", VIEW_MAPPER, id).stream().findFirst();
    }

    /** Appointment history. Both filters are optional (null = no filter). */
    public List<AppointmentView> findViews(AppointmentStatus status, Long patientId) {
        StringBuilder sql = new StringBuilder(VIEW_SQL).append("WHERE 1 = 1 ");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append("AND a.status = ? ");
            args.add(status.name());
        }
        if (patientId != null) {
            sql.append("AND a.patient_id = ? ");
            args.add(patientId);
        }
        sql.append("ORDER BY a.id DESC");
        return jdbc.query(sql.toString(), VIEW_MAPPER, args.toArray());
    }

    /** Used at startup to rebuild the in-memory priority queue from persisted WAITING rows. */
    public List<Appointment> findWaiting() {
        return jdbc.query("SELECT id, patient_id, final_level, request_time FROM appointments WHERE status = 'WAITING'",
                (rs, i) -> new Appointment(rs.getLong("id"), rs.getLong("patient_id"),
                        EmergencyLevel.valueOf(rs.getString("final_level")),
                        rs.getTimestamp("request_time").toInstant()));
    }

    public boolean doctorHasInProgress(long doctorId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE doctor_id = ? AND status = 'IN_PROGRESS'",
                Long.class, doctorId);
        return n != null && n > 0;
    }

    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments", Long.class);
    }

    public long countByStatus(AppointmentStatus status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE status = ?", Long.class, status.name());
    }

    /** Emergency = final level CRITICAL or HIGH. */
    public long countEmergency() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM appointments WHERE final_level IN ('CRITICAL', 'HIGH')",
                Long.class);
    }

    public List<DoctorWorkload> workloadPerDoctor() {
        return jdbc.query("SELECT d.id, d.name, d.department, d.available, "
                + "COALESCE(SUM(CASE WHEN a.status = 'IN_PROGRESS' THEN 1 ELSE 0 END), 0) AS in_progress, "
                + "COALESCE(SUM(CASE WHEN a.status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS treated "
                + "FROM doctors d LEFT JOIN appointments a ON a.doctor_id = d.id "
                + "GROUP BY d.id, d.name, d.department, d.available ORDER BY d.id",
                (rs, i) -> new DoctorWorkload(rs.getLong("id"), rs.getString("name"), rs.getString("department"),
                        rs.getBoolean("available"), rs.getLong("in_progress"), rs.getLong("treated")));
    }
}
