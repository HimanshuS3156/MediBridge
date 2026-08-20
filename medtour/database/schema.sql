
-- ============================================================
-- MedTour India — Database Schema
-- Run this once in MySQL:  mysql -u root -p < schema.sql
-- ============================================================

create database btxvagdo6gi2no0xk2gt;
USE btxvagdo6gi2no0xk2gt;

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
('Apollo Global Care',         'Chennai',   'Multi-specialty hospital known for cardiac and orthopedic excellence.', 4.8, 'https://images.unsplash.com/photo-1586773860418-d37222d8fce3?w=600'),
('Fortis International',       'Delhi',     'JCI-accredited hospital with a dedicated international patient wing.',  4.6, 'https://images.unsplash.com/photo-1519494026892-80bbd2d6fd0d?w=600'),
('Radiance Cosmetic Institute', 'Mumbai',   'Specialist centre for cosmetic and hair-restoration procedures.',       4.7, 'https://images.unsplash.com/photo-1629909613654-28e377c37b09?w=600'),
('Sunrise Fertility & IVF',     'Bengaluru','Dedicated fertility clinic with high success rates and patient care.',  4.5, 'https://images.unsplash.com/photo-1631815589968-fdb09a223b1e?w=600'),
('MedLife Heart Institute',     'Chennai',  'Focused cardiac-care centre offering advanced interventional procedures.', 4.6, 'https://images.unsplash.com/photo-1551190822-a9333d879b1f?w=600'),
('CityCare Multispeciality',    'Delhi',    'General multi-specialty hospital serving both local and international patients.', 4.4, 'https://images.unsplash.com/photo-1587351021355-a479a299d2f9?w=600'),
('Global Ortho & Spine Center', 'Chennai',  'Dedicated orthopedic and spine-surgery hospital with advanced rehab facilities.', 4.7, 'https://images.unsplash.com/photo-1516549655169-df83a0774514?w=600'),
('Lotus Aesthetics & Wellness', 'Mumbai',   'Cosmetic and wellness hospital offering advanced dermatology and plastic surgery.', 4.5, 'https://images.unsplash.com/photo-1551076805-e1869033e561?w=600'),
('Harmony Women & Fertility Center', 'Bengaluru', 'Specialist hospital for women''s health, fertility and maternity care.', 4.6, 'https://images.unsplash.com/photo-1538108149393-fbbd81895907?w=600');

INSERT INTO doctors (hospital_id, name, specialization, experience_years, image_url, consultation_fee_inr) VALUES
(1, 'Dr. Ramesh Iyer',      'Cardiac Surgery',        22, 'https://randomuser.me/api/portraits/men/32.jpg',   2500),
(1, 'Dr. Priya Nair',       'Orthopedic Surgery',      15, 'https://randomuser.me/api/portraits/women/44.jpg', 2000),
(2, 'Dr. Anil Kapoor',      'Oncology',                18, 'https://randomuser.me/api/portraits/men/56.jpg',   3000),
(3, 'Dr. Meera Shah',       'Cosmetic Surgery',        12, 'https://randomuser.me/api/portraits/women/68.jpg', 1800),
(3, 'Dr. Arjun Verma',      'Hair Restoration',        10, 'https://randomuser.me/api/portraits/men/23.jpg',   1500),
(4, 'Dr. Kavita Rao',       'Reproductive Medicine',   16, 'https://randomuser.me/api/portraits/women/21.jpg', 2200),
(5, 'Dr. Suresh Menon',     'Cardiology',              20, 'https://randomuser.me/api/portraits/men/41.jpg',   2400),
(6, 'Dr. Neha Gupta',       'General Surgery',         14, 'https://randomuser.me/api/portraits/women/52.jpg', 1900),
(7, 'Dr. Vikram Reddy',     'Spine Surgery',           17, 'https://randomuser.me/api/portraits/men/64.jpg',   2600),
(8, 'Dr. Rohan Deshmukh',   'Dermatology',             13, 'https://randomuser.me/api/portraits/men/71.jpg',   1700),
(9, 'Dr. Ananya Bhat',      'Gynecology',              19, 'https://randomuser.me/api/portraits/women/59.jpg', 2100);

INSERT INTO treatments (hospital_id, name, category, description, cost_min, cost_max, duration_days) VALUES
(1, 'Coronary Bypass Surgery (CABG)', 'Cardiac',    'Bypass surgery to restore blood flow to the heart.', 539500, 788500, 10),
(1, 'Total Knee Replacement',         'Orthopedic', 'Full knee joint replacement for advanced arthritis.', 456500, 622500, 12),
(1, 'Angioplasty with Stent',         'Cardiac',    'Minimally invasive procedure to open blocked coronary arteries.', 249500, 415500, 4),
(1, 'Hip Replacement Surgery',        'Orthopedic', 'Total hip joint replacement for severe joint damage.', 415500, 581500, 10),
(2, 'Rhinoplasty',                    'Cosmetic',    'Surgical reshaping of the nose.',                    182500, 315500, 7),
(2, 'Chemotherapy Cycle',             'Oncology',    'One complete cycle of chemotherapy treatment.',      124500, 290500, 5),
(2, 'Radiation Therapy',              'Oncology',    'Targeted radiation treatment for cancer.',           207500, 415500, 15),
(2, 'Liver Transplant',               'Transplant',  'Complete liver transplantation surgery.',            1245000, 1868000, 21),
(3, 'Hair Transplant (FUE)',          'Hair',        'Follicular unit extraction hair restoration.',       99500, 249000, 3),
(3, 'Full Mouth Dental Implants',     'Dental',      'Complete dental implant restoration.',               290500, 498000, 6),
(3, 'Liposuction',                    'Cosmetic',    'Body-contouring fat-removal procedure.',             124500, 290500, 4),
(3, 'Breast Augmentation',            'Cosmetic',    'Cosmetic breast enhancement surgery.',               165500, 332500, 5),
(4, 'IVF Treatment Cycle',            'Fertility',   'One complete in-vitro fertilisation cycle.',         207500, 373500, 21),
(4, 'ICSI Treatment',                 'Fertility',   'Intracytoplasmic sperm injection fertility procedure.', 232500, 415500, 21),
(4, 'Egg Freezing',                   'Fertility',   'Oocyte cryopreservation for future fertility use.',  149500, 290500, 3),
(4, 'Surrogacy Program',              'Fertility',   'Complete gestational surrogacy program.',            1660000, 2905000, 280),
(5, 'Pacemaker Implantation',         'Cardiac',     'Implantation of a permanent pacemaker device.',      373500, 622500, 5),
(5, 'Heart Valve Replacement',        'Cardiac',     'Surgical replacement of a damaged heart valve.',     622500, 954500, 12),
(5, 'Cardiac Catheterization',        'Cardiac',     'Diagnostic procedure to examine heart function.',    124500, 249000, 2),
(5, 'Bypass Graft Surgery',           'Cardiac',     'Alternative bypass surgery for coronary artery disease.', 539500, 788500, 10),
(6, 'Gallbladder Removal (Laparoscopic)', 'General', 'Minimally invasive laparoscopic cholecystectomy.',   165500, 290500, 4),
(6, 'Hernia Repair Surgery',          'General',     'Surgical repair of abdominal or inguinal hernia.',   124500, 249000, 3),
(6, 'Appendectomy',                   'General',     'Surgical removal of the appendix.',                  99500, 207500, 3),
(6, 'Bariatric Surgery',              'General',     'Weight-loss surgery for severe obesity.',            456500, 705500, 6),
(7, 'Spinal Fusion Surgery',          'Orthopedic',  'Surgical fusion of vertebrae to treat spinal instability.', 498000, 705500, 14),
(7, 'Disc Replacement Surgery',       'Orthopedic',  'Artificial disc replacement for spinal disorders.',  539500, 788500, 12),
(7, 'ACL Reconstruction',             'Orthopedic',  'Surgical reconstruction of the anterior cruciate ligament.', 207500, 373500, 6),
(7, 'Shoulder Arthroscopy',           'Orthopedic',  'Minimally invasive shoulder joint surgery.',         165500, 332500, 5),
(8, 'Laser Skin Resurfacing',         'Cosmetic',    'Laser treatment to improve skin texture and tone.',  74500, 165500, 2),
(8, 'Botox & Fillers',                'Cosmetic',    'Non-surgical facial rejuvenation treatment.',        49500, 124500, 1),
(8, 'Tummy Tuck (Abdominoplasty)',    'Cosmetic',    'Surgical removal of excess abdominal skin and fat.', 249000, 456500, 6),
(8, 'Scar Revision Surgery',          'Cosmetic',    'Surgical treatment to improve the appearance of scars.', 74500, 182500, 3),
(9, 'High-Risk Pregnancy Care',       'Maternity',   'Specialized monitoring and care for high-risk pregnancies.', 99500, 249000, 30),
(9, 'Laparoscopic Hysterectomy',      'Gynecology',  'Minimally invasive uterus removal surgery.',         207500, 373500, 5),
(9, 'Fibroid Removal Surgery',        'Gynecology',  'Surgical removal of uterine fibroids.',              165500, 332500, 4),
(9, 'Maternity Delivery Package',     'Maternity',   'Complete package covering delivery and postnatal care.', 124500, 290500, 5);

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
