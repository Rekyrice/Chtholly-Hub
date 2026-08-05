package com.chtholly.post.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostServiceImplFacadeTest {

    private final PostDraftCommandService draftCommands = mock(PostDraftCommandService.class);
    private final PostMetadataCommandService metadataCommands = mock(PostMetadataCommandService.class);
    private final PostPublicationCommandService publicationCommands = mock(PostPublicationCommandService.class);
    private final PostDeletionCommandService deletionCommands = mock(PostDeletionCommandService.class);
    private final PostDetailQueryService detailQueries = mock(PostDetailQueryService.class);
    private final PostBackgroundQueryService backgroundQueries = mock(PostBackgroundQueryService.class);
    private final PostServiceImpl service = new PostServiceImpl(
            draftCommands,
            metadataCommands,
            publicationCommands,
            deletionCommands,
            detailQueries,
            backgroundQueries);

    @Test
    void writeMethodsDelegateToDedicatedCommandServices() {
        PostDraftCommandService.PreparedContentBinding prepared = mock(
                PostDraftCommandService.PreparedContentBinding.class);
        when(draftCommands.prepareContentBinding(7L, 42L, "key", "etag", 10L, "sha"))
                .thenReturn(prepared);

        service.createDraft(7L);
        service.confirmContent(7L, 42L, "key", "etag", 10L, "sha");
        service.updateMetadata(7L, 42L, "title", 3L, List.of("tag"), List.of("image"),
                "public", false, "description");
        service.publish(7L, 42L);
        service.updateTop(7L, 42L, true);
        service.updateVisibility(7L, 42L, "private");
        service.delete(7L, 42L);
        service.adminUpdateVisibility(42L, "private");
        service.adminDelete(42L);

        verify(draftCommands).createDraft(7L);
        var orderedDraftCalls = inOrder(draftCommands);
        orderedDraftCalls.verify(draftCommands)
                .prepareContentBinding(7L, 42L, "key", "etag", 10L, "sha");
        orderedDraftCalls.verify(draftCommands).bindPreparedContent(prepared);
        verify(metadataCommands).updateMetadata(
                7L, 42L, "title", 3L, List.of("tag"), List.of("image"),
                "public", false, "description");
        verify(publicationCommands).publish(7L, 42L);
        verify(metadataCommands).updateTop(7L, 42L, true);
        verify(metadataCommands).updateVisibility(7L, 42L, "private");
        verify(deletionCommands).delete(7L, 42L);
        verify(deletionCommands).adminUpdateVisibility(42L, "private");
        verify(deletionCommands).adminDelete(42L);
    }

    @Test
    void databaseOnlyWriteFacadeMethodsKeepTransactionalBoundary() throws Exception {
        List<Method> writes = List.of(
                method("createDraft", long.class),
                method("updateMetadata", long.class, long.class, String.class, Long.class, List.class, List.class,
                        String.class, Boolean.class, String.class),
                method("publish", long.class, long.class),
                method("updateTop", long.class, long.class, boolean.class),
                method("updateVisibility", long.class, long.class, String.class),
                method("delete", long.class, long.class),
                method("adminUpdateVisibility", long.class, String.class),
                method("adminDelete", long.class));

        assertThat(writes).allMatch(method -> method.isAnnotationPresent(Transactional.class));
    }

    @Test
    void contentConfirmationVerifiesOutsideTransactionThenUsesRequiresNewBinder() throws Exception {
        Method facade = method(
                "confirmContent", long.class, long.class, String.class, String.class, Long.class, String.class);
        Method prepare = PostDraftCommandService.class.getMethod(
                "prepareContentBinding",
                long.class,
                long.class,
                String.class,
                String.class,
                Long.class,
                String.class);
        Method bind = PostDraftCommandService.class.getMethod(
                "bindPreparedContent", PostDraftCommandService.PreparedContentBinding.class);

        assertThat(facade.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(prepare.getAnnotation(Transactional.class))
                .isNotNull()
                .extracting(Transactional::propagation)
                .isEqualTo(Propagation.NOT_SUPPORTED);
        assertThat(bind.getAnnotation(Transactional.class))
                .isNotNull()
                .extracting(Transactional::propagation)
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return PostServiceImpl.class.getMethod(name, parameterTypes);
    }
}
