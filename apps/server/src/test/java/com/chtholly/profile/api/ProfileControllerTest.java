package com.chtholly.profile.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.profile.api.dto.ProfileResponse;
import com.chtholly.profile.service.ProfileService;
import com.chtholly.storage.StorageService;
import com.chtholly.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfileControllerTest {

    @Test
    void getReturnsCurrentAuthenticatedProfile() {
        ProfileService profileService = mock(ProfileService.class);
        JwtService jwtService = mock(JwtService.class);
        StorageService storageService = mock(StorageService.class);
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "42")
                .issuedAt(Instant.parse("2026-07-05T00:00:00Z"))
                .expiresAt(Instant.parse("2026-07-05T01:00:00Z"))
                .build();
        User user = User.builder()
                .id(42L)
                .nickname("Chtholly")
                .avatar("https://cdn.example/avatar.png")
                .bio("quiet")
                .handle("chtholly")
                .gender("UNKNOWN")
                .birthday(LocalDate.parse("2000-01-01"))
                .school("Fairy Warehouse")
                .phone("13800000000")
                .email("c@example.com")
                .tagsJson("[\"治愈\"]")
                .build();

        when(jwtService.extractUserId(jwt)).thenReturn(42L);
        when(profileService.getById(42L)).thenReturn(Optional.of(user));

        ProfileResponse response = new ProfileController(profileService, jwtService, storageService).get(jwt);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.nickname()).isEqualTo("Chtholly");
        assertThat(response.avatar()).isEqualTo("https://cdn.example/avatar.png");
        assertThat(response.gender()).isEqualTo("UNKNOWN");
        assertThat(response.tagJson()).isEqualTo("[\"治愈\"]");
    }
}
