# ── Backup terjadwal Postgres + InfluxDB ─────────────────────────────────
# Basis influxdb:2.7 (Debian) supaya CLI `influx` tersedia tanpa install
# tambahan; cron untuk jadwal harian.
# pg_dump harus versi >= server (postgres:16-alpine) - postgresql-client
# bawaan Debian bookworm hanya v15 dan menolak server 16, jadi pakai repo
# PGDG resmi untuk postgresql-client-16.
# Dipakai oleh service `backup` di docker-compose.yml.
FROM influxdb:2.7

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && mkdir -p /usr/share/postgresql-common/pgdg \
    && curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
        https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt bookworm-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client-16 cron \
    && rm -rf /var/lib/apt/lists/*

COPY backup.sh entrypoint.sh /usr/local/bin/
RUN chmod 0755 /usr/local/bin/backup.sh /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]