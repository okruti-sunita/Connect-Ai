package com.connectai.llm;

/** Small helpers for building prompts safely. */
final class PromptText {

    private PromptText() {
    }

    static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /**
     * Evidence comes from external systems, so it is untrusted. Escaping angle brackets stops a
     * malicious commit message or ticket from closing our <evidence> tag and smuggling in instructions.
     */
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
