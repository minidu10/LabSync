# dev/ — test tools (NOT part of the submission zip)

Mock servers and console checks used to build the client before the real
servers were ready. Only `src/` goes into `LabSync_GroupXX.zip`.

Run everything from the repository root.

## Quick start — the REAL system (use this to check the project)

```bash
dev/run.sh build          # compile src/, test/, dev/
dev/run.sh test           # every suite must say "0 FAIL"
dev/run.sh auth-real      # terminal 1: TlsAuthServer, port 8443
dev/run.sh gateway-real   # terminal 2: NioGatewayServer, port 5000 + UDP live feed on 6000
dev/run.sh client         # terminal 3 and 4: two clients, side by side
```

Users: `student1/pass123`, `student2/pass123` (STUDENT), `admin/admin123` (ADMIN).
Click a green seat to book it; click your own (blue) seat to cancel it. An ADMIN can
cancel any booked seat. A booking or cancel in one client appears in the other via UDP.

Both servers share a token secret through the git-ignored file `labsync.secret`,
which the first server creates. Start both from the repository root.

## Quick start — mock servers (client-only testing)

```bash
dev/run.sh build      # compile
dev/run.sh gateway    # terminal 1 (mock)
dev/run.sh auth       # terminal 2 (mock)
dev/run.sh client     # terminal 3 (run again for a second client)
```

## Build (manual commands)

```bash
javac --release 17 -d bin $(find src -name "*.java")
javac --release 17 -cp bin -d bin/dev $(find dev -name "*.java")
dev/gen-certs.sh        # once: creates dev/certs/*.jks (password: changeit)
```

## Start mock servers (two terminals)

```bash
java -cp bin/dev mock.MockGateway                       # port 5000, add "5000 6000" for a 6 s reply delay
java -Djavax.net.ssl.keyStore=dev/certs/server.jks -Djavax.net.ssl.keyStorePassword=changeit \
     -cp bin/dev mock.MockAuthServer                     # port 8443
```

Mock users: `student1/pass123`, `student2/pass123` (STUDENT), `admin/admin123` (ADMIN).

## Run the client

```bash
java -Djavax.net.ssl.trustStore=dev/certs/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit \
     -cp bin com.labsync.client.LabSyncClientApp [host]
```

## Console checks (one per stage)

```bash
T="-Djavax.net.ssl.trustStore=dev/certs/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
java -cp bin:bin/dev check.ResolverCheck [host...]           # DNS + receipt URI parsing
java -cp bin:bin/dev check.GatewayCheck [host] [port] [lab] [seat]   # LIST / BOOK
java $T -cp bin:bin/dev check.AuthCheck [host] [user] [pass] # TLS login
java -cp bin:bin/dev check.GridPreview                       # seat grid, fake UDP update
```
