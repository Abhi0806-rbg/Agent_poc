--liquibase formatted sql

--changeset intutr:3
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE agent_config (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id varchar(80) NOT NULL,
  config_type varchar(40) NOT NULL,
  config_identifier varchar(120) NOT NULL,
  config_name varchar(200) NOT NULL,
  configuration jsonb NOT NULL,
  source varchar(20) NOT NULL DEFAULT 'DATABASE' CHECK (source IN ('YAML','DATABASE')),
  version int NOT NULL DEFAULT 1 CHECK (version > 0),
  status varchar(30) NOT NULL DEFAULT 'ACTIVE',
  created_by varchar(120) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, config_type, config_identifier),
  CHECK (jsonb_typeof(configuration) = 'object'),
  CHECK (source = 'DATABASE')
);
CREATE INDEX idx_agent_config_tenant_type ON agent_config (tenant_id, config_type, updated_at DESC);

-- Domain-specific updatable views keep each configuration family discoverable while
-- sharing one JSONB persistence model and one version-history implementation.
CREATE VIEW prompt_config AS SELECT * FROM agent_config WHERE config_type='prompt';
CREATE VIEW tool_config AS SELECT * FROM agent_config WHERE config_type='tool';
CREATE VIEW skill_config AS SELECT * FROM agent_config WHERE config_type='skill';
CREATE VIEW model_config AS SELECT * FROM agent_config WHERE config_type='model';
CREATE VIEW guardrail_config AS SELECT * FROM agent_config WHERE config_type='guardrail';
CREATE VIEW eval_config AS SELECT * FROM agent_config WHERE config_type='eval';
CREATE VIEW approval_rule AS SELECT * FROM agent_config WHERE config_type='approval_rule';
CREATE VIEW model_pricing AS SELECT * FROM agent_config WHERE config_type='model_pricing';
-- Usage already lives in llm_call; expose its existing records without duplicating storage.
CREATE VIEW token_usage AS SELECT id, run_id, provider, model, prompt_tokens, completion_tokens, cost_usd, created_at FROM llm_call;

CREATE TABLE config_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  config_id uuid NOT NULL REFERENCES agent_config(id) ON DELETE RESTRICT,
  version int NOT NULL CHECK (version > 0),
  configuration jsonb NOT NULL,
  changed_by varchar(120) NOT NULL,
  change_description text,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (config_id, version)
);
CREATE INDEX idx_config_versions_config ON config_versions (config_id, version DESC);
