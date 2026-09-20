#!/usr/bin/env bash
# Generates a self-signed CA plus one leaf certificate per service/gateway,
# simulating TLS termination the way it would happen at an ALB/API Gateway
# and at each Spring Boot service in the real EKS topology.
set -euo pipefail

CERT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PASSWORD="changeit"
DAYS=3650
SERVICES=(sales-service-rest sales-service-grpc api-gateway-sim alb-grpc-sim)

rm -rf "$CERT_DIR/ca" "$CERT_DIR/truststore.p12"
for svc in "${SERVICES[@]}"; do
  rm -rf "$CERT_DIR/$svc"
done

mkdir -p "$CERT_DIR/ca"
openssl req -x509 -newkey rsa:4096 -sha256 -days "$DAYS" -nodes \
  -keyout "$CERT_DIR/ca/ca-key.pem" \
  -out "$CERT_DIR/ca/ca-cert.pem" \
  -subj "/O=Sales Benchmark POC/CN=Sales Benchmark POC Root CA"

keytool -importcert -noprompt -alias ca \
  -file "$CERT_DIR/ca/ca-cert.pem" \
  -keystore "$CERT_DIR/truststore.p12" \
  -storetype PKCS12 -storepass "$PASSWORD"

for svc in "${SERVICES[@]}"; do
  dir="$CERT_DIR/$svc"
  mkdir -p "$dir"

  openssl genrsa -out "$dir/key.pem" 2048
  openssl req -new -key "$dir/key.pem" -out "$dir/csr.pem" \
    -subj "/O=Sales Benchmark POC/CN=$svc" \
    -addext "subjectAltName=DNS:$svc,DNS:localhost,IP:127.0.0.1"

  openssl x509 -req -in "$dir/csr.pem" \
    -CA "$CERT_DIR/ca/ca-cert.pem" -CAkey "$CERT_DIR/ca/ca-key.pem" -CAcreateserial \
    -out "$dir/cert.pem" -days "$DAYS" -sha256 \
    -extfile <(printf "subjectAltName=DNS:%s,DNS:localhost,IP:127.0.0.1" "$svc")

  openssl pkcs12 -export \
    -in "$dir/cert.pem" -inkey "$dir/key.pem" -certfile "$CERT_DIR/ca/ca-cert.pem" \
    -name "$svc" -out "$dir/keystore.p12" -password "pass:$PASSWORD"

  rm -f "$dir/csr.pem"
done

echo "Certificates generated under $CERT_DIR (password: $PASSWORD)"
