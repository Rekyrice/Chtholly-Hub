package com.chtholly.storage.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.storage.api.dto.StoragePresignRequest;
import com.chtholly.storage.api.dto.StoragePresignResponse;
import com.chtholly.storage.api.dto.StorageUploadResponse;
import com.chtholly.storage.UploadContent;
import com.chtholly.storage.service.StorageUploadApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class StorageControllerTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private StorageUploadApplicationService uploadApplicationService;
    @Mock
    private Jwt jwt;

    private StorageController controller;

    @BeforeEach
    void setUp() {
        controller = new StorageController(jwtService, uploadApplicationService);
        when(jwtService.extractUserId(jwt)).thenReturn(7L);
    }

    @Test
    void presignMapsHttpRequestAndApplicationResult() {
        StoragePresignRequest request = new StoragePresignRequest(
                "post_content", "42", "text/markdown", "md");
        when(uploadApplicationService.presign(7L, "42", "post_content", "text/markdown", "md"))
                .thenReturn(new StorageUploadApplicationService.PresignResult(
                        "posts/42/content.md",
                        "https://put",
                        Map.of("x-test", "1"),
                        300,
                        "PUT",
                        "https://cdn/posts/42/content.md"));

        StoragePresignResponse response = controller.presign(request, jwt);

        assertThat(response).isEqualTo(new StoragePresignResponse(
                "posts/42/content.md",
                "https://put",
                Map.of("x-test", "1"),
                300,
                "PUT",
                "https://cdn/posts/42/content.md"));
    }

    @Test
    void uploadMapsAuthenticatedUserAndEtag() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "content.md", "text/markdown", "hello".getBytes());
        when(uploadApplicationService.upload(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("posts/42/content.md"),
                any(UploadContent.class)))
                .thenReturn("etag");

        StorageUploadResponse response = controller.upload(jwt, "posts/42/content.md", file);

        assertThat(response).isEqualTo(new StorageUploadResponse("etag"));
        ArgumentCaptor<UploadContent> contentCaptor = ArgumentCaptor.forClass(UploadContent.class);
        verify(uploadApplicationService).upload(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("posts/42/content.md"),
                contentCaptor.capture());
        UploadContent content = contentCaptor.getValue();
        assertThat(content.contentType()).isEqualTo("text/markdown");
        assertThat(content.size()).isEqualTo(5L);
    }
}
