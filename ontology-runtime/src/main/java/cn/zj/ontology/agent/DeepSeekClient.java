package cn.zj.ontology.agent;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 的 OpenAI 兼容客户端。只做一件事：把 messages + tools 发过去，拿回一条 assistant 消息。
 * 重试策略交给上层（AgentService），这里失败即抛。
 */
@Component
public class DeepSeekClient {

    private final AgentProperties properties;
    private final HttpClient http;
    private final ObjectMapper json = JsonMapper.builder().build();

    public DeepSeekClient(AgentProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools) {
        if (!properties.configured()) {
            throw new AgentException(503,
                    "未配置 DEEPSEEK_API_KEY，无法调用模型。设置环境变量后重启即可。");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", messages);
        body.put("tools", tools);
        body.put("tool_choice", "auto");
        body.put("temperature", 0);

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.chatCompletionsUrl()))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 300) {
                throw new AgentException(502,
                        "DeepSeek 返回 " + response.statusCode() + "：" + truncate(response.body(), 500));
            }
            return json.readValue(response.body(), Map.class);
        } catch (AgentException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException(504, "调用 DeepSeek 被中断", e);
        } catch (Exception e) {
            throw new AgentException(504, "调用 DeepSeek 失败：" + e.getMessage(), e);
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
