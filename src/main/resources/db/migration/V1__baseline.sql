-- Baseline schema: all tables as of the initial LLM integration release.
-- For new deployments: Flyway runs this file to create the full schema.
-- For existing deployments upgrading from ddl-auto=update:
--   set spring.flyway.baseline-on-migrate=true on the first startup; Flyway
--   will mark this file as already applied and track future migrations from V2.

CREATE TABLE IF NOT EXISTS users (
    id               BIGSERIAL PRIMARY KEY,
    spotify_id       VARCHAR(255) NOT NULL UNIQUE,
    display_name     VARCHAR(255),
    avatar_url       VARCHAR(255),
    access_token     VARCHAR(1024) NOT NULL,
    refresh_token    VARCHAR(1024) NOT NULL,
    token_expires_at TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS artists (
    id            BIGSERIAL PRIMARY KEY,
    spotify_id    VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    first_seen_at TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_artists_spotify_id ON artists (spotify_id);

CREATE TABLE IF NOT EXISTS tracks (
    id            BIGSERIAL PRIMARY KEY,
    spotify_id    VARCHAR(255) NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    uri           VARCHAR(255) NOT NULL,
    first_seen_at TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tracks_spotify_id ON tracks (spotify_id);

CREATE TABLE IF NOT EXISTS track_tags (
    id               BIGSERIAL PRIMARY KEY,
    track_spotify_id VARCHAR(255) NOT NULL,
    tag_value        VARCHAR(255) NOT NULL,
    type             VARCHAR(50)  NOT NULL,
    source           VARCHAR(50)  NOT NULL,
    weight           INTEGER      NOT NULL,
    cached_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_track_tags_spotify_source ON track_tags (track_spotify_id, source);

CREATE TABLE IF NOT EXISTS artist_tags (
    id                BIGSERIAL PRIMARY KEY,
    artist_spotify_id VARCHAR(255) NOT NULL,
    tag_value         VARCHAR(255) NOT NULL,
    type              VARCHAR(50)  NOT NULL,
    source            VARCHAR(50)  NOT NULL,
    weight            INTEGER      NOT NULL,
    cached_at         TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_artist_tags_spotify_source ON artist_tags (artist_spotify_id, source);

CREATE TABLE IF NOT EXISTS scan_jobs (
    id                           BIGSERIAL PRIMARY KEY,
    user_id                      BIGINT       NOT NULL,
    source_playlist_id           VARCHAR(255) NOT NULL,
    source_playlist_ids_json     TEXT,
    threshold                    INTEGER      NOT NULL,
    status                       VARCHAR(50)  NOT NULL,
    result_json                  TEXT,
    execution_request_json       TEXT,
    execution_summary_json       TEXT,
    created_playlist_ids_json    TEXT,
    source_deletion_actions_json TEXT,
    error_message                VARCHAR(2048),
    current_step                 VARCHAR(255),
    progress_percent             INTEGER      NOT NULL DEFAULT 0,
    current_item                 INTEGER      NOT NULL DEFAULT 0,
    total_items                  INTEGER      NOT NULL DEFAULT 0,
    current_fetch_request        INTEGER      NOT NULL DEFAULT 0,
    total_fetch_requests         INTEGER      NOT NULL DEFAULT 0,
    cancel_requested             BOOLEAN      NOT NULL DEFAULT FALSE,
    applied                      BOOLEAN      NOT NULL DEFAULT FALSE,
    undone                       BOOLEAN      NOT NULL DEFAULT FALSE,
    applied_at                   TIMESTAMPTZ,
    undone_at                    TIMESTAMPTZ,
    provider_mode                VARCHAR(32),
    tagging_stats_json           TEXT,
    created_at                   TIMESTAMPTZ  NOT NULL
);

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
