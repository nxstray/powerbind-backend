-- Users table — stores registered dashboard users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Refresh tokens — one user can have multiple active sessions
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Rooms — each ESP32 node maps to one room
CREATE TABLE rooms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    mqtt_topic VARCHAR(255) NOT NULL UNIQUE,
    presence_detected BOOLEAN NOT NULL DEFAULT FALSE,
    relay_on BOOLEAN NOT NULL DEFAULT FALSE,
    no_presence_seconds INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Presence logs — historical event log stored in PostgreSQL
-- Time-series aggregates go to InfluxDB, this table is for event history
CREATE TABLE presence_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    detected BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Index for faster history queries per room
CREATE INDEX idx_presence_logs_room_created ON presence_logs(room_id, created_at DESC);

-- Index for refresh token lookup
CREATE INDEX idx_refresh_tokens_token ON refresh_tokens(token);
