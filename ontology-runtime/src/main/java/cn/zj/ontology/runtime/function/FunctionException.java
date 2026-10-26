package cn.zj.ontology.runtime.function;

/** 函数调用的四类失败：找不到、参数不对、超时、实现缺失。 */
public class FunctionException extends RuntimeException {

    public enum Kind { NOT_FOUND, BAD_REQUEST, TIMEOUT, NOT_IMPLEMENTED, INTERNAL }

    private final Kind kind;

    private FunctionException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static FunctionException notFound(String message) {
        return new FunctionException(Kind.NOT_FOUND, message, null);
    }

    public static FunctionException badRequest(String message) {
        return new FunctionException(Kind.BAD_REQUEST, message, null);
    }

    public static FunctionException timeout(String message) {
        return new FunctionException(Kind.TIMEOUT, message, null);
    }

    public static FunctionException notImplemented(String message) {
        return new FunctionException(Kind.NOT_IMPLEMENTED, message, null);
    }

    public static FunctionException internal(String message, Throwable cause) {
        return new FunctionException(Kind.INTERNAL, message, cause);
    }

    public Kind kind() {
        return kind;
    }
}
