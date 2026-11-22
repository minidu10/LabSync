package com.labsync.client;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Client side of the TLS login (port 8443, server is Member 4's TlsAuthServer).
 *
 * Design decisions:
 *  - Short-lived connection: connect, send LOGIN, read one reply, close.
 *    After that the client only needs the token, which travels over the
 *    fast NIO gateway (the "control plane / data plane split" in the spec).
 *  - Trust: SSLSocketFactory.getDefault() trusts the certificates in the
 *    truststore given on the command line:
 *        -Djavax.net.ssl.trustStore=<file> -Djavax.net.ssl.trustStorePassword=<pw>
 *    If the server's certificate is not trusted the handshake fails
 *    (SSLHandshakeException) and the password is never sent.
 *
 * This method BLOCKS - call it from a background thread.
 */
public final class AuthClient {

    public static final int DEFAULT_PORT = 8443;

    private AuthClient() { }

    /** Result of a login: the session plus TLS details for the log. */
    public record LoginResult(Session session, String tlsProtocol, String cipherSuite) { }

    public static LoginResult login(InetAddress address, int port, String userId, String password)
            throws IOException, AuthException {
        String frame = Protocol.login(userId, password);   // validates fields first

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new InetSocketAddress(address, port), GatewayConnection.CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(GatewayConnection.READ_TIMEOUT_MS);
            socket.startHandshake();   // explicit, so certificate problems surface here

            Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            out.write(frame + "\n");
            out.flush();

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String reply = in.readLine();
            if (reply == null) {
                throw new EOFException("Auth server closed the connection");
            }
            Session session = Protocol.parseAuth(userId, reply);
            return new LoginResult(session,
                    socket.getSession().getProtocol(), socket.getSession().getCipherSuite());
        }
    }
}
