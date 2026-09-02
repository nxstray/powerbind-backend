-- Migration: V3 (Alter email to username)

-- 1. Ubah kolom email menjadi username
ALTER TABLE users RENAME COLUMN email TO username;

-- 2. Sesuaikan tipe data dan constraint jika diperlukan
ALTER TABLE users ALTER COLUMN username TYPE VARCHAR(100);
ALTER TABLE users ALTER COLUMN username SET NOT NULL;