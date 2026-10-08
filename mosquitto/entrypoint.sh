#!/bin/sh
# Mosquitto entrypoint — generates password_file and acl_file from .env
# before starting the broker. Secrets never live in git; they come from
# docker-compose environment variables.
set -e

CONFIG_DIR=/mosquitto/config
PASSWD_FILE="$CONFIG_DIR/passwd"
ACL_FILE="$CONFIG_DIR/acl"
CONF_D="$CONFIG_DIR/conf.d"

mkdir -p "$CONF_D"

# ── Required: backend service credentials ──────────────────────────────────
BACKEND_USER="${MQTT_BACKEND_USERNAME:-powerbind-backend}"
BACKEND_PASS="${MQTT_BACKEND_PASSWORD:-}"

if [ -z "$BACKEND_PASS" ]; then
    echo "ERROR: MQTT_BACKEND_PASSWORD is not set. Add it to .env (see .env.example)." >&2
    exit 1
fi

# -c alone reopens the existing inode (owner stays whatever it was before),
# so remove the file first and force a fresh root:root one on every restart.
rm -f "$PASSWD_FILE"
mosquitto_passwd -b -c "$PASSWD_FILE" "$BACKEND_USER" "$BACKEND_PASS"

# ── ACL: backend can read/write everything under smart-home/ ───────────────
cat > "$ACL_FILE" <<EOF
user $BACKEND_USER
topic readwrite smart-home/#
EOF

# ── Optional: device users (format: user1:pass1;user2:pass2) ───────────────
# Each device gets publish access to presence/power/logs and read access
# to relay commands — but NOT to other users' topics.
DEVICE_USERS="${MQTT_DEVICE_USERS:-}"

if [ -n "$DEVICE_USERS" ]; then
    echo "$DEVICE_USERS" | tr ';' '\n' | while IFS=: read -r duser dpass; do
        [ -z "$duser" ] && continue
        [ -z "$dpass" ] && continue
        mosquitto_passwd -b "$PASSWD_FILE" "$duser" "$dpass"
        cat >> "$ACL_FILE" <<EOF

user $duser
topic write smart-home/presence/#
topic write smart-home/power/#
topic write smart-home/logs/#
topic read smart-home/relay/#
EOF
    done
fi

# ── Optional: TLS listener (8883) if certs are present ─────────────────────
CERT_DIR="$CONFIG_DIR/certs"
TLS_CONF="$CONF_D/tls-listener.conf"

if [ -f "$CERT_DIR/server.crt" ] && [ -f "$CERT_DIR/server.key" ]; then
    cat > "$TLS_CONF" <<EOF
listener 8883
certfile $CERT_DIR/server.crt
keyfile $CERT_DIR/server.key
EOF
    echo "MQTT TLS listener 8883 enabled."
else
    rm -f "$TLS_CONF"
    echo "MQTT TLS certs not found — listener 8883 disabled. Run run-mosquitto-certs.ps1 to enable."
fi

# The broker loads both files AFTER dropping privileges to the mosquitto
# user and demands mosquitto ownership + no world-readable bits on each.
# rm -f above keeps mosquitto_passwd happy (fresh root:root file at create
# time); the chown below satisfies the broker at load time.
chown mosquitto:mosquitto "$PASSWD_FILE" "$ACL_FILE"
chmod 600 "$PASSWD_FILE" "$ACL_FILE"

# Start the broker directly (skip the image's docker-entrypoint.sh: its
# recursive chown fails on the read-only mounts and would move passwd out of
# root ownership, which mosquitto 2.x warns about. The broker runs as root
# here, so root-owned passwd + root-owned data volume are both correct).
exec /usr/sbin/mosquitto -c /mosquitto/config/mosquitto.conf
