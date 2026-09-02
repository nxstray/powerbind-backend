-- Migration: V4 (Add updated_at to rooms for timeout tracking)

-- Add updated_at column with default value
ALTER TABLE rooms ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT NOW();