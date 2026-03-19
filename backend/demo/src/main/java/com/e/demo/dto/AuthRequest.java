package com.e.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Авторизация пользователя")
public class AuthRequest {
    @Schema(description = "email", example = "kotak@gmail.com")
    @NotBlank(message = "Email can't be empty")
    @Email(message = "Email must be in format user@example.com")
    private String email;

    @Schema(description = "password", example = "am@gus778sp")
    @Size(min = 8, max = 255, message = "Password must have from 8 to 255 symbols")
    @NotBlank(message = "Password can't be empty")
    private String password;
}
