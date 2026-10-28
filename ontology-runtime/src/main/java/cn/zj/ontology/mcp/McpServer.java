package cn.zj.ontology.mcp;

import cn.zj.ontology.runtime.security.Actor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 MCP 服务端（JSON-RPC 2.0 over HTTP POST）。
 *
 * <p>支持 initialize / tools/list / tools/call / ping。
 * 不支持 SSE 流式传输、resources、prompts、sampling；生产环境建议换 Spring AI 的 MCP starter。
 */
@Service
public class McpServer {

    public static final String PROTOCOL_VERSION = "2025-06-18";

    private final OntologyToolRegistry registry;
    private final ObjectMapper json = JsonMapper.builder().build();

    public McpServer(OntologyToolRegistry registry) {
        this.registry = registry;
    }

    /** 返回 null 表示这是通知，不需要响应体。 */
    public Map<String, Object> handle(Map<String, Object> request, Actor actor, String domain) {
        Object id = request.get("id");
        String method = String.valueOf(request.get("method"));
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> map
                ? castMap(map)
                : Map.of();

        try {
            Object result = dispatch(method, params, actor, domain);
            if (id == null) {
                return null;
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            return response;
        } catch (McpException e) {
            return error(id, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return error(id, -32603, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private Object dispatch(String method, Map<String, Object> params, Actor actor, String domain) {
        return switch (method) {
            case "initialize" -> Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "ontology-platform", "version", "0.1.0"));
            case "ping" -> Map.of();
            case "tools/list" -> Map.of("tools", toolDescriptors(domain));
            case "tools/call" -> callTool(params, actor, domain);
            default -> {
                if (method.startsWith("notifications/")) {
                    yield Map.of();
                }
                throw new McpException(-32601, "Method not found: " + method);
            }
        };
    }

    private List<Map<String, Object>> toolDescriptors(String domain) {
        return registry.tools(domain).stream()
                .map(tool -> {
                    Map<String, Object> descriptor = new LinkedHashMap<>();
                    descriptor.put("name", tool.name());
                    descriptor.put("description", tool.description());
                    descriptor.put("inputSchema", tool.inputSchema());
                    return descriptor;
                })
                .toList();
    }

    private Map<String, Object> callTool(Map<String, Object> params, Actor actor, String domain) {
        String name = String.valueOf(params.get("name"));
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> map
                ? castMap(map)
                : Map.of();

        ToolDefinition tool = registry.tools(domain).stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new McpException(-32602, "Unknown tool: " + name));

        try {
            Object result = tool.handler().call(arguments, actor);
            return content(result, false);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return content(message, true);
        }
    }

    private Map<String, Object> content(Object payload, boolean isError) {
        String text;
        try {
            text = payload instanceof String s ? s : json.writeValueAsString(payload);
        } catch (RuntimeException e) {
            text = String.valueOf(payload);
        }
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", text)),
                "isError", isError);
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
