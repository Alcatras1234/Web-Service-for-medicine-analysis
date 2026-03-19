package com.e.demo.Controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.e.demo.dto.AuthRequest;
import com.e.demo.server.AuthServer;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;


@RestController
@RequestMapping("/app")
@Tag(name = "RegistrationController", description = "Controller for user registration")
public class RegController {

    private final AuthServer authServer;
    public RegController(AuthServer authServer) {
        this.authServer = authServer;
    }
    
    @PostMapping("/auth")
    public ResponseEntity<String> authUser(@RequestBody @Valid AuthRequest regRequest)  {
        
        return ResponseEntity.ok("Пользователь авторизован");
    }
}
