package com.connectai.agent;

import java.util.List;

/** Turns ranked evidence into the final answer text. Step 3 replaces the template with an LLM. */
public interface AnswerGenerator {

    String generate(String question, List<Finding> rankedEvidence);
}
