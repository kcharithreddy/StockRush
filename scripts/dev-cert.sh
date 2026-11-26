#!/usr/bin/env bash
# Creates a local certificate authority and a certificate for localhost, for HTTPS on this machine only.
#   scripts/dev-cert.sh            # once, before the first `docker compose up`
#   curl --cacert infra/tls/ca.crt https://localhost:8443/sales/1/stock
# To make browsers trust it, import infra/tls/ca.crt as a trusted authority (or use mkcert instead).
# Everything it writes is in infra/tls/, which is git-ignored. Production uses a real certificate
# (e.g. Let's Encrypt) in the same place.
set -euo pipefail

dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/infra/tls"
mkdir -p "$dir"
cd "$dir"

if [ -f server.crt ] && [ "${1:-}" != "--force" ]; then
  echo "infra/tls/server.crt already exists (use --force to replace it)"
  exit 0
fi

umask 077
openssl ecparam -name prime256v1 -genkey -noout -out ca.key
openssl req -x509 -new -key ca.key -sha256 -days 825 -subj "/CN=StockRush local development CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out ca.crt

openssl ecparam -name prime256v1 -genkey -noout -out server.key
openssl req -new -key server.key -subj "/CN=localhost" -out server.csr
cat > server.ext <<'EXT'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
EXT
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 397 -sha256 \
  -extfile server.ext -out server.crt
rm -f server.csr server.ext ca.srl

# Certificates are public; private keys stay owner-only (Nginx's master process reads them as root).
chmod 644 ca.crt server.crt
chmod 600 ca.key server.key
echo "Created infra/tls/ca.crt (trust this) and infra/tls/server.{crt,key} for https://localhost:8443"
