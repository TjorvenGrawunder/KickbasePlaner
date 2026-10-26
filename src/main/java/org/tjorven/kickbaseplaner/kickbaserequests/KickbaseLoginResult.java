package org.tjorven.kickbaseplaner.kickbaserequests;

import java.util.List;

public record KickbaseLoginResult(String token, String managerId, List<KickbaseLeague> leagues) {
}
