-- Optional demo data. Run after the tables exist:  mysql -u root -p hospital_db < database/sample_data.sql
INSERT INTO doctors (name, specialization, department, available) VALUES
 ('Dr. Anita Rao',   'Cardiologist',      'Cardiology', TRUE),
 ('Dr. Vikram Sethi','Emergency Medicine','Emergency',  TRUE),
 ('Dr. Meera Nair',  'General Physician', 'General',    TRUE),
 ('Dr. Rahul Jain',  'Orthopedic Surgeon','Orthopedics',TRUE);

INSERT INTO patients (name, age, gender, contact, symptoms, registered_at) VALUES
 ('Rohit Sharma', 58, 'Male',   '9810000001', 'Severe chest pain and sweating', NOW(6)),
 ('Priya Singh',  27, 'Female', '9810000002', 'Mild fever and common cold',     NOW(6)),
 ('Amit Verma',   41, 'Male',   '9810000003', 'Fracture in left arm after fall',NOW(6)),
 ('Sana Khan',    34, 'Female', '9810000004', 'Difficulty breathing',           NOW(6));
