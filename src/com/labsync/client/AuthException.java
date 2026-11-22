package com.labsync.client;

/** Login was rejected by the server (AUTH_FAIL). Not a network error. */
public class AuthException extends Exception {
    private static final long serialVersionUID = 1L;

    public AuthException(String reason) {
        super(reason);
    }
}
