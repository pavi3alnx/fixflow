package com.fixflow.backend.security;

import com.fixflow.backend.auth.AuthService;
import com.fixflow.backend.identity.UserRepository;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountDetailsService implements UserDetailsService {
    private final UserRepository users;
    public AccountDetailsService(UserRepository users) { this.users = users; }
    @Override @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(AuthService.normalizeEmail(email)).map(AccountPrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
