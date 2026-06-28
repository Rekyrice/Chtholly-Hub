package com.chtholly.common.tracing;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 异步任务执行前复制当前线程 MDC，执行后恢复。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = CorrelationIdSupport.copyContext();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                CorrelationIdSupport.setContext(context);
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
