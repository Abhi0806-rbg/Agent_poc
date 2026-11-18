package com.intutr.agents.trace;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes the run / step / llm_call / approval tables. Reads for the API use `jdbc` directly. */
@Repository
public class TraceRepo {
    private final JdbcTemplate jdbc;
    public TraceRepo(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public JdbcTemplate jdbc() { return jdbc; }

    public void createRun(String id, String tenant, String agent, String input, String userId, String parent, String corr, String hash, String chatId) {
        jdbc.update("INSERT INTO agent_run(id,tenant_id,agent,status,input,user_id,parent_run_id,correlation_id,config_hash,chat_id) VALUES (?,?,?,'RUNNING',?,?,?,?,?,?)",
            id, tenant, agent, input, userId, parent, corr, hash, chatId);
    }
    public void finishRun(String id, String status, String output, String errType, String errMsg, int tokens, double cost, long ms, String state) {
        jdbc.update("UPDATE agent_run SET status=?,output=?,error_type=?,error_message=?,total_tokens=?,cost_usd=?,duration_ms=?,state=? WHERE id=?",
            status, output, errType, errMsg, tokens, cost, ms, state, id);
    }
    public void setStatus(String id, String status) { jdbc.update("UPDATE agent_run SET status=? WHERE id=?", status, id); }
    public void step(String runId, int no, String type, String name, String input, String output, String error, long ms) {
        jdbc.update("INSERT INTO agent_step(run_id,step_no,type,name,input,output,error,duration_ms) VALUES (?,?,?,?,?,?,?,?)", runId, no, type, name, input, output, error, ms);
    }
    public void llmCall(String runId, int no, String provider, String model, int pt, int ct, double cost, long ms, String finish, String errType, int retry, boolean estimated) {
        jdbc.update("INSERT INTO llm_call(run_id,step_no,provider,model,prompt_tokens,completion_tokens,cost_usd,latency_ms,finish_reason,error_type,retry_no,estimated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            runId, no, provider, model, pt, ct, cost, ms, finish, errType, retry, estimated);
    }
    public void createApproval(String id, String runId, String tenant, String tool, String args) {
        jdbc.update("INSERT INTO approval(id,run_id,tenant_id,tool,args) VALUES (?,?,?,?,?)", id, runId, tenant, tool, args);
    }
    public void decideApproval(String id, String status, String by) {
        jdbc.update("UPDATE approval SET status=?, decided_at=now(), decided_by=? WHERE id=?", status, by, id);
    }
    public Map<String, Object> approval(String id) {
        List<Map<String, Object>> l = jdbc.queryForList("SELECT * FROM approval WHERE id=?", id);
        return l.isEmpty() ? null : l.get(0);
    }
    public Map<String, Object> run(String id) {
        List<Map<String, Object>> l = jdbc.queryForList("SELECT * FROM agent_run WHERE id=?", id);
        return l.isEmpty() ? null : l.get(0);
    }
    public String userName(String tenant, String user) {
        List<String> l = jdbc.queryForList("SELECT name FROM users WHERE tenant_id=? AND id=?", String.class, tenant, user);
        return l.isEmpty() ? "Learner" : l.get(0);
    }
}
