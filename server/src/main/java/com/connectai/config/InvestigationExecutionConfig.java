package com.connectai.config;

import com.connectai.agent.persistence.AgentConversationRepository;
import com.connectai.service.InvestigationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Investigations run in the background on a small bounded pool: the POST returns the
 * RUNNING record immediately and the UI polls it. {@code connectai.investigations.async=false}
 * runs them on the calling thread instead (useful for tests and scripts).
 */
@Configuration
public class InvestigationExecutionConfig {

    private static final Logger log = LoggerFactory.getLogger(InvestigationExecutionConfig.class);

    static final String RESTART_REASON =
            "Interrupted: the server stopped before this investigation finished. Start it again.";

    private final InvestigationStore store;
    private final AgentConversationRepository conversations;

    public InvestigationExecutionConfig(InvestigationStore store, AgentConversationRepository conversations) {
        this.store = store;
        this.conversations = conversations;
    }

    @Bean(name = "investigationTaskExecutor")
    public TaskExecutor investigationTaskExecutor(
            @Value("${connectai.investigations.async:true}") boolean async,
            @Value("${connectai.investigations.concurrency:2}") int concurrency,
            @Value("${connectai.investigations.queue-capacity:20}") int queueCapacity) {

        if (!async) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("investigation-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /** Nothing survives a restart mid-run, so anything still RUNNING is failed honestly. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failAbandonedRuns() {
        int investigations = store.failAbandoned(RESTART_REASON);
        var running = conversations.findByStatus(com.connectai.agent.AgentExecutionStatus.RUNNING);
        running.forEach(conversation -> conversation.markFailed());
        conversations.saveAll(running);
        if (investigations > 0 || !running.isEmpty()) {
            log.warn("Marked {} investigation(s) and {} conversation(s) FAILED after restart",
                    investigations, running.size());
        }
    }
}
