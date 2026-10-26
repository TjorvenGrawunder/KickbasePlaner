package org.tjorven.kickbaseplaner.kickbaserequests;

import jakarta.validation.constraints.NotBlank;

public record KickbaseSquadRequest(@NotBlank String leagueId) {
}
