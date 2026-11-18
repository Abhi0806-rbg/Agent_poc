package com.intutr.agents.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Map;

/** Small JSON helpers that throw unchecked exceptions. */
public final class J {
    public static final ObjectMapper OM = new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private J() {}
    public static String str(Object o) { try { return OM.writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); } }
    public static JsonNode tree(String s) { try { return OM.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }
    public static <T> T read(String s, Class<T> c) { try { return OM.readValue(s, c); } catch (Exception e) { throw new RuntimeException(e); } }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(JsonNode n) { return OM.convertValue(n, Map.class); }
    public static String clip(String s, int n) { return s == null ? null : (s.length() <= n ? s : s.substring(0, n) + "…"); }
}
