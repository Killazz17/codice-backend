-- ============================================================
--  Códice · PostgreSQL schema v6 · Third Normal Form
--  One database: 15 relational tables + 4 non-relational
--  jsonb columns (raw AI output).
--
--  Business Rules & Features in v6:
--  1. Row Level Security (RLS) enabled on all 15 tables.
--  2. Permissions restricted to service_role (anon/authenticated/publishable key revoked).
--  3. Added course.syllabus_published_at for syllabus publishing state (issue #32).
--  4. Added note.client_request_id (UNIQUE) for retry idempotency on upload.
--  5. Referential integrity ON DELETE actions & CHECK constraints.
-- ============================================================

-- ---------- 1. Authentication and Authorization ----------

CREATE TABLE users (
    id             BIGSERIAL PRIMARY KEY,
    first_name     VARCHAR(255) NOT NULL,
    last_name      VARCHAR(255) NOT NULL,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password       VARCHAR(255) NOT NULL,
    enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    token_expired  BOOLEAN     NOT NULL DEFAULT FALSE,
    create_date    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE role (
    id    BIGSERIAL PRIMARY KEY,
    name  VARCHAR(50) NOT NULL UNIQUE
          CHECK (name IN ('ROLE_STUDENT', 'ROLE_TEACHER'))
);

CREATE TABLE privilege (
    id    BIGSERIAL PRIMARY KEY,
    name  VARCHAR(80) NOT NULL UNIQUE
);

CREATE TABLE user_role (
    user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id  BIGINT NOT NULL REFERENCES role(id)  ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE role_privilege (
    role_id       BIGINT NOT NULL REFERENCES role(id)      ON DELETE CASCADE,
    privilege_id  BIGINT NOT NULL REFERENCES privilege(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, privilege_id)
);

-- ---------- 2. Courses, Groups and Enrollment ----------

CREATE TABLE course (
    id                    BIGSERIAL PRIMARY KEY,
    code                  VARCHAR(20)  NOT NULL UNIQUE,            -- e.g. 'EIF411'
    name                  VARCHAR(255) NOT NULL,
    color                 VARCHAR(20)  NOT NULL DEFAULT '#C8F250',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    extracted_syllabus    JSONB,                                   -- AI call 1
    syllabus_published_at TIMESTAMPTZ,                             -- Syllabus publication timestamp (Issue #32)
    teacher_id            BIGINT REFERENCES users(id) ON DELETE SET NULL  -- Course owner (V3, SVC-02)
);

CREATE TABLE student_group (
    id                 BIGSERIAL PRIMARY KEY,
    course_id          BIGINT NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    teacher_id         BIGINT NOT NULL REFERENCES users(id)  ON DELETE CASCADE,
    number             VARCHAR(10)  NOT NULL,                    -- e.g. '01', '02'
    period             VARCHAR(50)  NOT NULL,
    schedule           VARCHAR(255),
    invite_code        VARCHAR(30)  NOT NULL UNIQUE,              -- e.g. 'EIF411-01-7K2A'
    requires_approval  BOOLEAN      NOT NULL DEFAULT TRUE,
    invite_expires_at  TIMESTAMPTZ,
    UNIQUE (course_id, teacher_id, number)
);

CREATE TABLE enrollment (
    id            BIGSERIAL PRIMARY KEY,
    group_id      BIGINT NOT NULL REFERENCES student_group(id) ON DELETE CASCADE,
    course_id     BIGINT NOT NULL REFERENCES course(id)        ON DELETE CASCADE,
    student_id    BIGINT NOT NULL REFERENCES users(id)        ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED')),
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    UNIQUE (group_id, student_id)
);

-- Trigger to keep enrollment.course_id consistent with student_group.course_id
CREATE OR REPLACE FUNCTION set_enrollment_course_id() RETURNS TRIGGER AS $$
BEGIN
    SELECT course_id INTO NEW.course_id FROM student_group WHERE id = NEW.group_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_enrollment_course_id
BEFORE INSERT OR UPDATE OF group_id ON enrollment
FOR EACH ROW EXECUTE FUNCTION set_enrollment_course_id();

-- Rule: a student can have at most one ACTIVE enrollment per course
CREATE UNIQUE INDEX uq_enrollment_one_active_group_per_course
ON enrollment (course_id, student_id)
WHERE status = 'ACTIVE';

-- ---------- 3. Syllabus: Module -> Subtopic -> Objective ----------

CREATE TABLE module (
    id         BIGSERIAL PRIMARY KEY,
    course_id  BIGINT NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    number     INT NOT NULL,
    title      VARCHAR(255) NOT NULL,
    UNIQUE (course_id, number)
);

CREATE TABLE subtopic (
    id         BIGSERIAL PRIMARY KEY,
    module_id  BIGINT NOT NULL REFERENCES module(id) ON DELETE CASCADE,
    code       VARCHAR(20)  NOT NULL,                            -- e.g. '4.4'
    title      VARCHAR(255) NOT NULL,
    position   INT NOT NULL DEFAULT 0,
    UNIQUE (module_id, code)
);

CREATE TABLE subtopic_objective (
    id           BIGSERIAL PRIMARY KEY,
    subtopic_id  BIGINT NOT NULL REFERENCES subtopic(id) ON DELETE CASCADE,
    description  VARCHAR(500) NOT NULL
);

-- ---------- 4. Student Work ----------

CREATE TABLE note (
    id                BIGSERIAL PRIMARY KEY,
    enrollment_id     BIGINT NOT NULL REFERENCES enrollment(id) ON DELETE CASCADE,
    subtopic_id       BIGINT REFERENCES subtopic(id) ON DELETE SET NULL,
    client_request_id VARCHAR(100) UNIQUE,                          -- Upload idempotency (retry deduplication)
    confirmed_text    TEXT,
    sync_status       VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
                      CHECK (sync_status IN ('QUEUED','UPLOADING','SYNCED','FAILED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    transcription     JSONB,                                       -- AI call 2
    audit             JSONB                                        -- AI call 3
);

CREATE TABLE note_page (
    id          BIGSERIAL PRIMARY KEY,
    note_id     BIGINT NOT NULL REFERENCES note(id) ON DELETE CASCADE,
    page_index  INT NOT NULL,
    file_path   VARCHAR(500) NOT NULL,
    UNIQUE (note_id, page_index)
);

CREATE TABLE coverage (
    id              BIGSERIAL PRIMARY KEY,
    student_id      BIGINT NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    subtopic_id     BIGINT NOT NULL REFERENCES subtopic(id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'NO_NOTES'
                    CHECK (status IN ('NO_NOTES','PARTIAL','COVERED','REINFORCED')),
    justification   VARCHAR(500),
    note_origin_id  BIGINT REFERENCES note(id) ON DELETE SET NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (student_id, subtopic_id)
);

CREATE TABLE review (
    id            BIGSERIAL PRIMARY KEY,
    student_id    BIGINT NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    subtopic_id   BIGINT NOT NULL REFERENCES subtopic(id) ON DELETE CASCADE,
    interval_day  INT NOT NULL DEFAULT 1 CHECK (interval_day IN (1,3,7,14,30)),
    next_date     DATE NOT NULL,
    result        VARCHAR(20) CHECK (result IN ('PENDING','PASSED','FAILED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    questions     JSONB                                          -- AI call 4
);

-- ---------- 5. Indexes ----------

CREATE INDEX idx_group_course      ON student_group(course_id);
CREATE INDEX idx_group_teacher     ON student_group(teacher_id);
CREATE INDEX idx_enrollment_group  ON enrollment(group_id, status);
CREATE INDEX idx_enrollment_course ON enrollment(course_id, student_id);
CREATE INDEX idx_module_course     ON module(course_id, number);
CREATE INDEX idx_subtopic_module   ON subtopic(module_id, position);
CREATE INDEX idx_note_enrollment   ON note(enrollment_id, created_at DESC);
CREATE INDEX idx_note_page_note    ON note_page(note_id, page_index);
CREATE INDEX idx_coverage_subtopic ON coverage(subtopic_id, status);
CREATE INDEX idx_review_due        ON review(student_id, next_date);

-- GIN indexes for JSONB columns
CREATE INDEX idx_note_transcription ON note   USING GIN (transcription);
CREATE INDEX idx_note_audit         ON note   USING GIN (audit);
CREATE INDEX idx_review_questions   ON review USING GIN (questions);
CREATE INDEX idx_course_syllabus    ON course USING GIN (extracted_syllabus);

-- ---------- 6. Row Level Security (RLS) ----------

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE role ENABLE ROW LEVEL SECURITY;
ALTER TABLE privilege ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_privilege ENABLE ROW LEVEL SECURITY;
ALTER TABLE course ENABLE ROW LEVEL SECURITY;
ALTER TABLE student_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE enrollment ENABLE ROW LEVEL SECURITY;
ALTER TABLE module ENABLE ROW LEVEL SECURITY;
ALTER TABLE subtopic ENABLE ROW LEVEL SECURITY;
ALTER TABLE subtopic_objective ENABLE ROW LEVEL SECURITY;
ALTER TABLE note ENABLE ROW LEVEL SECURITY;
ALTER TABLE note_page ENABLE ROW LEVEL SECURITY;
ALTER TABLE coverage ENABLE ROW LEVEL SECURITY;
ALTER TABLE review ENABLE ROW LEVEL SECURITY;

-- ---------- 7. Permissions ----------

-- Create roles if executing outside Supabase environment (e.g. standard local Postgres)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'service_role') THEN
        CREATE ROLE service_role;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anon') THEN
        CREATE ROLE anon;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authenticated') THEN
        CREATE ROLE authenticated;
    END IF;
END $$;

-- Revoke permissions from public / anonymous / authenticated roles to prevent publishable key exposure
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, anon, authenticated;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, anon, authenticated;

-- Grant permissions exclusively to service_role and postgres superuser
GRANT USAGE ON SCHEMA public TO service_role, postgres;
GRANT ALL ON ALL TABLES IN SCHEMA public TO service_role, postgres;
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO service_role, postgres;
