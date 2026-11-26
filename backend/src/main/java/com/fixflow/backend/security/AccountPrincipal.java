package com.fixflow.backend.security;

import com.fixflow.backend.identity.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record AccountPrincipal(Long id, String email, String passwordHash, String role, boolean active)
        implements UserDetails {
    public static AccountPrincipal from(User user) {
        return new AccountPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(),
                user.getRole().getName(), user.isEnabled() && user.isEmailVerified());
    }
    @Override public String getUsername() { return email; }
    @Override public String getPassword() { return passwordHash; }
    @Override public boolean isEnabled() { return active; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public String toString() { return "AccountPrincipal[id=" + id + "]"; }
}
