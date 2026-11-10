CREATE SCHEMA IF NOT EXISTS platform;

CREATE TABLE platform.app_user (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email           text NOT NULL,
    display_name    text,
    password_hash   text,       
    totp_secret     text,              
    failed_attempts int NOT NULL DEFAULT 0,
    locked_until    timestamptz,
    disabled        boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX app_user_email_idx ON platform.app_user (lower(email));

CREATE TABLE platform.session (
    token_hash   bytea PRIMARY KEY,      
    user_id      uuid NOT NULL REFERENCES platform.app_user(id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    ip           text,
    user_agent   text
);
CREATE INDEX session_user_idx    ON platform.session (user_id);
CREATE INDEX session_expires_idx ON platform.session (expires_at);

CREATE TABLE platform.audit_log (
    id          bigserial PRIMARY KEY,
    event       text NOT NULL,
    user_id     uuid,
    email       text,
    ip          text,
    user_agent  text,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_user_idx ON platform.audit_log (user_id, created_at);
