package com.chtholly.counter.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.counter.api.dto.ActionRequest;
import com.chtholly.counter.service.CounterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActionControllerTest {

    @Mock
    private CounterService counterService;
    @Mock
    private JwtService jwtService;
    @Mock
    private Jwt jwt;

    private ActionController controller;
    private ActionRequest request;

    @BeforeEach
    void setUp() {
        controller = new ActionController(counterService, jwtService);
        request = new ActionRequest();
        request.setEntityType("post");
        request.setEntityId("7");
        when(jwtService.extractUserId(jwt)).thenReturn(42L);
    }

    @Test
    void likeReturnsCommittedTargetWithoutReadingBitmap() {
        when(counterService.like("post", "7", 42L)).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.like(request, jwt);

        assertThat(response.getBody()).containsEntry("changed", true).containsEntry("liked", true);
        verify(counterService, never()).isLiked("post", "7", 42L);
    }

    @Test
    void unlikeReturnsFalseTargetWithoutReadingBitmap() {
        when(counterService.unlike("post", "7", 42L)).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.unlike(request, jwt);

        assertThat(response.getBody()).containsEntry("changed", true).containsEntry("liked", false);
        verify(counterService, never()).isLiked("post", "7", 42L);
    }

    @Test
    void favoriteReturnsCommittedTargetWithoutReadingBitmap() {
        when(counterService.fav("post", "7", 42L)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.fav(request, jwt);

        assertThat(response.getBody()).containsEntry("changed", false).containsEntry("faved", true);
        verify(counterService, never()).isFaved("post", "7", 42L);
    }

    @Test
    void unfavoriteReturnsFalseTargetWithoutReadingBitmap() {
        when(counterService.unfav("post", "7", 42L)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.unfav(request, jwt);

        assertThat(response.getBody()).containsEntry("changed", false).containsEntry("faved", false);
        verify(counterService, never()).isFaved("post", "7", 42L);
    }
}
