#!/bin/sh
set -eu

DATA_DIR=/data
CONFIG_DIR="$DATA_DIR/.kickbase-planer"
KEY_FILE="$DATA_DIR/credentials-encryption-key"
ADMIN_PASSWORD_FILE="$DATA_DIR/admin-password"

mkdir -p "$CONFIG_DIR"

if [ -z "${KICKBASE_CREDENTIALS_ENCRYPTION_KEY:-}" ]; then
    if [ -f "$KEY_FILE" ]; then
        KICKBASE_CREDENTIALS_ENCRYPTION_KEY=$(cat "$KEY_FILE")
    else
        KICKBASE_CREDENTIALS_ENCRYPTION_KEY=$(head -c 32 /dev/urandom | base64 | tr -d '\n')
        printf '%s' "$KICKBASE_CREDENTIALS_ENCRYPTION_KEY" > "$KEY_FILE"
        chmod 600 "$KEY_FILE"
    fi
fi
export KICKBASE_CREDENTIALS_ENCRYPTION_KEY

if [ -z "${KICKBASE_ADMIN_PASSWORD:-}" ]; then
    if [ -f "$ADMIN_PASSWORD_FILE" ]; then
        KICKBASE_ADMIN_PASSWORD=$(cat "$ADMIN_PASSWORD_FILE")
    else
        KICKBASE_ADMIN_PASSWORD=$(head -c 32 /dev/urandom | base64 | tr -d '\n')
        printf '%s' "$KICKBASE_ADMIN_PASSWORD" > "$ADMIN_PASSWORD_FILE"
        chmod 600 "$ADMIN_PASSWORD_FILE"
        printf '\nAdmin-Login: Benutzername admin, Passwort: %s\n' "$KICKBASE_ADMIN_PASSWORD"
        printf 'Das Passwort wird im persistenten Volume gespeichert. Sichere es jetzt.\n'
    fi
fi
export KICKBASE_ADMIN_PASSWORD

exec java -jar /app/app.jar
