package cn.zj.ontology.agent;

/** Agent 调用失败，status 直接映射到 HTTP 状态码。 */
public class AgentException extends RuntimeException {

    private final int status;

    public AgentException(int status, String message) {
        super(message);
        this.status = status;
    }

    public AgentException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
