#!/bin/sh
# Satu kali run backup: pg_dump (Postgres) + influx backup (InfluxDB), lalu
# rotasi file lama. Dipanggil cron (lihat entrypoint.sh) atau manual:
#   docker compose exec backup /usr/local/bin/backup.sh
# Log: docker compose exec backup tail -20 /var/log/backup.log
set -eu

# Environment container dipindah ke file oleh entrypoint.sh karena cron tidak
# mewarisi env container.
. /run/backup.env

STAMP=$(date +%Y%m%d-%H%M%S)
RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-7}
PG_DIR=/backups/postgres
INFLUX_DIR=/backups/influx

mkdir -p "$PG_DIR" "$INFLUX_DIR"

# ── Postgres: format custom (-Fc, sudah terkompresi) → restore via pg_restore
echo "[$(date -Iseconds)] pg_dump start"
export PGPASSWORD="$DB_PASSWORD"
# tulis ke .tmp dulu: dump setengah jalan tidak boleh terbaca sebagai valid
pg_dump -h postgres -p 5432 -U "$DB_USERNAME" -d "${POSTGRES_DB:-powerbind}" -Fc \
    -f "$PG_DIR/.powerbind-$STAMP.dump.tmp"
mv "$PG_DIR/.powerbind-$STAMP.dump.tmp" "$PG_DIR/powerbind-$STAMP.dump"
unset PGPASSWORD

# ── InfluxDB: backup terkompresi gzip (default CLI) → influx restore
# Catatan: tidak ada flag --compress; kompresi diatur --compression
# none|gzip dan default-nya sudah gzip.
echo "[$(date -Iseconds)] influx backup start"
influx backup --host "$INFLUXDB_URL" --token "$INFLUXDB_TOKEN" --org "$INFLUXDB_ORG" \
    "$INFLUX_DIR/.influx-$STAMP.tmp"
mv "$INFLUX_DIR/.influx-$STAMP.tmp" "$INFLUX_DIR/influx-$STAMP"

# ── Rotasi: buang apa pun yang lebih tua dari RETENTION_DAYS hari
echo "[$(date -Iseconds)] rotate (keep ${RETENTION_DAYS}d)"
find "$PG_DIR" -mindepth 1 -mtime +"$RETENTION_DAYS" -delete
find "$INFLUX_DIR" -mindepth 1 -mtime +"$RETENTION_DAYS" -delete

echo "[$(date -Iseconds)] backup OK"