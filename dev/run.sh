#!/usr/bin/env bash
# DEV helper - avoids typing long java -D commands. Run from Git Bash.
#   dev/run.sh build             compile src/, test/ and dev/
#   dev/run.sh test              run every test suite (prints one RESULT line each)
#   dev/run.sh auth-real         REAL TLS auth server        (port 8443)
#   dev/run.sh gateway-real      REAL NIO gateway + UDP feed (ports 5000 / 6000)
#   dev/run.sh client [host]     the LabSync client app (run twice for two clients)
#   dev/run.sh gateway [args]    mock NIO gateway (port 5000)   - client-only testing
#   dev/run.sh auth              mock TLS auth server (port 8443) - client-only testing
set -e
cd "$(dirname "$0")/.."            # always run from the repository root
CERTS=dev/certs
PW=changeit
# Java classpath separator: ';' on Windows (Git Bash), ':' elsewhere
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; *) SEP=':' ;; esac

if [ ! -f "$CERTS/truststore.jks" ]; then
  dev/gen-certs.sh
fi

TESTS="engine.SeatStoreTest engine.BookingTaskTest engine.ReceiptGeneratorTest engine.BookingWorkerPoolTest
nio.NioGatewayIntegrationTest udp.UdpStatusTest security.TokenSecurityTest security.GatewaySecurityTest
security.TlsAuthServerTest"

case "$1" in
  build)
    rm -rf bin
    javac --release 17 -d bin $(find src -name "*.java")
    javac --release 17 -cp bin -d bin/test $(find test -name "*.java")
    javac --release 17 -cp bin -d bin/dev $(find dev -name "*.java")
    echo "Build OK" ;;
  test)
    for t in $TESTS; do
      printf '%-36s ' "$t"
      java -cp "bin${SEP}bin/test" com.labsync.$t 2>&1 | grep RESULT || echo "DID NOT FINISH"
    done ;;
  auth-real)
    java -cp bin com.labsync.security.TlsAuthServer ;;
  gateway-real)
    java -cp bin com.labsync.nio.NioGatewayServer ;;
  gateway)
    shift; java -cp bin/dev mock.MockGateway "$@" ;;
  auth)
    java -Djavax.net.ssl.keyStore=$CERTS/server.jks -Djavax.net.ssl.keyStorePassword=$PW \
         -cp bin/dev mock.MockAuthServer ;;
  client)
    shift; java -Djavax.net.ssl.trustStore=$CERTS/truststore.jks -Djavax.net.ssl.trustStorePassword=$PW \
         -cp bin com.labsync.client.LabSyncClientApp "$@" ;;
  *)
    echo "usage: dev/run.sh build | test | auth-real | gateway-real | client [host] | gateway [port] [delayMs] | auth"
    exit 1 ;;
esac
