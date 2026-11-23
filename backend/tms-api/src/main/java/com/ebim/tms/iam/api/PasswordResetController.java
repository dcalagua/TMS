package com.ebim.tms.iam.api;

import com.ebim.tms.iam.application.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * «¿Olvidaste tu contraseña?» (ADR-031). Anonymous by necessity - the person cannot sign in, that is
 * the point - so it is one of the two business-free paths {@code SecurityConfig} leaves open, and it
 * answers {@code 202} for every well-formed address whether or not an account exists.
 */
@RestController
@RequestMapping("${tms.api.base-path}/auth")
@Tag(name = "Authentication", description = "Password reset by email")
public class PasswordResetController {

    private final PasswordResetService service;

    public PasswordResetController(PasswordResetService service) {
        this.service = service;
    }

    /** @param email the address the person signs in with */
    public record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Email a password reset link, if the address has an account (the answer never says)")
    public void request(@Valid @RequestBody PasswordResetRequest request, HttpServletRequest http) {
        service.request(request.email(), http.getRemoteAddr());
    }
}
