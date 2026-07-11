package com.chtholly.agent;

import jakarta.annotation.PreDestroy;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;

class ChthollyAgentLifecycleTest {

    @Test
    void toolExecutorIsInstanceOwnedAndClosedOnBeanDestruction() {
        SoftAssertions.assertSoftly(softly -> {
            List<Field> executorFields = Arrays.stream(ChthollyAgent.class.getDeclaredFields())
                    .filter(field -> ExecutorService.class.isAssignableFrom(field.getType()))
                    .toList();
            softly.assertThat(executorFields)
                    .singleElement()
                    .satisfies(field -> softly.assertThat(Modifier.isStatic(field.getModifiers())).isFalse());
            softly.assertThat(Arrays.stream(ChthollyAgent.class.getDeclaredMethods())
                            .filter(method -> method.isAnnotationPresent(PreDestroy.class))
                            .map(Method::getName))
                    .contains("shutdownToolExecutor");
        });
    }
}
