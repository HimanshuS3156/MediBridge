-- ============================================================
-- MedTour India — Database Schema
-- Run this once in MySQL:  mysql -u root -p < schema.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS medtour;
USE medtour;

-- ---------- Users (patients, doctors, admin) ----------
CREATE TABLE IF NOT EXISTS users (
    id                             INT AUTO_INCREMENT PRIMARY KEY,
    full_name                      VARCHAR(150)  NOT NULL,
    email                          VARCHAR(150)  NOT NULL UNIQUE,
    password_hash                  VARCHAR(200)  NOT NULL,          -- PBKDF2WithHmacSHA256 hash, never plain text
    phone                          VARCHAR(30),                     -- E.164 format, e.g. +919876543210
    country                        VARCHAR(80),
    role                           ENUM('PATIENT','DOCTOR','ADMIN') NOT NULL DEFAULT 'PATIENT',
    email_verified                 BOOLEAN NOT NULL DEFAULT FALSE,
    -- SHA-256 hex digest of the verification token, never the raw token — a DB leak alone can't
    -- be used to verify someone else's account. NULL once verified or before a token is issued.
    verification_token_hash        CHAR(64) NULL,
    verification_token_expires_at  TIMESTAMP NULL,
    created_at                     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uq_users_verification_token_hash (verification_token_hash)
);

-- ---------- Hospitals / Clinics ----------
CREATE TABLE IF NOT EXISTS hospitals (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(150)  NOT NULL,
    city          VARCHAR(100)  NOT NULL,
    description   TEXT,
    rating        DECIMAL(2,1)  DEFAULT 4.5,
    image_url     VARCHAR(300)
);

-- ---------- Doctors ----------
CREATE TABLE IF NOT EXISTS doctors (
    id                    INT AUTO_INCREMENT PRIMARY KEY,
    hospital_id           INT NOT NULL,
    user_id               INT NULL,                  -- linked login account, if the doctor self-registered
    name                  VARCHAR(150) NOT NULL,
    specialization        VARCHAR(150) NOT NULL,
    experience_years      INT DEFAULT 0,
    image_url             VARCHAR(300),
    consultation_fee_inr  DECIMAL(10,2) NOT NULL DEFAULT 0,  -- source of truth for the booking estimate
    FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_doctors_specialization (specialization)
);

-- ---------- Treatments / Services ----------
CREATE TABLE IF NOT EXISTS treatments (
    id            INT AUTO_INCREMENT PRIMARY KEY,
    hospital_id   INT NOT NULL,
    name          VARCHAR(150) NOT NULL,
    category      VARCHAR(80)  NOT NULL,
    description   TEXT,
    cost_min      INT DEFAULT 0,
    cost_max      INT DEFAULT 0,
    duration_days INT DEFAULT 1,
    FOREIGN KEY (hospital_id) REFERENCES hospitals(id) ON DELETE CASCADE
);

-- ---------- Appointment / Consultation Requests ----------
CREATE TABLE IF NOT EXISTS appointments (
    id                          INT AUTO_INCREMENT PRIMARY KEY,
    patient_user_id             INT NULL,               -- set when a logged-in patient books; null for guests
    patient_name                VARCHAR(150) NOT NULL,
    email                       VARCHAR(150) NOT NULL,
    phone                       VARCHAR(30)  NOT NULL,
    country                     VARCHAR(80),
    hospital_id                 INT,
    treatment_id                INT,
    doctor_id                   INT,
    preferred_date              DATE NOT NULL,
    message                     TEXT,
    airport_pickup              BOOLEAN NOT NULL DEFAULT FALSE,
    travel_assistance           BOOLEAN NOT NULL DEFAULT FALSE,
    consultation_fee_inr        DECIMAL(10,2) NOT NULL DEFAULT 0,
    airport_pickup_fee_inr      DECIMAL(10,2) NOT NULL DEFAULT 0,
    travel_assistance_fee_inr   DECIMAL(10,2) NOT NULL DEFAULT 0,
    estimated_total_inr         DECIMAL(10,2) NOT NULL DEFAULT 0,  -- backend-calculated, NEVER trust a client value
    status                      VARCHAR(30) DEFAULT 'Pending',     -- Pending, Confirmed, Rejected, Completed
    confirmation_email_sent_at  TIMESTAMP NULL,
    created_at                  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (patient_user_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (hospital_id)  REFERENCES hospitals(id)  ON DELETE SET NULL,
    FOREIGN KEY (treatment_id) REFERENCES treatments(id) ON DELETE SET NULL,
    FOREIGN KEY (doctor_id)    REFERENCES doctors(id)    ON DELETE SET NULL,
    INDEX idx_appointments_email (email),
    INDEX idx_appointments_status (status)
);

-- ---------- Doctor dashboard: in-app notifications ----------
CREATE TABLE IF NOT EXISTS notifications (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    doctor_id      INT NOT NULL,
    appointment_id INT NULL,
    title          VARCHAR(200) NOT NULL,
    message        VARCHAR(500) NOT NULL,
    is_read        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (doctor_id) REFERENCES doctors(id) ON DELETE CASCADE,
    FOREIGN KEY (appointment_id) REFERENCES appointments(id) ON DELETE SET NULL
);

-- ---------- Doctor dashboard: weekly availability ----------
CREATE TABLE IF NOT EXISTS doctor_availability (
    id           INT AUTO_INCREMENT PRIMARY KEY,
    doctor_id    INT NOT NULL,
    day_of_week  ENUM('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY') NOT NULL,
    start_time   TIME NOT NULL,
    end_time     TIME NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    FOREIGN KEY (doctor_id) REFERENCES doctors(id) ON DELETE CASCADE
);

-- ---------- Footer feedback (star rating + comment) ----------
CREATE TABLE IF NOT EXISTS feedback (
    id             INT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(150) NOT NULL,
    email          VARCHAR(150) NOT NULL,
    appointment_id INT NULL,
    rating         TINYINT NOT NULL,          -- 1 to 5
    comment        TEXT NOT NULL,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_feedback_rating CHECK (rating BETWEEN 1 AND 5),
    FOREIGN KEY (appointment_id) REFERENCES appointments(id) ON DELETE SET NULL
);

-- ============================================================
-- Sample data so the site is not empty on first run
-- ============================================================

INSERT INTO hospitals (name, city, description, rating, image_url) VALUES
('Apollo Global Care',        'Chennai',   'Multi-specialty hospital known for cardiac and orthopedic excellence.', 4.8, 'https://images.unsplash.com/photo-1586773860418-d37222d8fce3?w=600'),
('Fortis International',      'Delhi',     'JCI-accredited hospital with a dedicated international patient wing.',  4.6, 'https://images.unsplash.com/photo-1519494026892-80bbd2d6fd0d?w=600'),
('Radiance Cosmetic Institute','Mumbai',   'Specialist centre for cosmetic and hair-restoration procedures.',       4.7, 'https://images.unsplash.com/photo-1629909613654-28e377c37b09?w=600'),
('Sunrise Fertility & IVF',    'Bengaluru','Dedicated fertility clinic with high success rates and patient care.',  4.5, 'https://images.unsplash.com/photo-1631815589968-fdb09a223b1e?w=600');

INSERT INTO doctors (hospital_id, name, specialization, experience_years, image_url, consultation_fee_inr) VALUES
(1, 'Dr. Ramesh Iyer',      'Cardiac Surgery',        22, 'https://randomuser.me/api/portraits/men/32.jpg',   2500),
(1, 'Dr. Priya Nair',       'Orthopedic Surgery',      15, 'https://randomuser.me/api/portraits/women/44.jpg', 2000),
(2, 'Dr. Anil Kapoor',      'Oncology',                18, 'https://randomuser.me/api/portraits/men/56.jpg',   3000),
(3, 'Dr. Meera Shah',       'Cosmetic Surgery',        12, 'https://randomuser.me/api/portraits/women/68.jpg', 1800),
(3, 'Dr. Arjun Verma',      'Hair Restoration',        10, 'https://randomuser.me/api/portraits/men/23.jpg',   1500),
(4, 'Dr. Kavita Rao',       'Reproductive Medicine',   16, 'https://randomuser.me/api/portraits/women/21.jpg', 2200);

INSERT INTO treatments (hospital_id, name, category, description, cost_min, cost_max, duration_days) VALUES
(1, 'Coronary Bypass Surgery (CABG)', 'Cardiac',    'Bypass surgery to restore blood flow to the heart.', 539500, 788500, 10),
(1, 'Total Knee Replacement',         'Orthopedic', 'Full knee joint replacement for advanced arthritis.', 456500, 622500, 12),
(2, 'Rhinoplasty',                    'Cosmetic',    'Surgical reshaping of the nose.',                    182500, 315500, 7),
(3, 'Hair Transplant (FUE)',          'Hair',        'Follicular unit extraction hair restoration.',       99500, 249000, 3),
(3, 'Full Mouth Dental Implants',     'Dental',      'Complete dental implant restoration.',               290500, 498000, 6),
(4, 'IVF Treatment Cycle',            'Fertility',   'One complete in-vitro fertilisation cycle.',         207500, 373500, 21);

-- Sample admin login for the appointment-triage dashboard (admin.html).
-- Email: admin@medtour.in   Password: ChangeMe123!
-- Hashed with this backend's own PasswordUtil (PBKDF2WithHmacSHA256, 100000 iterations) —
-- see backend/src/medtour/PasswordUtil.java. Format: pbkdf2_sha256$<iterations>$<saltB64>$<hashB64>
-- CHANGE THIS PASSWORD before this ever goes anywhere near production.
INSERT INTO users (full_name, email, password_hash, phone, country, role, email_verified) VALUES
('Platform Admin', 'admin@medtour.in', 'pbkdf2_sha256$100000$dtZMEtinOo3hcQxfBQUd9w==$mFhXOFHwO3pO7gX4tBpwHM5OzMwCADvg89ik5HFMWOs=', '+911234567890', 'India', 'ADMIN', TRUE);

-- ============================================================
-- Done. Tables: users, hospitals, doctors, treatments, appointments, feedback,
--               notifications, doctor_availability
-- ============================================================
