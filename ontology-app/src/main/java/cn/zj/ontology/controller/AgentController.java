package cn.zj.ontology.controller;

import cn.zj.ontology.OntologyProperties;
import cn.zj.ontology.agent.AgentService;
import cn.zj.ontology.runtime.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent 入口：自然语言进，工具调用轨迹 + 回答出。
 * 模型是 DeepSeek（OpenAI 兼容协议），工具来自本体，执行在我们这边。
 */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent", description = "DeepSeek + 本体工具：模型只负责选择和叙述，不负责计算")
public class AgentController {

    private final AgentService agent;
    private final OntologyProperties properties;
    private final ActorResolver actors;

    public AgentController(AgentService agent, OntologyProperties properties, ActorResolver actors) {
        this.agent = agent;
        this.properties = properties;
        this.actors = actors;
    }

    public record AskRequest(String question) {
    }

    @PostMapping("/ask")
    @Operation(summary = "问一个问题，Agent 通过本体工具回答",
            description = "返回回答与每一步工具调用；权限按 X-Actor-* 请求头生效")
    public AgentService.Answer ask(HttpServletRequest request, @RequestBody AskRequest body) {
        if (body == null || body.question() == null || body.question().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        Actor actor = actors.resolve(request);
        return agent.ask(actor, properties.domain(), body.question());
    }

    @PostMapping("/preview")
    @Operation(summary = "预览将发给模型的请求", description = "不消耗 token，用于检查工具定义与提示词")
    public Map<String, Object> preview(@RequestBody AskRequest body) {
        return agent.preview(properties.domain(),
                body == null || body.question() == null ? "" : body.question());
    }
}
