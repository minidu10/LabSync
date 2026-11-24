package com.labsync.security;

/**
 * Thrown when a request's token, signature, or role is not acceptable. {@link #reason()} is the
 * code the gateway puts on the wire, e.g. {@code BOOK_FAIL|INVALID_SIGNATURE}.
 */
public final class AuthorizationException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** Token or signature field is empty. */
        MISSING_TOKEN,
        /** HMAC-SHA256 signature does not match the token (token or signature was altered). */
        INVALID_SIGNATURE,
        /** Signature matched but the token could not be decrypted or parsed. */
        MALFORMED_TOKEN,
        /** Token is genuine but past its expiry time. */
        TOKEN_EXPIRED,
        /** Token is genuine but the user's role may not run this command (RBAC). */
        FORBIDDEN
    }

    private final Reason reason;

    public AuthorizationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
