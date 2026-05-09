package com.e.demo.config;

import com.e.demo.server.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        String path = request.getRequestURI();

        if (header == null) {
            log.debug("JwtFilter: no Authorization header for {}", path);
        } else if (!header.startsWith("Bearer ")) {
            log.warn("JwtFilter: bad Authorization header format for {}: {}",
                    path, header.substring(0, Math.min(20, header.length())));
        } else {
            String token = header.substring(7);
            try {
                if (jwtService.isValid(token)) {
                    Integer userId = jwtService.extractUserId(token);
                    String role    = jwtService.extractRole(token);
                    log.debug("JwtFilter: OK userId={} role={} path={}", userId, role, path);

                    UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        );
                    auth.setDetails(userId);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else {
                    log.warn("JwtFilter: token NOT VALID for {} (signature/expired)", path);
                }
            } catch (Exception e) {
                log.error("JwtFilter: exception while validating token for {}: {}",
                        path, e.getMessage());
            }
        }

        chain.doFilter(request, response);
    }
}
