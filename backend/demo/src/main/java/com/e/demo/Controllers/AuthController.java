package com.e.demo.Controllers;

import com.e.demo.dto.LoginRequest;
import com.e.demo.dto.LoginResponse;
import com.e.demo.entity.User;
import com.e.demo.repository.UserRepository;
import com.e.demo.server.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Log4j2
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
            .orElseThrow(() -> new RuntimeException("Пользователь не найден"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            log.info("Хеш пароль", req.password());
            return ResponseEntity.status(401).build();
        }

        String token = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole());
        return ResponseEntity.ok(new LoginResponse(token, user.getEmail(), user.getRole()));
    }
}
