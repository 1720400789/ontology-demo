package cn.zj.ontology.agent;

import cn.zj.ontology.mcp.OntologyToolRegistry;
import cn.zj.ontology.mcp.ToolDefinition;
import cn.zj.ontology.runtime.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 循环：模型只能通过本体生成的工具访问业务数据。
 *
 * <p>这是「让模型别猜」的落点：工具来自元模型，数值来自函数，写操作只能提议。
 * 循环最多 {@code maxSteps} 轮，避免模型在工具间打转。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private static final int MAX_TOOL_RESULT_CHARS = 4000;

    private static final String SYSTEM_PROMPT = """
            你是企业设备维保助手。必须遵守以下规则：
            1. 只能使用提供的工具获取业务数据，不得凭记忆或推测给出数字、日期、对象 id。
            2. 需要数据时先调用工具；工具返回空、报错或超时，如实说明，不得编造。
            3. 超出本体词汇的问题（例如预测未来故障、评价员工绩效），明确说「当前本体没有这个能力」，
               并说明缺少什么函数或数据。
            4. 写操作只能调用 action_* 工具「提议」，不要声称已经执行；执行需要人工或主管审批。
            5. 回答用中文，给出依据：对象 id、调用的函数名、关键返回值。
            6. 不确定时追问，不要猜。
            """;

    private final OntologyToolRegistry registry;
    private final DeepSeekClient client;
    private final AgentProperties properties;
    private final ObjectMapper json = JsonMapper.builder().build();

    public AgentService(OntologyToolRegistry registry, DeepSeekClient client,
                        AgentProperties properties) {
        this.registry = registry;
        this.client = client;
        this.properties = properties;
    }

    public Answer ask(Actor actor, String domain, String question) {
        if (!properties.enabled()) {
            throw new AgentException(503, "Agent 未启用（ontology.agent.enabled=false）");
        }
        List<ToolDefinition> tools = registry.tools(domain);
        Map<String, ToolDefinition> byName = tools.stream()
                .collect(Collectors.toMap(ToolDefinition::name, Function.identity()));

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", question));

        List<Step> steps = new ArrayList<>();
        String answer = null;

        for (int round = 1; round <= properties.maxSteps(); round++) {
            Map<String, Object> response = client.chat(messages, toOpenAiTools(tools));
            Map<String, Object> message = firstMessage(response);
            List<?> toolCalls = message.get("tool_calls") instanceof List<?> list ? list : List.of();

            if (toolCalls.isEmpty()) {
                Object content = message.get("content");
                answer = content == null ? "模型未返回内容。" : String.valueOf(content);
                break;
            }

            messages.add(message);

            for (Object raw : toolCalls) {
                if (!(raw instanceof Map<?, ?> call)) {
                    continue;
                }
                String callId = String.valueOf(call.get("id"));
                Map<?, ?> function = call.get("function") instanceof Map<?, ?> f ? f : Map.of();
                String name = String.valueOf(function.get("name"));
                String argumentsJson = function.get("arguments") == null
                        ? "{}"
                        : String.valueOf(function.get("arguments"));

                Map<String, Object> arguments = parseArguments(argumentsJson);
                ToolOutcome outcome = invoke(byName.get(name), name, arguments, actor);
                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", callId,
                        "content", outcome.content()));
                steps.add(new Step(round, name, argumentsJson, outcome.error(),
                        truncate(outcome.content(), 300)));
                log.debug("Agent 调用工具 {}（round {}），error={}", name, round, outcome.error());
            }
        }

        if (answer == null) {
            answer = "已达到最大工具调用轮数（" + properties.maxSteps() + "），未能得出结论。";
        }
        return new Answer(answer, steps, properties.model());
    }

    /** 不发请求，只返回将要发给模型的 payload，用于排查工具定义与提示词。 */
    public Map<String, Object> preview(String domain, String question) {
        List<ToolDefinition> tools = registry.tools(domain);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.model());
        payload.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", question)));
        payload.put("tools", toOpenAiTools(tools));
        payload.put("tool_choice", "auto");
        payload.put("temperature", 0);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("configured", properties.configured());
        view.put("model", properties.model());
        view.put("toolCount", tools.size());
        view.put("toolNames", tools.stream().map(ToolDefinition::name).toList());
        view.put("payload", payload);
        return view;
    }

    private ToolOutcome invoke(ToolDefinition tool, String name,
                               Map<String, Object> arguments, Actor actor) {
        if (tool == null) {
            return new ToolOutcome("未知工具：" + name, true);
        }
        try {
            Object result = tool.handler().call(arguments, actor);
            String content = json.writeValueAsString(result);
            return new ToolOutcome(truncate(content, MAX_TOOL_RESULT_CHARS), false);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ToolOutcome("工具执行失败：" + message, true);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstMessage(Map<String, Object> response) {
        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw new AgentException(502, "模型响应缺少 choices：" + truncate(String.valueOf(response), 300));
        }
        Object message = ((Map<String, Object>) list.get(0)).get("message");
        if (!(message instanceof Map<?, ?> map)) {
            throw new AgentException(502, "模型响应缺少 message");
        }
        return (Map<String, Object>) map;
    }

    private List<Map<String, Object>> toOpenAiTools(List<ToolDefinition> tools) {
        return tools.stream()
                .map(tool -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", tool.inputSchema())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(argumentsJson, Map.class);
        } catch (RuntimeException e) {
            throw new AgentException(502, "模型给出的工具参数不是合法 JSON：" + truncate(argumentsJson, 200));
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private record ToolOutcome(String content, boolean error) {
    }

    public record Step(int round, String tool, String arguments, boolean error, String resultPreview) {
    }

    public record Answer(String answer, List<Step> steps, String model) {
    }
}
