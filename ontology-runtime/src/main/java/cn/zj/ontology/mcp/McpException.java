package cn.zj.ontology.mcp;

/** JSON-RPC 错误，带标准错误码。 */
public class McpException extends RuntimeException {

    private final int code;

    public McpException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
