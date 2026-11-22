package com.labsync.client;

/**
 * What the client keeps after a successful TLS login:
 * the role, and the signed token that is attached to every BOOK request.
 * The client never checks the signature itself - only the server can.
 */
public record Session(String userId, String role, String token, String signature) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
