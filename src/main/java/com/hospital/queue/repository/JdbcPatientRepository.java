package com.hospital.queue.repository;

import com.hospital.queue.model.Patient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcPatientRepository implements PatientRepository {

    private static final RowMapper<Patient> MAPPER = (rs, i) -> new Patient(
            rs.getLong("id"), rs.getString("name"), rs.getInt("age"), rs.getString("gender"),
            rs.getString("contact"), rs.getString("symptoms"), rs.getTimestamp("registered_at").toInstant());

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public JdbcPatientRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("patients").usingGeneratedKeyColumns("id");
    }

    @Override
    public Patient save(Patient p) {
        Map<String, Object> row = new HashMap<>();
        row.put("name", p.name());
        row.put("age", p.age());
        row.put("gender", p.gender());
        row.put("contact", p.contact());
        row.put("symptoms", p.symptoms());
        row.put("registered_at", Timestamp.from(p.registeredAt()));
        long id = insert.executeAndReturnKey(row).longValue();
        return new Patient(id, p.name(), p.age(), p.gender(), p.contact(), p.symptoms(), p.registeredAt());
    }

    @Override
    public Optional<Patient> findById(long id) {
        return jdbc.query("SELECT * FROM patients WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<Patient> findAll() {
        return jdbc.query("SELECT * FROM patients ORDER BY id DESC", MAPPER);
    }

    @Override
    public List<Patient> search(String query) {
        String like = "%" + query.toLowerCase().trim() + "%";
        long id = parseIdOrMinusOne(query);
        return jdbc.query("SELECT * FROM patients WHERE id = ? OR LOWER(name) LIKE ? OR contact LIKE ? "
                + "OR LOWER(symptoms) LIKE ? ORDER BY id DESC", MAPPER, id, like, like, like);
    }

    @Override
    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM patients", Long.class);
    }

    private static long parseIdOrMinusOne(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
