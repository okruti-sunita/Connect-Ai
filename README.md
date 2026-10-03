
# React + TypeScript + Vite

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend enabling type-aware lint rules by installing `oxlint-tsgolint` and editing `.oxlintrc.json`:

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "typescript", "oxc"],
  "options": {
    "typeAware": true
  },
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

See the [Oxlint rules documentation](https://oxc.rs/docs/guide/usage/linter/rules) for the full list of rules and categories.

# Connect AI

Spring Boot 3.5 / Java 21 / H2 (in-memory). No external services needed.

## Progress
- Step 1: data model, REST API, validation, seed data
- Step 2: the agent loop (Planner, Tools, Ranker, AnswerGenerator) running on fake tools
- Step 3: Claude-backed Planner + AnswerGenerator, with guardrails and fallbacks  <- you are here
- Step 4 (next): real tools through MCP (GitHub first)

## Open in IntelliJ
1. File > Open > select `pom.xml` > Open as Project.
2. File > Project Structure > Project SDK = JDK 21.
3. Wait for Maven to finish importing, then run `ConnectAiApplication`.

## Try the agent (IntelliJ: open http/investigations.http and click the green arrows)
POST /api/investigations with one of these questions:
- "Why did payment failures start after release 2.4.1?"   -> 4 tools, Splunk evidence ranked first
- "Why are orders stuck in Payment Processing?"           -> the event-schema / dead-letter-queue story
- "What changed in the checkout service last week?"       -> only GitHub + Jira (logs skipped)
- "What is the weather today?"                            -> honest "no evidence" answer
Watch the IntelliJ console: every step the agent takes is logged.

## Turning the LLM on (optional - the app works fully without it)
Default is OFF: rules + template, no network calls, no cost.
1. Get an API key from the Anthropic Console.
2. IntelliJ: Run > Edit Configurations > select ConnectAiApplication > Environment variables:
   `ANTHROPIC_API_KEY=your-key;CONNECTAI_LLM_ENABLED=true`
3. Run, then POST the same questions. The console now shows lines like
   `LLM planner chose JIRA because: ...`.
Never put the key in application.yml or commit it (`.env` and `*.key` are git-ignored).
Model and limits: `connectai.llm.*` in application.yml (default is a small, cheap model).
Note: evidence text is sent to the provider - fine for the fake demo data, decide policy before real data.

## Tests
`mvn test` - expect 38 passing: 30 fast unit tests (agent/ and llm/, no Spring) and 8 Spring tests.

## Switching to PostgreSQL later
Everything is prepared but switched off: see the commented blocks in `pom.xml`
and `application.yml`, plus `docker-compose.yml`.

## Layout
- `domain/`      entities + enums
- `repository/`  Spring Data JPA
- `agent/`       the loop + its interfaces (no HTTP, DB or vendor code in here)
- `llm/`         model client, LLM planner, LLM answer generator, grounding check
- `tools/fake/`  fake GitHub/Jira/Splunk/Slack for demos and tests
- `service/`     runs the agent and stores the result
- `api/`         thin REST controller + DTOs
- `config/`      demo data seeder

