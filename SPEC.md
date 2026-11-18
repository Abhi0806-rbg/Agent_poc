# SPEC: Intutr Agent Platform (POC)

Status: POC, v0.1. Owner: Beee. Goal: prove a **config-driven** agent framework that can run end to end on fake data, then swap in real keys.

## 1. Goals / non-goals

**Goals**
1. Add or change agents, prompts, tools, skills and models with **config only** (no engine code).
2. Support multiple LLM providers behind one interface (OpenAI, Anthropic, mock).
3. Agents can use SQL (read-only), embeddings (pgvector), HTTP and other agents as tools.
4. Every run is traceable: steps, LLM calls, tokens, cost, errors, config version.
5. Safety enforced **in code**: budgets, approvals, tenant isolation, identity binding.
6. Runs offline with seeded data; real models need only keys + model ids.

**Non-goals (POC)**: database-per-tenant routing, Firebase JWT, Spring AI, model-token streaming, production auth, rate limiting per tenant, cost alerts, skills hot-loading from DB, UI exactness vs the Figma wireframe.

## 2. Architecture

```
React (Chat UI + Console)  --/api (REST + SSE)-->  Spring Boot
                                                   |- ConfigRegistry (YAML -> validated snapshot)
                                                   |- AgentEngine (loop, budgets, approvals)
                                                   |    |- LlmGateway -> Mock | OpenAI | Anthropic
                                                   |    '- ToolExecutor -> sql | vector_search | http | agent
                                                   |- TraceRepo -> agent_run / agent_step / llm_call / approval
                                                   '- Retrieval -> pgvector (HNSW, cosine)
PostgreSQL 16 + pgvector (Liquibase-managed)
```

Layers: Orchestrator (supervisor via agent-as-tool) > Agent (config) > {LLM gateway, tools, memory/knowledge, tracer}.

## 3. Config (source of truth: `backend/config/`)

Unknown YAML keys are **rejected**. `${NAME:default}` is replaced from environment variables. Keys are snake_case.

| File | Purpose | Key fields |
|---|---|---|
| `platform.yaml` | defaults | default_model, limits{max_steps,max_tokens,max_cost_usd}, tool_result_max_chars, history_turns, max_agent_depth |
| `models.yaml` | model aliases | provider (openai/anthropic/mock), name, fallback, max_output_tokens, temperature?, input/output_cost_per_million, dimensions (embed=384) |
| `guardrails.yaml` | safety | sql_allowed_relations, sql_max_rows, sql_timeout_ms, http_allowed_hosts, approvals_required_for |
| `prompts/*.yaml` | prompts | name, version, system (`{{tenant.name}}`, `{{user.name}}`, `{{today}}`, `{{language}}`) |
| `skills/*.yaml` | instructions + tools | name, description, instructions, tools[] |
| `tools/*.yaml` | tools | name, type, description, params, plus type fields (below) |
| `agents/*.yaml` | agents | name, enabled, description, model, prompt (`name@version` or `name`), skills[], tools[], limits{}, suggestions[] |
| `evals/*.yaml` | agent eval cases | agent, cases[{id,input,calls_tools,not_calls_tools,answer_not_contains,max_steps}] |
| `knowledge/retrieval_eval.yaml` | retrieval pairs | pairs[{query,expected_title}] |

**Tool types**
- `sql`: `query` (fixed SELECT, named `:params`).
- `vector_search`: `collection`, `top_k`, `min_score`.
- `http`: `method`, `url` (`mock://...` is simulated; real hosts need allowlisting), `requires_approval`.
- `agent`: `agent` (delegates a `task` to another agent).

**Param fields**: `type` (string/integer/number/boolean), `required`, `default`, `max`, `source`.
`source: context.user_id | context.tenant | context.language` binds the value from the session. Such params are **hidden from the model** and any model-supplied value is ignored.

**Startup validation (fails loudly)**: all references exist (model, fallback, prompt, tool, skill, agent); tool type valid; SQL is `SELECT` only, no `;`, relations on the allowlist, `:tenant` filter present, every `:placeholder` is a declared param; `source` values valid; no agent-as-tool cycles; embed dimension = 384; platform fields present.
A reload with errors keeps the previous config. Saving an agent through the API validates and **rolls back** on error.

Adding things: new agent/prompt/tool/skill/model alias = a file. New tool **type** = one `ToolHandler` class. New provider = one `LlmClient` class.

## 4. Engine behaviour

Loop per run: build messages (system prompt + skill instructions + history + user) > call model > if tool calls: execute each > append results > repeat > final answer.

- **Limits**: max_steps, max_tokens, max_cost_usd (agent overrides platform). Checked before and after each model call. Failure types: `max_steps`, `budget_exceeded`.
- **Loop detection**: identical tool call + args 3 times = `loop_detected`.
- **Approval**: tools with `requires_approval` (or in `approvals_required_for`) pause the run: state stored as JSON in `agent_run.state`, status `WAITING_APPROVAL`, row in `approval`. `POST /approvals/{id}/decision` resumes (approved) or tells the model it was rejected.
- **Tool errors** are returned to the model as `ERROR: ...` so it can recover. Tool output is truncated to `tool_result_max_chars`.
- **Sub-agents**: depth capped by `max_agent_depth`. A sub-agent that needs approval fails the parent call (POC limitation).
- **Status values**: RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, BUDGET_EXCEEDED.
- **Error types**: provider_rate_limit, provider_timeout, provider_5xx, provider_error, circuit_open, config, schema_invalid*, tool_error*, max_steps, budget_exceeded, loop_detected, agent_disabled, engine_error. (*reserved, shown in seed data and the fix-layer hints.)

**LLM gateway**: up to 3 attempts per model with backoff (300/900/2000 ms), retry only on retryable errors (429, 5xx, timeout), then the model's `fallback` alias once. Circuit breaker per provider: 5 consecutive failures open it for 30 s. Every attempt writes an `llm_call` row (tokens, cost, latency, finish reason, error type, retry number, estimated flag). If a provider has no key or no model id, the **mock client** serves that alias.

**Mock client** (offline): picks a tool by keyword, then summarises the tool result. Magic words: `simulate error`, `simulate budget`.

## 5. Data model (Liquibase `001-init.sql`)

Domain (fake): `users`, `user_group`, `user_group_member`, `course`, `user_progress`; views `v_user_progress`, `v_group_summary` (what SQL tools may read).
Knowledge: `knowledge_chunk(tenant_id, collection, title, text, content_hash, embedding vector(384), embed_model, embed_version, is_synthetic)` with an HNSW cosine index.
Observability: `agent_run`, `agent_step`, `llm_call`, `approval`. Chat: `chat`, `chat_message`.
Every table is scoped by `tenant_id` (POC uses one DB; Intutr would route per tenant database).

Seed (idempotent, fixed random seed 42): 2 tenants x (15 users, 3 groups, 6 courses, progress rows, 15 content chunks, 20 synthetic runs). All synthetic; seed costs use made-up prices.

## 6. Embeddings and retrieval

- Offline: hashed bag-of-words (384 dims, L2-normalised). With `OPENAI_API_KEY` + `MODEL_EMBED`: real embeddings requested with `dimensions=384`.
- Search: cosine similarity, always filtered by tenant and collection, `min_score` threshold.
- Changing the embedder requires re-index (`POST /api/admin/reindex`); `embed_version` increments. Never mix models in one index.

## 7. API (all under `/api`; headers `X-Tenant`, `X-User`, `X-Role`)

| Area | Endpoints |
|---|---|
| Identity | `GET /me` |
| Agents | `GET /agents`, `GET /agents/{n}`, `POST /agents/{n}/enabled`, `PUT /agents/{n}/yaml` (text/plain) |
| Config | `GET /config/validate`, `GET /prompts /tools /skills /models /guardrails`, `POST /tools/{n}/test` |
| Runs | `GET /runs?agent&status&limit`, `GET /runs/{id}`, `POST /runs/{id}/replay {agent?,model?}` |
| Approvals | `GET /approvals`, `POST /approvals/{id}/decision {approved}` |
| Knowledge | `GET /knowledge/chunks?q`, `POST /knowledge/search`, `POST /admin/reindex` |
| Evals | `POST /evals/run`, `GET /evals/latest` |
| Insights | `GET /dashboard`, `GET /cost` |
| Chat | `GET /chats`, `GET/PATCH/DELETE /chats/{id}`, `POST /chat/stream` (SSE: `chat`, `step`, `approval`, `delta`, `done`) |

Roles (header, POC): Learner, Manager (can decide approvals), Developer, Admin (config, replay, tools test, reindex, evals).

## 8. Security (POC level)

- Tool params bound from session; model cannot choose the user or tenant.
- SQL: validated fixed queries, read-only transaction, statement timeout, row cap, allowlisted views, tenant filter required.
- Guardrails run in code before each tool call; HTTP hosts allowlisted; mutating tools need approval.
- Secrets only from environment variables; never returned by the API (`/models` shows only live/mock).
- Known gaps: header-based identity (spoofable), no per-tenant rate limits, prompts and tool I/O are stored in plaintext in trace tables (add redaction + retention before production), no PII handling.

## 9. Observability

Per run: status, error type/message, tokens, cost, duration, config hash, correlation id, parent run. Per step: type, name, input, output, error, duration. Per LLM call: provider, model, tokens (estimated flag), cost, latency, retry. Logs include `run=<id>` via MDC. Console: dashboard (24h KPIs, errors by type, cost by agent), run trace waterfall with replay, cost page (top runs by tokens).

Cost = tokens x configured price per million. Prices are **not** shipped; cost reads 0 until configured.

## 10. Evals

`POST /evals/run` runs agent cases (tool calls, forbidden tools, forbidden answer text, step cap) as demo user u1 and retrieval pairs (recall@5, MRR). Report fields: question, baseline, metrics, confidence, gap to real world, per-case rows. Latest report is kept in memory.
Limits: 7 agent cases and 8 retrieval pairs are a **smoke test**, not statistics; retrieval questions overlap the chunk text so offline scores are inflated; mock mode measures plumbing only.

## 11. Acceptance criteria

1. `docker compose up --build` then http://localhost:3000 works with no keys.
2. Each demo in README "Try this" behaves as described.
3. A YAML typo (for example `max_step`) stops startup with a clear message; the same typo via the Agents page is rejected and rolled back.
4. A tool call never uses a user id other than the session's.
5. With keys + model ids set, Console, Models shows `live`, and traces show real token counts.
6. `mvn test` passes (config validity, SQL guardrails, param binding, mock behaviour).

## 12. Open questions / next steps

1. Port to Intutr: Firebase JWT claims for identity/role, database-per-tenant routing (HikariCP pools), Liquibase in the existing changelog.
2. Decide Spring AI vs the thin HTTP clients here.
3. Replace the retrieval eval with real learner questions (30+), add a held-out split.
4. Add token-level streaming, per-tenant budgets and alerts, payload redaction + retention.
5. Verify current provider pricing and fill `models.yaml`.
6. Restyle the UI to the Figma wireframe once its export is available.
