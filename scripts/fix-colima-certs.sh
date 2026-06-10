#!/bin/bash
# Inject Mac System Keychain certs into Colima VM (fixes corporate proxy TLS issues)
set -e

echo "Injecting Mac system certs into Colima VM..."

TEMP_CERTS="/tmp/mac-system-certs.pem"
security find-certificate -a -p /Library/Keychains/System.keychain > "$TEMP_CERTS" 2>/dev/null

CERT_COUNT=$(grep -c 'BEGIN CERTIFICATE' "$TEMP_CERTS" 2>/dev/null || echo "0")
if [ "$CERT_COUNT" -eq 0 ]; then
    echo "No extra system certs found. Skipping."
    rm -f "$TEMP_CERTS"
    exit 0
fi

echo "  Found $CERT_COUNT certificates in System Keychain"

colima ssh -- sh -c 'cat > /tmp/mac-system-certs.pem' < "$TEMP_CERTS"
colima ssh -- sh -c '
sudo cp /tmp/mac-system-certs.pem /usr/local/share/ca-certificates/mac-system-certs.crt
sudo update-ca-certificates 2>&1
sudo systemctl restart docker 2>&1
'
rm -f "$TEMP_CERTS"

echo "Certs injected. Docker daemon restarted."
