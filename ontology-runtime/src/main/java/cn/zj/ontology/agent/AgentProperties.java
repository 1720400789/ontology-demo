package cn.zj.ontology.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** DeepSeek（OpenAI 兼容协议）接入配置。密钥只从环境变量来，不写进仓库。 */
@ConfigurationProperties(prefix = "ontology.agent")
public record AgentProperties(
        boolean enabled,
        String baseUrl,
        String model,
        String apiKey,
        int maxSteps,
        int timeoutSeconds) {

    public AgentProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.strip();
        model = model == null || model.isBlank() ? "deepseek-chat" : model;
        maxSteps = maxSteps <= 0 ? 6 : maxSteps;
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String chatCompletionsUrl() {
        return baseUrl.replaceAll("/+$", "") + "/chat/completions";
    }
}
