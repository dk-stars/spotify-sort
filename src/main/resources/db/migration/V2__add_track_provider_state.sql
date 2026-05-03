-- Add track_provider_state for existing deployments that had ddl-auto=update.
-- New deployments will have this table from V1__baseline.sql; this migration is a safety net.

CREATE TABLE IF NOT EXISTS track_provider_state (
    id               BIGSERIAL PRIMARY KEY,
    track_spotify_id VARCHAR(255)    NOT NULL,
    source           VARCHAR(50)     NOT NULL,
    status           VARCHAR(50)     NOT NULL,
    fetched_at       TIMESTAMPTZ     NOT NULL,
    refresh_after    TIMESTAMPTZ     NOT NULL,
    model_name       VARCHAR(128),
    match_strategy   VARCHAR(64),
    reason_code      VARCHAR(64),
    confidence       DOUBLE PRECISION,
    CONSTRAINT uq_tps_track_source UNIQUE (track_spotify_id, source)
);
