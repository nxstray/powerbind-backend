#!/bin/sh
# Entrypoint service backup:
#   1. simpan variabel environment ke /run/backup.env (cron tidak mewarisi
#      env container, jadi backup.sh membaca file ini)
#   2. tulis jadwal cron dari BACKUP_CRON (default: tiap hari 02:00)
#   3. jalankan backup sekali saat start (gagal = log saja, cron retry)
#   4. jalankan cron di foreground
set -eu

printenv | grep -E '^(DB_USERNAME|DB_PASSWORD|POSTGRES_DB|INFLUXDB_URL|INFLUXDB_TOKEN|INFLUXDB_ORG|BACKUP_RETENTION_DAYS)=' \
    > /run/backup.env
chmod 0600 /run/backup.env

# Format file /etc/cron.d: menit jam dom mon dow USER perintah (user field
# wajib). Baris terakhir wajib newline, kalau tidak file diabaikan cron.
CRON_SCHEDULE="${BACKUP_CRON:-0 2 * * *}"
printf '%s root /usr/local/bin/backup.sh >> /var/log/backup.log 2>&1\n' "$CRON_SCHEDULE" \
    > /etc/cron.d/powerbind-backup
chmod 0644 /etc/cron.d/powerbind-backup

echo "[$(date -Iseconds)] initial backup run (schedule: $CRON_SCHEDULE)" >> /var/log/backup.log
/usr/local/bin/backup.sh >> /var/log/backup.log 2>&1 \
    || echo "[$(date -Iseconds)] initial backup FAILED - will retry on schedule" >> /var/log/backup.log

exec cron -f