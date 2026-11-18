package com.intutr.agents.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.intutr.agents.config.Cfg.*;
import com.intutr.agents.util.J;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Loads the YAML config folder, substitutes ${ENV:default}, validates, and serves an immutable snapshot. */
@Component
public class ConfigRegistry {
    public record Snapshot(Platform platform, Map<String, ModelDef> models, Guardrails guardrails, Map<String, Prompt> prompts,
                           Map<String, SkillDef> skills, Map<String, ToolDef> tools, Map<String, AgentDef> agents,
                           Map<String, String> agentYaml, List<EvalSet> evals, RetrievalEval retrieval) {}

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");
    private final Path dir;
    private final ObjectMapper yaml = YAMLMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private volatile Snapshot snap;
    private final Map<String, Boolean> enabledOverride = new ConcurrentHashMap<>();

    public ConfigRegistry(@Value("${agent.config-dir:./config}") String dir) { this.dir = Path.of(dir); }

    @PostConstruct
    public void init() {
        List<String> errs = reload();
        if (!errs.isEmpty()) throw new IllegalStateException("Invalid config in " + dir.toAbsolutePath() + ":\n - " + String.join("\n - ", errs));
    }

    /** Returns validation errors; the active snapshot only changes when there are none. */
    public synchronized List<String> reload() {
        List<String> errs = new ArrayList<>();
        Snapshot s = load(errs);
        if (errs.isEmpty()) errs.addAll(ConfigValidator.validate(s));
        if (errs.isEmpty()) snap = s;
        return errs;
    }

    private Snapshot load(List<String> errs) {
        Platform platform = read(dir.resolve("platform.yaml"), Platform.class, errs);
        ModelsFile mf = read(dir.resolve("models.yaml"), ModelsFile.class, errs);
        Guardrails g = read(dir.resolve("guardrails.yaml"), Guardrails.class, errs);
        Map<String, Prompt> prompts = new LinkedHashMap<>();
        for (Prompt p : readAll("prompts", Prompt.class, errs)) {
            if (p.name() == null || p.version() == null) { errs.add("prompt file missing name/version"); continue; }
            prompts.put(p.name() + "@" + p.version(), p);
            Prompt cur = prompts.get(p.name());
            if (cur == null || cur.version() < p.version()) prompts.put(p.name(), p);
        }
        Map<String, SkillDef> skills = byName(readAll("skills", SkillDef.class, errs), SkillDef::name, "skill", errs);
        Map<String, ToolDef> tools = byName(readAll("tools", ToolDef.class, errs), ToolDef::name, "tool", errs);
        Map<String, AgentDef> agents = new LinkedHashMap<>();
        Map<String, String> agentYaml = new LinkedHashMap<>();
        Path ad = dir.resolve("agents");
        if (Files.isDirectory(ad)) {
            try (Stream<Path> st = Files.list(ad)) {
                for (Path p : st.filter(x -> x.toString().endsWith(".yaml")).sorted().toList()) {
                    AgentDef a = read(p, AgentDef.class, errs);
                    if (a == null) continue;
                    if (a.name() == null) { errs.add(p.getFileName() + ": name is required"); continue; }
                    agents.put(a.name(), a);
                    try { agentYaml.put(a.name(), Files.readString(p)); } catch (IOException ignored) { }
                }
            } catch (IOException e) { errs.add("cannot list agents: " + e.getMessage()); }
        }
        List<EvalSet> evals = readAll("evals", EvalSet.class, errs);
        RetrievalEval re = Files.exists(dir.resolve("knowledge/retrieval_eval.yaml")) ? read(dir.resolve("knowledge/retrieval_eval.yaml"), RetrievalEval.class, errs) : new RetrievalEval(List.of());
        if (platform == null) errs.add("platform.yaml missing or invalid");
        if (mf == null || mf.models() == null) { errs.add("models.yaml missing or invalid"); mf = new ModelsFile(Map.of()); }
        return new Snapshot(platform, mf.models(), g, prompts, skills, tools, agents, agentYaml, evals, re);
    }

    private <T> Map<String, T> byName(List<T> list, java.util.function.Function<T, String> f, String kind, List<String> errs) {
        Map<String, T> m = new LinkedHashMap<>();
        for (T t : list) {
            String n = f.apply(t);
            if (n == null) errs.add(kind + " file missing name"); else if (m.put(n, t) != null) errs.add("duplicate " + kind + " name: " + n);
        }
        return m;
    }

    private <T> List<T> readAll(String sub, Class<T> c, List<String> errs) {
        List<T> out = new ArrayList<>();
        Path d = dir.resolve(sub);
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> st = Files.list(d)) {
            for (Path p : st.filter(x -> x.toString().endsWith(".yaml")).sorted().toList()) { T t = read(p, c, errs); if (t != null) out.add(t); }
        } catch (IOException e) { errs.add("cannot list " + sub + ": " + e.getMessage()); }
        return out;
    }

    private <T> T read(Path p, Class<T> c, List<String> errs) {
        try {
            return yaml.readValue(subst(Files.readString(p)), c);
        } catch (Exception e) {
            errs.add(p.getFileName() + ": " + String.valueOf(e.getMessage()).split("\n")[0]);
            return null;
        }
    }

    public static String subst(String text) {
        Matcher m = ENV.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = System.getenv(m.group(1));
            if (v == null || v.isBlank()) v = m.group(2) == null ? "" : m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- accessors ----
    public Platform platform() { return snap.platform(); }
    public Map<String, ModelDef> models() { return snap.models(); }
    public ModelDef model(String alias) { return snap.models().get(alias); }
    public Guardrails guardrails() { return snap.guardrails(); }
    public Map<String, Prompt> prompts() { return snap.prompts(); }
    public Map<String, SkillDef> skills() { return snap.skills(); }
    public Map<String, ToolDef> tools() { return snap.tools(); }
    public ToolDef tool(String n) { return snap.tools().get(n); }
    public Map<String, AgentDef> agents() { return snap.agents(); }
    public AgentDef agent(String n) { return snap.agents().get(n); }
    public String agentYaml(String n) { return snap.agentYaml().get(n); }
    public List<EvalSet> evals() { return snap.evals(); }
    public RetrievalEval retrieval() { return snap.retrieval(); }

    public boolean isEnabled(AgentDef a) { return enabledOverride.getOrDefault(a.name(), a.enabled() == null || a.enabled()); }
    public void setEnabled(String agent, boolean on) { enabledOverride.put(agent, on); }

    public Limits limits(AgentDef a) {
        Limits d = platform().limits(), o = a.limits();
        return new Limits(o != null && o.maxSteps() != null ? o.maxSteps() : d.maxSteps(),
                o != null && o.maxTokens() != null ? o.maxTokens() : d.maxTokens(),
                o != null && o.maxCostUsd() != null ? o.maxCostUsd() : d.maxCostUsd());
    }

    /** Agent tools plus tools bundled in its skills (deduplicated, order kept). */
    public List<String> toolsFor(AgentDef a) {
        Set<String> out = new LinkedHashSet<>();
        if (a.tools() != null) out.addAll(a.tools());
        if (a.skills() != null) for (String s : a.skills()) { SkillDef sd = skills().get(s); if (sd != null && sd.tools() != null) out.addAll(sd.tools()); }
        return new ArrayList<>(out);
    }

    public String renderSystem(AgentDef a, Map<String, String> vars) {
        String text = prompts().get(a.prompt()).system();
        StringBuilder sb = new StringBuilder(text.trim());
        if (a.skills() != null) for (String s : a.skills()) {
            SkillDef sd = skills().get(s);
            if (sd != null && sd.instructions() != null) sb.append("\n\n## Skill: ").append(sd.name()).append("\n").append(sd.instructions().trim());
        }
        String out = sb.toString();
        for (var e : vars.entrySet()) out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        return out;
    }

    /** Short hash of everything that shapes an agent's behaviour; stored on every run. */
    public String hash(AgentDef a) {
        try {
            String s = J.str(a) + snap.prompts().get(a.prompt()).system() + toolsFor(a).stream().map(t -> J.str(tool(t))).collect(Collectors.joining());
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (Exception e) { return "unknown"; }
    }

    /** Saves agents/<name>.yaml, reloads, and rolls back if validation fails. Returns errors (empty = saved). */
    public synchronized List<String> saveAgentYaml(String name, String text) {
        Path p = dir.resolve("agents").resolve(name + ".yaml");
        String old = null;
        try {
            AgentDef parsed = yaml.readValue(subst(text), AgentDef.class);
            if (parsed.name() == null || !parsed.name().equals(name)) return List.of("name in YAML must be '" + name + "'");
            if (Files.exists(p)) old = Files.readString(p);
            Files.createDirectories(p.getParent());
            Files.writeString(p, text);
        } catch (Exception e) { return List.of(String.valueOf(e.getMessage()).split("\n")[0]); }
        List<String> errs = reload();
        if (!errs.isEmpty()) {
            try { if (old == null) Files.deleteIfExists(p); else Files.writeString(p, old); } catch (IOException ignored) { }
            reload();
        }
        return errs;
    }

    /** Saves a file-backed module YAML, reloads and validates the full snapshot, and rolls back on error. */
    public synchronized List<String> saveModuleYaml(String module, String fileName, String text) {
        Map<String, String> dirs = Map.of("prompts", "prompts", "tools", "tools", "skills", "skills", "evals", "evals");
        String sub = dirs.get(module);
        if (sub == null) return List.of("module is not editable through YAML");
        if (fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || fileName.contains("..")) return List.of("invalid configuration filename");
        if (!fileName.endsWith(".yaml")) fileName += ".yaml";
        Path base = dir.resolve(sub).normalize();
        Path p = base.resolve(fileName).normalize();
        if (!p.startsWith(base)) return List.of("invalid configuration filename");
        String old = null;
        try {
            JsonNode parsed = yaml.readTree(subst(text));
            String expected = switch (module) { case "prompts" -> "name"; case "tools" -> "name"; case "skills" -> "name"; case "evals" -> "agent"; default -> ""; };
            String actual = parsed == null || parsed.get(expected) == null ? null : parsed.get(expected).asText();
            String expectedFile = "evals".equals(module) ? actual + ".yaml" : actual + ".yaml";
            if (actual == null || !expectedFile.equals(fileName)) return List.of("configuration identity in YAML must match filename '" + fileName + "'");
            if (Files.exists(p)) old = Files.readString(p);
            Files.createDirectories(p.getParent());
            Files.writeString(p, text);
        } catch (Exception e) { return List.of(String.valueOf(e.getMessage()).split("\\n")[0]); }
        List<String> errs = reload();
        if (!errs.isEmpty()) {
            try { if (old == null) Files.deleteIfExists(p); else Files.writeString(p, old); } catch (IOException ignored) { }
            reload();
        }
        return errs;
    }

    /** Saves singleton configuration files supported by this registry and rolls back invalid changes. */
    public synchronized List<String> saveSingletonYaml(String module, String text) {
        String relative = switch (module) {
            case "models" -> "models.yaml";
            case "guardrails" -> "guardrails.yaml";
            case "platform" -> "platform.yaml";
            case "retrieval_eval" -> "knowledge/retrieval_eval.yaml";
            default -> null;
        };
        if (relative == null) return List.of("unsupported configuration module");
        Path p = dir.resolve(relative).normalize();
        String old;
        try { yaml.readTree(subst(text)); old = Files.readString(p); Files.writeString(p, text); }
        catch (Exception e) { return List.of(String.valueOf(e.getMessage()).split("\\n")[0]); }
        List<String> errs = reload();
        if (!errs.isEmpty()) { try { Files.writeString(p, old); } catch (IOException ignored) { } reload(); }
        return errs;
    }

    public String singletonYaml(String module) {
        String relative = switch (module) { case "models" -> "models.yaml"; case "guardrails" -> "guardrails.yaml"; case "platform" -> "platform.yaml"; case "retrieval_eval" -> "knowledge/retrieval_eval.yaml"; default -> null; };
        if (relative == null) throw new IllegalArgumentException("unsupported configuration module");
        try { return Files.readString(dir.resolve(relative)); } catch (IOException e) { throw new IllegalArgumentException("configuration file not found"); }
    }

    public String moduleYaml(String module, String fileName) {
        String sub = switch (module) { case "prompts" -> "prompts"; case "tools" -> "tools"; case "skills" -> "skills"; case "evals" -> "evals"; default -> null; };
        if (sub == null || fileName == null || !fileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || fileName.contains("..")) throw new IllegalArgumentException("invalid configuration file");
        if (!fileName.endsWith(".yaml")) fileName += ".yaml";
        try { return Files.readString(dir.resolve(sub).resolve(fileName)); } catch (IOException e) { throw new IllegalArgumentException("configuration file not found"); }
    }

    public List<Map<String, Object>> evalConfigs() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path d = dir.resolve("evals");
        try (Stream<Path> st = Files.list(d)) {
            for (Path p : st.filter(x -> x.toString().endsWith(".yaml")).sorted().toList()) {
                Map<String, Object> row = new LinkedHashMap<>(); row.put("file", p.getFileName().toString()); row.put("yaml", Files.readString(p));
                EvalSet set = yaml.readValue(subst((String) row.get("yaml")), EvalSet.class); row.put("agent", set.agent()); row.put("cases", set.cases()); out.add(row);
            }
        } catch (Exception e) { throw new IllegalStateException("could not read evaluation config", e); }
        return out;
    }
}
