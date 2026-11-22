package com.hospital.queue.repository;

import com.hospital.queue.model.Doctor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DoctorRepository {

    private static final RowMapper<Doctor> MAPPER = (rs, i) -> new Doctor(
            rs.getLong("id"), rs.getString("name"), rs.getString("specialization"),
            rs.getString("department"), rs.getBoolean("available"));

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public DoctorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("doctors").usingGeneratedKeyColumns("id");
    }

    public Doctor save(Doctor d) {
        Map<String, Object> row = new HashMap<>();
        row.put("name", d.name());
        row.put("specialization", d.specialization());
        row.put("department", d.department());
        row.put("available", d.available());
        long id = insert.executeAndReturnKey(row).longValue();
        return new Doctor(id, d.name(), d.specialization(), d.department(), d.available());
    }

    public Optional<Doctor> findById(long id) {
        return jdbc.query("SELECT * FROM doctors WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Doctor> findAll() {
        return jdbc.query("SELECT * FROM doctors ORDER BY id", MAPPER);
    }

    public int updateAvailability(long id, boolean available) {
        return jdbc.update("UPDATE doctors SET available = ? WHERE id = ?", available, id);
    }

    /** Doctors that are on duty AND not currently in a consultation. */
    public long countFreeDoctors() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM doctors d WHERE d.available = TRUE AND NOT EXISTS "
                + "(SELECT 1 FROM appointments a WHERE a.doctor_id = d.id AND a.status = 'IN_PROGRESS')", Long.class);
    }
}
