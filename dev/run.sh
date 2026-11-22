#!/usr/bin/env bash
# DEV helper - avoids typing long java -D commands.
#   dev/run.sh build             compile src/ and dev/
#   dev/run.sh gateway [args]    mock NIO gateway (port 5000)
#   dev/run.sh auth              mock TLS auth server (port 8443)
#   dev/run.sh client [host]     the UniBook client app
set -e
cd "$(dirname "$0")/.."            # always run from the repository root
CERTS=dev/certs
PW=changeit

if [ ! -f "$CERTS/truststore.jks" ]; then
  dev/gen-certs.sh
fi

case "$1" in
  build)
    rm -rf bin
    javac --release 17 -d bin $(find src -name "*.java")
    javac --release 17 -cp bin -d bin/dev $(find dev -name "*.java")
    echo "Build OK" ;;
  gateway)
    shift; java -cp bin/dev mock.MockGateway "$@" ;;
  auth)
    java -Djavax.net.ssl.keyStore=$CERTS/server.jks -Djavax.net.ssl.keyStorePassword=$PW \
         -cp bin/dev mock.MockAuthServer ;;
  client)
    shift; java -Djavax.net.ssl.trustStore=$CERTS/truststore.jks -Djavax.net.ssl.trustStorePassword=$PW \
         -cp bin com.labsync.client.UniBookClientApp "$@" ;;
  *)
    echo "usage: dev/run.sh build | gateway [port] [delayMs] | auth | client [host]"; exit 1 ;;
esac
