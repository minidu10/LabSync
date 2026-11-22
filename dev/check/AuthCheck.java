package check;

import com.labsync.client.AuthClient;
import com.labsync.client.AuthException;
import com.labsync.client.NetworkResolver;

/**
 * Stage 3 console check: TLS login (needs -Djavax.net.ssl.trustStore=...).
 * Usage: java check.AuthCheck [host] [user] [password]
 */
public class AuthCheck {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        String user = args.length > 1 ? args[1] : "student1";
        String pass = args.length > 2 ? args[2] : "pass123";
        try {
            AuthClient.LoginResult r = AuthClient.login(NetworkResolver.resolve(host), AuthClient.DEFAULT_PORT, user, pass);
            System.out.println("Logged in: " + r.session().userId() + " role=" + r.session().role()
                    + " via " + r.tlsProtocol() + " / " + r.cipherSuite());
        } catch (AuthException e) {
            System.out.println("Login rejected: " + e.getMessage());
        } catch (java.io.IOException e) {
            System.out.println("Network/TLS error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
