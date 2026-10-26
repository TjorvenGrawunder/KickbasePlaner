package org.tjorven.kickbaseplaner.kickbaserequests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record KickbaseLoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
