--liquibase formatted sql

--changeset poc:1
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (id varchar(20), tenant_id varchar(20), name varchar(100), role varchar(20), PRIMARY KEY (tenant_id, id));
CREATE TABLE user_group (id varchar(20), tenant_id varchar(20), name varchar(100), PRIMARY KEY (tenant_id, id));
CREATE TABLE user_group_member (tenant_id varchar(20), group_id varchar(20), user_id varchar(20), PRIMARY KEY (tenant_id, group_id, user_id));
CREATE TABLE course (id varchar(20), tenant_id varchar(20), name varchar(150), PRIMARY KEY (tenant_id, id));
CREATE TABLE user_progress (tenant_id varchar(20), user_id varchar(20), course_id varchar(20), percent int, deadline date, last_accessed date, time_spent_min int, PRIMARY KEY (tenant_id, user_id, course_id));

CREATE VIEW v_user_progress AS
  SELECT p.tenant_id, p.user_id, c.name AS course_name, p.percent, p.deadline
  FROM user_progress p JOIN course c ON c.tenant_id = p.tenant_id AND c.id = p.course_id;

CREATE VIEW v_group_summary AS
  SELECT g.tenant_id, g.id AS group_id, g.name AS group_name,
         count(DISTINCT m.user_id) AS learners,
         COALESCE(round(avg(p.percent)), 0) AS avg_percent,
         count(DISTINCT CASE WHEN p.percent < 50 AND p.deadline < current_date + 7 THEN m.user_id END) AS at_risk
  FROM user_group g
  LEFT JOIN user_group_member m ON m.tenant_id = g.tenant_id AND m.group_id = g.id
  LEFT JOIN user_progress p ON p.tenant_id = m.tenant_id AND p.user_id = m.user_id
  GROUP BY g.tenant_id, g.id, g.name;

CREATE TABLE knowledge_chunk (
  id varchar(36) PRIMARY KEY, tenant_id varchar(20), collection varchar(50), title varchar(200), text text,
  content_hash varchar(64), embedding vector(384), embed_model varchar(100), embed_version int DEFAULT 1, is_synthetic boolean DEFAULT true);
CREATE INDEX idx_chunk_hnsw ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_tenant ON knowledge_chunk (tenant_id, collection);

CREATE TABLE agent_run (
  id varchar(36) PRIMARY KEY, tenant_id varchar(20), agent varchar(100), status varchar(30), input text, output text,
  error_type varchar(50), error_message text, total_tokens int DEFAULT 0, cost_usd numeric(12,6) DEFAULT 0, duration_ms bigint DEFAULT 0,
  parent_run_id varchar(36), user_id varchar(20), correlation_id varchar(40), config_hash varchar(20), chat_id varchar(36), state text,
  created_at timestamptz DEFAULT now());
CREATE INDEX idx_run_tenant ON agent_run (tenant_id, created_at DESC);

CREATE TABLE agent_step (
  id bigserial PRIMARY KEY, run_id varchar(36), step_no int, type varchar(20), name varchar(150), input text, output text, error text,
  duration_ms bigint, created_at timestamptz DEFAULT now());
CREATE INDEX idx_step_run ON agent_step (run_id);

CREATE TABLE llm_call (
  id bigserial PRIMARY KEY, run_id varchar(36), step_no int, provider varchar(40), model varchar(100), prompt_tokens int, completion_tokens int,
  cost_usd numeric(12,6), latency_ms bigint, finish_reason varchar(40), error_type varchar(50), retry_no int, estimated boolean,
  created_at timestamptz DEFAULT now());
CREATE INDEX idx_llm_run ON llm_call (run_id);

CREATE TABLE approval (
  id varchar(36) PRIMARY KEY, run_id varchar(36), tenant_id varchar(20), tool varchar(100), args text, status varchar(20) DEFAULT 'PENDING',
  created_at timestamptz DEFAULT now(), decided_at timestamptz, decided_by varchar(20));

CREATE TABLE chat (id varchar(36) PRIMARY KEY, tenant_id varchar(20), user_id varchar(20), title varchar(200), agent varchar(100), project varchar(100), pinned boolean DEFAULT false, created_at timestamptz DEFAULT now());
CREATE TABLE chat_message (id bigserial PRIMARY KEY, chat_id varchar(36), role varchar(20), content text, run_id varchar(36), created_at timestamptz DEFAULT now());
CREATE INDEX idx_msg_chat ON chat_message (chat_id, id);
