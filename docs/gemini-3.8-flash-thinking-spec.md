# Gemini 3.8 Flash `thinkingLevel` & Preset Migration Spec

## Overview & Decisions

1. **Q1: `GenerateContent` Thinking Parameter Dispatch (`GeminiGenerateContentProvider.kt`)**:
   - All modern Gemini 3.x / 4.x models (`isModernGemini(normalizedModel) == true`, across official `generativelanguage.googleapis.com`, Vertex AI `aiplatform.googleapis.com`, and third-party proxy gateways such as Antigravity Manager) send `thinkingLevel` instead of `thinkingBudget`.
   - `thinkingBudget` is retained strictly for legacy Gemini 2.5 models (`!isModernGemini(normalizedModel)`).
   - Map `ReasoningEffort` to `thinkingLevel` as:
     - `MINIMAL` -> `"MINIMAL"`
     - `LOW` -> `"LOW"`
     - `MEDIUM` -> `"MEDIUM"`
     - `HIGH`, `XHIGH`, `MAX` -> `"HIGH"`
     - `DEFAULT`, `OFF` -> omitted (`null`; `DEFAULT` sends `{"includeThoughts": true}` without `thinkingLevel`, `OFF` sends `{"includeThoughts": false}` without `thinkingBudget`).

2. **Q2: `gemini-3.8-flash` Capabilities & `Default` / `Off` Semantics**:
   - `GeminiModelCapabilities.kt` preserves documented thinking levels for `gemini-3.8-flash` (`LOW`, `MEDIUM`, `HIGH`, `defaultEffort = MEDIUM`, `mandatory = true`) while `Default` effort sends `{"includeThoughts": true}` without `thinkingLevel` so gateways/upstream apply their native default tier.

3. **Q3: `gemini-3.8-flash-tiered` Migration & Web Search Grounding (`OfficialModelCatalog.kt`, `ConfigurableWebBackends.kt`, `WebToolsSettingsRepository.kt`, `README.md`, `README_EN.md`)**:
   - Migrate `OfficialModelCatalog.curatedGeminiModels` primary entry from `gemini-3.8-flash-tiered` to `gemini-3.8-flash` (`id = "builtin-gemini-3-8-flash"`, `displayName = "Gemini 3.8 Flash"`).
   - Migrate default/fallback Gemini model in `ConfigurableWebBackends` and `WebToolsSettingsRepository` from `"gemini-3.8-flash-tiered"` to `"gemini-3.8-flash"`.
   - In `ConfigurableWebBackends.searchGemini`, omit `"thinkingBudget": 0` when the model is a modern Gemini 3.x/4.x model (send `{"includeThoughts": false}` only), keeping `"thinkingBudget": 0` only for legacy 2.5 models.
   - Update `README.md` and `README_EN.md` to reference `gemini-3.8-flash` and `thinkingLevel` (`MINIMAL` / `LOW` / `MEDIUM` / `HIGH`).

---

## Ticket Graph

- **Ticket 1 (Wave 1 - `GeminiGenerateContentProvider` `thinkingLevel` Dispatch & Unit Tests)**:
  - Scope:
    - `app/src/main/kotlin/io/github/mangi/eta/agent/model/GeminiGenerateContentProvider.kt`
    - `app/src/test/kotlin/io/github/mangi/eta/agent/model/VertexAiServiceAccountAuthTest.kt`
    - `app/src/test/kotlin/io/github/mangi/eta/agent/model/GeminiGenerateContentProviderTest.kt`
  - Unify `usesThinkingLevel = isModernGemini(normalizedModel)` in `buildThinkingConfig`, map `ReasoningEffort.MINIMAL` to `"MINIMAL"`, and add unit tests covering non-Vertex `gemini-3.8-flash` (`DEFAULT`, `MINIMAL`, `LOW`, `MEDIUM`, `HIGH`, `OFF`) as well as legacy `gemini-2.5-pro` (`thinkingBudget`).

- **Ticket 2 (Wave 1 - Catalog, Web Search Default Model & Grounding `thinkingConfig` Migration)**:
  - Scope:
    - `app/src/main/kotlin/io/github/mangi/eta/data/provider/OfficialModelCatalog.kt`
    - `app/src/main/kotlin/io/github/mangi/eta/agent/web/ConfigurableWebBackends.kt`
    - `app/src/main/kotlin/io/github/mangi/eta/data/repository/WebToolsSettingsRepository.kt`
    - `app/src/test/kotlin/io/github/mangi/eta/data/provider/OfficialModelCatalogTest.kt`
    - `app/src/test/kotlin/io/github/mangi/eta/agent/web/AgentWebToolsTest.kt`
    - `README.md`
    - `README_EN.md`
  - Replace `gemini-3.8-flash-tiered` defaults/curated presets with `gemini-3.8-flash`, stop sending `thinkingBudget: 0` for modern Gemini in `ConfigurableWebBackends.searchGemini`, and update corresponding tests and documentation.
