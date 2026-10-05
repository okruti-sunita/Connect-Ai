package com.connectai.tools.fake;

import com.connectai.agent.EvidenceTool;
import com.connectai.agent.Finding;
import com.connectai.domain.SourceType;

import java.util.List;

/** One class, four uses: pretends to be GitHub, Jira, Splunk or Slack by reading the scenario catalog. */
public class FakeTool implements EvidenceTool {

    private final SourceType source;
    private final ScenarioCatalog catalog;

    public FakeTool(SourceType source, ScenarioCatalog catalog) {
        this.source = source;
        this.catalog = catalog;
    }

    @Override
    public SourceType source() {
        return source;
    }

    @Override
    public List<Finding> gather(String question, List<Finding> evidenceSoFar) {
        return catalog.findingsFor(question, source);
    }
}
