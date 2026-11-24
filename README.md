# LabSync — Concurrent Seat & Lab-Slot Booking

LabSync lets students book computer-lab seats from a desktop app while many
other people book at the same time. No seat is ever booked twice, and every
open app sees each booking within about a second.

It is written in pure Java (JDK 17+) with no external libraries, build tools
or database.

**Author:** Minidu Dhananjana

---

## Contents

1. [How it fits together](#1-how-it-fits-together)
2. [Requirements](#2-requirements)
3. [Build and test](#3-build-and-test)
4. [Start the system](#4-start-the-system)
5. [Using the app](#5-using-the-app)
6. [Running on several computers](#6-running-on-several-computers)
7. [Demo and diagnostic tools](#7-demo-and-diagnostic-tools)
8. [Protocol reference](#8-protocol-reference)
9. [Troubleshooting](#9-troubleshooting)
10. [Project layout and submission](#10-project-layout-and-submission)

---

## 1. How it fits together

| Port | Protocol | Program | What it does |
|------|----------|---------|--------------|
| 8443 | TCP + TLS | `TlsAuthServer` | Login. Checks the password over an encrypted connection and returns a signed session token (role `STUDENT` or `ADMIN`). |
| 5000 | TCP, non-blocking NIO | `NioGatewayServer` | Seat lists, bookings and cancellations. One thread serves every client; seat locking runs on a worker pool. Every request carries the token, which is verified first. |
| 6000 | UDP broadcast | `UdpStatusBroadcaster` (inside the gateway) | Announces every seat change. Each packet carries a sequence number, so late or duplicate packets are ignored. |
| — | Swing desktop app | `LabSyncClientApp` | Logs in on 8443, books on 5000 and listens on 6000. |

You run **two servers** (auth and gateway) and **one or more clients**.

## 2. Requirements

- **JDK 17 or newer** (`java -version` must show 17+). `javac` and `keytool`
  must be on your `PATH`; both come with the JDK.
- **Git Bash** on Windows, or any terminal on macOS or Linux. The helper
  script `dev/run.sh` is a bash script. Plain PowerShell commands are in
  [section 9](#running-without-git-bash-powershell).

Run every command **from the repository root** (the folder that contains `src/`).

## 3. Build and test

```bash
dev/run.sh build    # compiles src/, test/ and dev/ into bin/
dev/run.sh test     # runs all 9 test suites
```

Every line from `test` must end in `0 FAIL`:

```
engine.SeatStoreTest                 RESULT: 6 PASS, 0 FAIL
...
security.TlsAuthServerTest           RESULT: 21 PASS, 0 FAIL
```

The first time you run any `dev/run.sh` command it also creates a
development TLS certificate in `dev/certs/` (password `changeit`).

## 4. Start the system

Open three terminals in the repository root:

```bash
dev/run.sh auth-real      # terminal 1 - login server, port 8443
dev/run.sh gateway-real   # terminal 2 - booking gateway, port 5000 + UDP feed 6000
dev/run.sh client         # terminal 3 - the app (run it again for a second window)
```

Both servers print a line like this when they start:

```
[Security] shared secret loaded from file ...labsync.secret, fingerprint eef26b1ed9e54fd3
```

**The two fingerprints must match.** The first server creates
`labsync.secret`, and the second one reads the same file. That's why both
servers must be started from the same folder. Never commit or share this
file; git already ignores it.

Stop a server with **Ctrl+C**.

> `dev/run.sh auth` and `dev/run.sh gateway` (without `-real`) start **mock**
> servers. They exist only for testing the client on its own. Use the `-real`
> targets to run the actual system.

## 5. Using the app

### Log in

1. **Server host:** `localhost`, or the server's name or IP address (see section 6).
2. **User / Password:** use one of the built-in accounts:

   | User | Password | Role |
   |------|----------|------|
   | `student1` | `pass123` | STUDENT |
   | `student2` | `pass123` | STUDENT |
   | `admin` | `admin123` | ADMIN |

3. Click **Connect** (or press Enter). The status pill turns green
   (**Connected**) and the seat grid for `LAB_A` appears.

### Read the seat grid

| Colour | Meaning |
|--------|---------|
| Green — **FREE** | Click to book. |
| Red — **BOOKED** | Taken by someone else. |
| Blue — **YOURS** | Booked by you. Click to cancel. |
| Amber — **RESERVED** | Held by the system. |
| Greyed out | Not connected. Clicks are ignored. |

Pick another lab (`LAB_B`) from the **Lab** list. Each lab has seats `S01`–`S20`.

### Book a seat

Click a green seat and confirm. A receipt opens with:

- **Receipt ID**
- **SHA-256** — a tamper-evident hash of the booking
- **Receipt URI** — `labsync://localhost:5000/receipt/<id>`, split into
  scheme, host, port and receipt ID

**Copy URI** and **Copy hash** put those values on the clipboard.

If someone else took the seat a moment earlier, you get
*Booking failed: SEAT_ALREADY_TAKEN* and the grid refreshes.

### Cancel a booking

Click your own blue seat and confirm. A STUDENT can cancel only their own
bookings. An ADMIN can cancel any booked seat.

### Live updates

When anyone books or cancels, every open app updates that seat within about
a second. The **Network activity** log shows the UDP packet that caused it:

```
UDP  SEQ:3 LAB_A S07 -> BOOKED by student2
```

The app also asks the gateway for the full seat list every 3 seconds, so
the grid corrects itself even if a UDP packet is lost.

### If the server goes down

The pill turns amber (**Reconnecting…**) and the app retries after 1, 2, 4
and then every 8 seconds. When the gateway is back, it reconnects, reloads
the grid and returns to **Connected**. You don't need to log in again.

Your login is valid for **30 minutes**. After that, actions fail with
`TOKEN_EXPIRED`; click **Connect** to log in again.

## 6. Running on several computers

1. On the **server computer**, run `dev/run.sh build`, then start
   `auth-real` and `gateway-real`.
2. Copy `dev/certs/truststore.jks` from the server computer to the same path
   in each **client computer's** copy of the repository. Clients must trust
   the certificate that this server generated.
3. On each client, run `dev/run.sh build`, then `dev/run.sh client`, and
   type the server's IP address or hostname in **Server host**.
4. Allow Java through the firewall on both sides: TCP **8443** and **5000**
   on the server, UDP **6000** on the clients. Windows asks the first time;
   choose *Private networks*.

Live updates use UDP broadcast, so they reach clients **on the same
network/subnet** only. Clients elsewhere still stay correct through the
3-second refresh.

## 7. Demo and diagnostic tools

These run against the real gateway (start it first). Replace `;` with `:` in
`-cp` on macOS or Linux.

| Goal | Command | What to look for |
|------|---------|------------------|
| Many clients, one thread | `java -cp bin com.labsync.nio.NioDemoClient multi` | Gateway log: four sessions, `JVM threads=2` |
| TCP split frame | `java -cp bin com.labsync.nio.NioDemoClient split` | `[PARTIAL] ... kept by compact()`, then the full frame |
| Type raw frames | `java -cp bin com.labsync.nio.NioDemoClient shell` | Type `LIST\|LAB_A` or `STATS` and press Enter |
| Selector rebuild | Start the gateway with `java -Dlabsync.debug=true -cp bin com.labsync.nio.NioGatewayServer`, then `java -cp bin com.labsync.nio.NioDemoClient spin` | `epoll 0-key spin detected ... rebuilding selector` |
| UDP out-of-order rejection | `java -cp bin com.labsync.udp.UdpDemoClient demo-ooo` | `[ACCEPT] SEQ:7`, then `WARN [DROP] incomingSeq=3 <= lastSeenSeq=7` |
| UDP menu for a live demo | `java -cp bin com.labsync.udp.UdpDemoClient interactive` | Menu to send in-order or out-of-order packets |
| Double-booking race | `java -cp "bin;bin/test" com.labsync.engine.SeatStoreTest` | `exactly one success` |
| Tampered token / RBAC | `java -cp "bin;bin/test" com.labsync.security.GatewaySecurityTest` | `INVALID_SIGNATURE`, `FORBIDDEN` |

In the UDP logs, `[DUP]` lines are harmless. Each update is sent to several
broadcast addresses, so copies arrive more than once. `WARN [DROP]` marks a
real out-of-order packet.

## 8. Protocol reference

Every TCP frame is one UTF-8 line ending in `\n`. Fields are separated by `|`.

**Login — port 8443 (TLS)**

```
LOGIN|<userId>|<password>
  -> AUTH_OK|<ROLE>|<TOKEN>|<SIG>
  -> AUTH_FAIL|INVALID_CREDENTIALS
  -> AUTH_FAIL|TOO_MANY_ATTEMPTS        (5 wrong passwords lock the user for 30 s)
```

**Gateway — port 5000**

```
LIST|<labId>
  -> SLOTS|<labId>|S01:FREE,S02:BOOKED,...

BOOK|<labId>|<seatId>|<TOKEN>|<SIG>
  -> BOOK_OK|<receiptId>|<SHA256_HASH>|labsync://localhost:5000/receipt/<receiptId>
  -> BOOK_FAIL|SEAT_ALREADY_TAKEN | UNKNOWN_SEAT | INVALID_SIGNATURE | TOKEN_EXPIRED | ...

CANCEL|<labId>|<seatId>|<TOKEN>|<SIG>
  -> CANCEL_OK|<labId>|<seatId>
  -> CANCEL_FAIL|NOT_OWNER | NOT_BOOKED | UNKNOWN_SEAT | INVALID_SIGNATURE | ...

ADD_LAB|<labId>|<TOKEN>|<SIG>          (ADMIN only, creates a 20-seat lab)
  -> ADD_LAB_OK|<labId>|20
  -> ADD_LAB_FAIL|FORBIDDEN | LAB_EXISTS | INVALID_LAB_ID | ...

STATS
  -> STATS|sessions=..|reactorThread=..|jvmThreads=..|frames=..|selectorRebuilds=..
```

**Live feed — UDP port 6000**

```
SEQ:<n>|<labId>|<seatId>|<STATUS>|<userId>
```

Clients ignore any packet whose `n` is not larger than the last one they
accepted.

## 9. Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| `Address already in use` when starting a server | A server is already running on that port. Close the other terminal or stop the old `java` process. |
| App: *Server not reachable (is it running?)* | Start both `-real` servers, and check the host name and the firewall. |
| App: *TLS setup failed: cannot load the truststore* | `dev/certs/truststore.jks` is missing. Run any `dev/run.sh` command to create it, or copy it from the server (section 6). |
| App: *Secure connection failed* | The client's truststore doesn't match the server's certificate. Copy the server's `truststore.jks` again. |
| Every booking fails with `INVALID_SIGNATURE` | The two servers use different secrets: the fingerprints don't match. Stop both and start them from the same folder. |
| Bookings fail with `TOKEN_EXPIRED` | Logins last 30 minutes. Click **Connect** again. (Server option: `-Dlabsync.tokenTtlSeconds=<n>`.) |
| *Login rejected: TOO_MANY_ATTEMPTS* | 5 wrong passwords in a row lock that user for 30 seconds. Wait, then try again. |
| *Unknown host* | The name can't be resolved. Use the IP address instead. |
| No live updates; the seat only changes after a few seconds | UDP 6000 is blocked or the client is on another subnet. Allow Java through the firewall. The 3-second refresh still keeps the grid correct. |

### Running without Git Bash (PowerShell)

```powershell
javac --release 17 -d bin (Get-ChildItem -Recurse src -Filter *.java).FullName
javac --release 17 -cp bin -d bin/test (Get-ChildItem -Recurse test -Filter *.java).FullName
java -cp bin com.labsync.security.TlsAuthServer          # terminal 1
java -cp bin com.labsync.nio.NioGatewayServer            # terminal 2
java "-Djavax.net.ssl.trustStore=dev/certs/truststore.jks" "-Djavax.net.ssl.trustStorePassword=changeit" -cp bin com.labsync.client.LabSyncClientApp   # terminal 3
```

The certificate must already exist. Create it once with Git Bash
(`bash dev/gen-certs.sh`) or with the two `keytool` commands in that script.

## 10. Project layout and submission

```
src/com/labsync/
  nio/       NioGatewayServer, ClientSession, NioDemoClient      - TCP 5000, single-thread reactor
  engine/    SeatStore, BookingTask, BookingWorkerPool,
             BookingReceipt, ReceiptGenerator                     - seat locks and worker pool
  udp/       UdpStatusBroadcaster, UdpStatusListener,
             SeatStatusUpdate, UdpDemoClient                      - UDP 6000 live feed
  security/  TlsAuthServer, TokenService, RoleGuard,
             SecurityUtils, SharedSecret                          - TLS 8443, tokens, roles
  client/    LabSyncClientApp, SeatGridPanel, ReceiptDialog,
             NetworkResolver, AuthClient, GatewayConnection       - Swing app
test/        one test program per area (dev/run.sh test)
dev/         certificates script, mock servers, console checks - not submitted
docs/        LabSync_User_Guide.pdf - this guide as a PDF
```

**Submission archive.** Only `src/` and the report go into the zip:

```powershell
Compress-Archive -Path src, LabSync_Report.pdf -DestinationPath LabSync_GroupXX.zip
```
