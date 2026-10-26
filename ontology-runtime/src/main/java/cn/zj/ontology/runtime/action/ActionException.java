package cn.zj.ontology.runtime.action;

/** 动作链路的失败类型，Web 层按 kind 映射状态码。 */
public class ActionException extends RuntimeException {

    public enum Kind { NOT_FOUND, BAD_REQUEST, CONFLICT, NOT_IMPLEMENTED, INTERNAL }

    private final Kind kind;

    private ActionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static ActionException notFound(String message) {
        return new ActionException(Kind.NOT_FOUND, message, null);
    }

    public static ActionException badRequest(String message) {
        return new ActionException(Kind.BAD_REQUEST, message, null);
    }

    public static ActionException conflict(String message) {
        return new ActionException(Kind.CONFLICT, message, null);
    }

    public static ActionException notImplemented(String message) {
        return new ActionException(Kind.NOT_IMPLEMENTED, message, null);
    }

    public static ActionException internal(String message, Throwable cause) {
        return new ActionException(Kind.INTERNAL, message, cause);
    }

    public Kind kind() {
        return kind;
    }
}
