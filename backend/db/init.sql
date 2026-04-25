CREATE TABLE users (
    id            SERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(100),
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     INTEGER      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL,
    device_info VARCHAR(255),
    expires_at  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE slides (
    id          SERIAL PRIMARY KEY,
    user_id     INTEGER      NOT NULL REFERENCES users(id),
    filename    VARCHAR(255) NOT NULL,
    s3_path     VARCHAR(255) NOT NULL,
    patient_id  VARCHAR(100),
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE jobs (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    slide_id              INTEGER     NOT NULL REFERENCES slides(id),
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    patches_total         INTEGER     NOT NULL DEFAULT 0,
    patches_remaining     INTEGER     NOT NULL DEFAULT 0,
    total_eosinophil_count INTEGER    NOT NULL DEFAULT 0,
    max_hpf_count         INTEGER     NOT NULL DEFAULT 0,
    max_hpf_x             INTEGER,
    max_hpf_y             INTEGER,
    diagnosis             VARCHAR(20),
    report_path           TEXT,
    heatmap_path          TEXT,
    created_at            TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE TABLE patch_tasks (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID        NOT NULL REFERENCES jobs(id),
    minio_path      VARCHAR(512),
    x               INTEGER     NOT NULL,
    y               INTEGER     NOT NULL,
    width           INTEGER     NOT NULL,
    height          INTEGER     NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    eosinophil_count INTEGER    NOT NULL DEFAULT 0,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    heartbeat_at    TIMESTAMP,
    created_at      TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);

CREATE INDEX idx_patch_tasks_job_id ON patch_tasks(job_id);
CREATE INDEX idx_patch_tasks_status ON patch_tasks(status);

CREATE TABLE analysis_results (
    id               SERIAL  PRIMARY KEY,
    slide_id         INTEGER NOT NULL REFERENCES slides(id),
    job_id           UUID    NOT NULL REFERENCES jobs(id),
    eosinophil_count INTEGER NOT NULL DEFAULT 0,
    detections       JSONB,
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO users (email, password_hash, full_name, role)
VALUES ('admin@hospital.com',
        '$2a$12$BBqpeicbGUtRoWd/R24gz.ugsXQ96C.Pt4JfJz2WbpzXGoqGNFYdW',
        'Administrator', 'ADMIN');