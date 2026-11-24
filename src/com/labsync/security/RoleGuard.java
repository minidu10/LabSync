package com.labsync.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import com.labsync.security.AuthorizationException.Reason;
import com.labsync.security.TokenService.Claims;

/**
 * Role-based access control (RBAC) for gateway commands.
 *
 * <p>Every protected command carries {@code <TOKEN>|<SIG>}. {@link #authorize} first proves the
 * token is genuine and unexpired ({@link TokenService#verify}), then checks that the role
 * <em>inside the token</em> may run the command. The role is never taken from the request frame,
 * so a student cannot simply claim to be an admin.
 *
 * <pre>
 *   Action    STUDENT  ADMIN
 *   BOOK         yes     yes
 *   CANCEL       yes     yes
 *   ADD_LAB       -      yes
 * </pre>
 *
 * Thread-safe; cheap enough (one HMAC + one AES-GCM decrypt) to run on the NIO reactor thread.
 */
public final class RoleGuard {

    public enum Role { STUDENT, ADMIN }

    public enum Action {
        BOOK(Role.STUDENT, Role.ADMIN),
        CANCEL(Role.STUDENT, Role.ADMIN),
        ADD_LAB(Role.ADMIN);

        private final Set<Role> allowed;

        Action(Role first, Role... rest) {
            this.allowed = Collections.unmodifiableSet(EnumSet.of(first, rest));
        }

        public Set<Role> allowedRoles() {
            return allowed;
        }
    }

    private final TokenService tokens;

    public RoleGuard(TokenService tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /** Pure policy check, no token involved. */
    public static boolean permits(Role role, Action action) {
        return role != null && action.allowed.contains(role);
    }

    /**
     * Verifies the token and checks the role.
     *
     * @return the verified claims (use {@link Claims#userId()} as the acting user)
     * @throws AuthorizationException with reason MISSING_TOKEN, INVALID_SIGNATURE, MALFORMED_TOKEN,
     *                                TOKEN_EXPIRED or FORBIDDEN
     */
    public Claims authorize(Action action, String token, String signature) throws AuthorizationException {
        Claims claims = tokens.verify(token, signature);
        if (!permits(claims.role(), action)) {
            throw new AuthorizationException(Reason.FORBIDDEN, String.format(
                    "%s (%s) may not %s - requires %s", claims.userId(), claims.role(), action, action.allowed));
        }
        return claims;
    }

    public TokenService tokens() {
        return tokens;
    }
}
