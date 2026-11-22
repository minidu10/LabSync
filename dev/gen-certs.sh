#!/usr/bin/env bash
# DEV-ONLY: creates a self-signed server certificate for the mock TLS server
# and a client truststore that trusts it. (*.jks files are git-ignored.)
#   dev/certs/server.jks      -> used by the server  (javax.net.ssl.keyStore)
#   dev/certs/truststore.jks  -> used by the client  (javax.net.ssl.trustStore)
set -e
cd "$(dirname "$0")"
mkdir -p certs
rm -f certs/server.jks certs/truststore.jks certs/server.cer

keytool -genkeypair -alias unibook -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=localhost, OU=UniBook, O=Group, C=LK" -ext "SAN=dns:localhost,ip:127.0.0.1" \
  -keystore certs/server.jks -storepass changeit -keypass changeit

keytool -exportcert -alias unibook -keystore certs/server.jks -storepass changeit -file certs/server.cer
keytool -importcert -noprompt -alias unibook -file certs/server.cer \
  -keystore certs/truststore.jks -storepass changeit
rm certs/server.cer
echo "Created dev/certs/server.jks and dev/certs/truststore.jks (password: changeit)"
