package com.intutr.agents.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** POC auth: role comes from the X-Role header. Replace with Firebase JWT claims in the real project. */
public final class Roles {
    private Roles() {}
    public static void require(String role, String... allowed) {
        for (String a : allowed) if (a.equalsIgnoreCase(role)) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role " + role + " cannot do this");
    }
}
