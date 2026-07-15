package com.chtholly.profile.service.impl;

import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.profile.api.dto.ProfilePatchRequest;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceImplTest {

    @Mock private UserMapper userMapper;
    @Mock private OutboxMapper outboxMapper;
    @Mock private SnowflakeIdGenerator idGenerator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void updateProfileWritesAuthorEventWhenPublicFieldsChange() throws Exception {
        User before = user("旧简介", "MALE");
        User after = user("新的简介", "MALE");
        when(userMapper.findById(7L)).thenReturn(before, after);
        when(idGenerator.nextId()).thenReturn(9001L);
        ProfileServiceImpl service = service();

        service.updateProfile(7L, new ProfilePatchRequest(
                null, "新的简介", null, null, null, null, null));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).insert(eq(9001L), eq("user"), eq(7L),
                eq("AUTHOR_PROFILE_CHANGED"), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.get("entity").asText()).isEqualTo("user");
        assertThat(json.get("op").asText()).isEqualTo("author_profile_changed");
        assertThat(json.get("id").asLong()).isEqualTo(7L);
    }

    @Test
    void updateProfileDoesNotWriteAuthorEventForPrivateOnlyChange() {
        User before = user("简介", "MALE");
        User after = user("简介", "FEMALE");
        when(userMapper.findById(7L)).thenReturn(before, after);
        ProfileServiceImpl service = service();

        service.updateProfile(7L, new ProfilePatchRequest(
                null, null, "FEMALE", null, null, null, null));

        verify(outboxMapper, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void updateProfileDoesNotWriteAuthorEventWhenPublicValueIsUnchanged() {
        User before = user("简介", "MALE");
        User after = user("简介", "MALE");
        when(userMapper.findById(7L)).thenReturn(before, after);
        ProfileServiceImpl service = service();

        service.updateProfile(7L, new ProfilePatchRequest(
                null, "简介", null, null, null, null, null));

        verify(outboxMapper, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void updateAvatarWritesAuthorEventWhenAvatarChanges() {
        User before = user("简介", "MALE");
        before.setAvatar("/old.webp");
        User after = user("简介", "MALE");
        after.setAvatar("/new.webp");
        when(userMapper.findById(7L)).thenReturn(before, after);
        when(idGenerator.nextId()).thenReturn(9002L);
        ProfileServiceImpl service = service();

        service.updateAvatar(7L, "/new.webp");

        verify(outboxMapper).insert(eq(9002L), eq("user"), eq(7L), eq("AUTHOR_PROFILE_CHANGED"), any());
    }

    private ProfileServiceImpl service() {
        return new ProfileServiceImpl(userMapper, outboxMapper, idGenerator, objectMapper);
    }

    private User user(String bio, String gender) {
        return User.builder()
                .id(7L)
                .handle("rekyrice")
                .nickname("Rekyrice")
                .avatar("/avatar.webp")
                .bio(bio)
                .gender(gender)
                .birthday(LocalDate.of(2000, 1, 1))
                .tagsJson("[\"动画\"]")
                .build();
    }
}
