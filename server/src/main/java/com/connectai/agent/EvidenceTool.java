package com.connectai.agent;

import com.connectai.domain.SourceType;

import java.util.List;

/**
 * A source the agent can ask for evidence (GitHub, Jira, Splunk, Slack ...).
 * Step 2 has fake implementations; Step 4 adds real ones behind this same interface,
 * so nothing else in the agent has to change.
 */
public interface EvidenceTool {

    SourceType source();

    /**
     * @param question       what the developer asked
     * @param evidenceSoFar  what earlier tools already found (a real Jira tool would look up
     *                       the ticket keys that GitHub evidence mentioned)
     */
    List<Finding> gather(String question, List<Finding> evidenceSoFar);
}
