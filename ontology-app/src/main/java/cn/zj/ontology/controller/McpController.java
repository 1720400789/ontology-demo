package cn.zj.ontology.controller;

import cn.zj.ontology.OntologyProperties;
import cn.zj.ontology.mcp.McpServer;
import cn.zj.ontology.mcp.OntologyToolRegistry;
import cn.zj.ontology.mcp.ToolDefinition;
import cn.zj.ontology.runtime.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 入口。Agent 通过 JSON-RPC 拿工具、调工具；身份仍走请求头。
 * 另提供一个人类可读的工具清单，方便排查 Agent 到底能做什么。
 */
@RestController
@Tag(name = "MCP", description = "把元模型编译成 MCP 工具，Agent 无需硬编码接口")
public class McpController {

    private final McpServer server;
    private final OntologyToolRegistry registry;
    private final OntologyProperties properties;
    private final ActorResolver actors;

    public McpController(McpServer server, OntologyToolRegistry registry,
                         OntologyProperties properties, ActorResolver actors) {
        this.server = server;
        this.registry = registry;
        this.properties = properties;
        this.actors = actors;
    }

    @PostMapping("/mcp")
    @Operation(summary = "MCP JSON-RPC 端点", description = "支持 initialize / tools/list / tools/call / ping")
    public ResponseEntity<Object> mcp(@RequestBody Map<String, Object> request, HttpServletRequest http) {
        Actor actor = actors.resolve(http);
        Map<String, Object> response = server.handle(request, actor, properties.domain());
        return response == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(response);
    }

    @GetMapping("/api/tools")
    @Operation(summary = "工具清单（人类可读）")
    public List<Map<String, Object>> tools() {
        return registry.tools(properties.domain()).stream()
                .map(tool -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("name", tool.name());
                    view.put("description", tool.description());
                    view.put("inputSchema", tool.inputSchema());
                    return view;
                })
                .toList();
    }
}
