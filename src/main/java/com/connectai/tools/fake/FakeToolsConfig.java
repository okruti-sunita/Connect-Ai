package com.connectai.tools.fake;

import com.connectai.agent.EvidenceTool;
import com.connectai.domain.SourceType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the fake tools only when connectai.tools.mode=fake (the default).
 * In Step 4 the real tools get their own config with mode=mcp, so the two never collide.
 */
@Configuration
@ConditionalOnProperty(name = "connectai.tools.mode", havingValue = "fake", matchIfMissing = true)
public class FakeToolsConfig {

    @Bean
    public ScenarioCatalog scenarioCatalog() {
        return new ScenarioCatalog();
    }

    @Bean
    public EvidenceTool fakeGithubTool(ScenarioCatalog catalog) {
        return new FakeTool(SourceType.GITHUB, catalog);
    }

    @Bean
    public EvidenceTool fakeJiraTool(ScenarioCatalog catalog) {
        return new FakeTool(SourceType.JIRA, catalog);
    }

    @Bean
    public EvidenceTool fakeSplunkTool(ScenarioCatalog catalog) {
        return new FakeTool(SourceType.SPLUNK, catalog);
    }

    @Bean
    public EvidenceTool fakeSlackTool(ScenarioCatalog catalog) {
        return new FakeTool(SourceType.SLACK, catalog);
    }
}
