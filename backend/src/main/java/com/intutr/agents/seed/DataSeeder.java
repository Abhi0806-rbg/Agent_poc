package com.intutr.agents.seed;

import com.intutr.agents.knowledge.Embedder;
import com.intutr.agents.knowledge.Embedders;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Idempotent, deterministic fake data (fixed random seed). Everything is synthetic. */
@Component
public class DataSeeder implements CommandLineRunner {
    private static final String[][] CHUNKS = {
        {"Fundamental Rights", "Part III of the Indian Constitution guarantees fundamental rights such as equality before law, freedom of speech and expression, protection of life and personal liberty, and freedom of religion. They can be enforced through the Supreme Court and High Courts."},
        {"Directive Principles of State Policy", "Directive Principles in Part IV are guidelines for the state to promote social and economic welfare. They are not enforceable by courts but are fundamental in governance."},
        {"Preamble of the Constitution", "The Preamble declares India a sovereign, socialist, secular, democratic republic and secures justice, liberty, equality and fraternity for all citizens."},
        {"Federalism in India", "India has a federal structure with a strong Centre. The Seventh Schedule divides powers between the Union and the states through the Union, State and Concurrent lists."},
        {"Parliament of India", "Parliament has the President, the Lok Sabha and the Rajya Sabha. Laws are made through bills that must pass both houses, with special rules for money bills."},
        {"Judicial Review", "Courts can strike down laws that violate the Constitution. The basic structure doctrine limits Parliament's power to amend core features."},
        {"Indian Monsoon", "The southwest monsoon brings most of India's annual rainfall between June and September. Its timing and strength strongly affect agriculture and the economy."},
        {"Rivers of India", "Himalayan rivers like the Ganga and Brahmaputra are perennial, fed by rain and snowmelt. Peninsular rivers like the Godavari and Krishna depend mainly on monsoon rain."},
        {"Indus Valley Civilization", "The Indus Valley Civilization, around 2500 BCE, had planned cities such as Harappa and Mohenjo-daro with drainage systems, standard weights and extensive trade."},
        {"Mauryan Empire", "The Mauryan Empire was founded by Chandragupta Maurya. Ashoka, after the Kalinga war, adopted Buddhism and spread the policy of dhamma."},
        {"Non-Cooperation Movement", "Launched in 1920 under Mahatma Gandhi, the movement urged Indians to withdraw from British institutions, boycott foreign goods and practise nonviolent resistance."},
        {"Inflation", "Inflation is a sustained rise in the general price level. Central banks manage it mainly through interest rate policy and control of money supply."},
        {"GDP and Economic Growth", "Gross Domestic Product measures the total value of goods and services produced in a country. Growth is the yearly percentage change in real GDP."},
        {"Biodiversity Hotspots", "A biodiversity hotspot is a region with exceptional species diversity that is under serious threat. India includes parts of the Himalaya, Western Ghats and Indo-Burma hotspots."},
        {"Climate Change and the Paris Agreement", "The Paris Agreement commits countries to limit global warming well below 2 degrees Celsius above pre-industrial levels through nationally determined contributions."},
    };
    private static final String[] NAMES = {"Asha", "Ravi", "Meera", "Karan", "Divya", "Imran", "Neha", "Arjun", "Sana", "Vikram", "Pooja", "Rahul", "Isha", "Dev", "Anita"};
    private static final String[][] COURSES = {{"c1", "Indian Polity"}, {"c2", "Modern History"}, {"c3", "Geography Basics"}, {"c4", "Economy Essentials"}, {"c5", "Environment and Ecology"}, {"c6", "Ancient and Medieval India"}};
    private static final String[] AGENTS = {"advisor", "report-agent", "supervisor", "content-agent"};
    private static final String[] ERRS = {"provider_rate_limit", "schema_invalid", "tool_error", "max_steps"};

    private final JdbcTemplate jdbc;
    private final Embedders embedders;
    public DataSeeder(JdbcTemplate jdbc, Embedders embedders) { this.jdbc = jdbc; this.embedders = embedders; }

    @Override
    public void run(String... args) throws Exception {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
        if (n != null && n > 0) return;
        Random rnd = new Random(42);
        Embedder emb = embedders.current();
        for (String tenant : new String[]{"acme", "globex"}) {
            for (int i = 0; i < NAMES.length; i++)
                jdbc.update("INSERT INTO users(id,tenant_id,name,role) VALUES (?,?,?,?)", "u" + (i + 1), tenant, NAMES[i], i == 0 ? "Manager" : "Learner");
            for (int g = 1; g <= 3; g++) jdbc.update("INSERT INTO user_group(id,tenant_id,name) VALUES (?,?,?)", "g" + g, tenant, "Batch " + (char) ('A' + g - 1));
            for (int i = 0; i < NAMES.length; i++)
                jdbc.update("INSERT INTO user_group_member(tenant_id,group_id,user_id) VALUES (?,?,?)", tenant, "g" + (i % 3 + 1), "u" + (i + 1));
            for (String[] c : COURSES) jdbc.update("INSERT INTO course(id,tenant_id,name) VALUES (?,?,?)", c[0], tenant, c[1]);
            for (int i = 0; i < NAMES.length; i++) for (String[] c : COURSES) {
                if (rnd.nextInt(6) == 0) continue;
                jdbc.update("INSERT INTO user_progress(tenant_id,user_id,course_id,percent,deadline,last_accessed,time_spent_min) VALUES (?,?,?,?,?,?,?)",
                    tenant, "u" + (i + 1), c[0], rnd.nextInt(101), java.sql.Date.valueOf(LocalDate.now().plusDays(rnd.nextInt(24) - 3)),
                    java.sql.Date.valueOf(LocalDate.now().minusDays(rnd.nextInt(10))), 20 + rnd.nextInt(400));
            }
            for (String[] ch : CHUNKS)
                jdbc.update("INSERT INTO knowledge_chunk(id,tenant_id,collection,title,text,content_hash,embedding,embed_model) VALUES (?,?,?,?,?,?,?::vector,?)",
                    UUID.randomUUID().toString(), tenant, "lob_content", ch[0], ch[1], sha(ch[1]), Embedders.toVector(emb.embed(ch[0] + ". " + ch[1])), emb.modelId());
            // synthetic history so the dashboard is not empty. Costs here are fake and only for the charts.
            for (int i = 0; i < 20; i++) {
                String id = UUID.randomUUID().toString();
                String agent = AGENTS[rnd.nextInt(AGENTS.length)];
                int roll = rnd.nextInt(100);
                String status = roll < 72 ? "COMPLETED" : roll < 94 ? "FAILED" : "BUDGET_EXCEEDED";
                String err = "COMPLETED".equals(status) ? null : "BUDGET_EXCEEDED".equals(status) ? "budget_exceeded" : ERRS[rnd.nextInt(ERRS.length)];
                int tokens = 500 + rnd.nextInt(3500);
                double cost = tokens * 0.000002;
                int hoursAgo = rnd.nextInt(72);
                jdbc.update("INSERT INTO agent_run(id,tenant_id,agent,status,input,output,error_type,total_tokens,cost_usd,duration_ms,user_id,correlation_id,config_hash,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, now() - (? * interval '1 hour'))",
                    id, tenant, agent, status, "[seed] sample question " + (i + 1), "COMPLETED".equals(status) ? "[seed] sample answer" : null, err, tokens, cost,
                    600 + rnd.nextInt(5000), "u" + (1 + rnd.nextInt(NAMES.length)), "seed" + i, "seedseedseed", hoursAgo);
                jdbc.update("INSERT INTO agent_step(run_id,step_no,type,name,output,duration_ms) VALUES (?,?,?,?,?,?)", id, 1, "llm", "seed-model", "[seed]", 400 + rnd.nextInt(2000));
                jdbc.update("INSERT INTO llm_call(run_id,step_no,provider,model,prompt_tokens,completion_tokens,cost_usd,latency_ms,finish_reason,error_type,retry_no,estimated,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, now() - (? * interval '1 hour'))",
                    id, 1, rnd.nextBoolean() ? "openai" : "anthropic", "seed-model", tokens * 3 / 4, tokens / 4, cost, 400 + rnd.nextInt(2000), "stop", null, 0, true, hoursAgo);
            }
        }
    }

    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
