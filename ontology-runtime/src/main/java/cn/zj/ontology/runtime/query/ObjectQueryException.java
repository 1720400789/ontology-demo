package cn.zj.ontology.runtime.query;

/** 查询层的两类错误：找不到对象类型 / 参数不合法。Web 层据此返回 404 或 400。 */
public class ObjectQueryException extends RuntimeException {

    public enum Kind { NOT_FOUND, BAD_REQUEST }

    private final Kind kind;

    private ObjectQueryException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public static ObjectQueryException notFound(String message) {
        return new ObjectQueryException(Kind.NOT_FOUND, message);
    }

    public static ObjectQueryException badRequest(String message) {
        return new ObjectQueryException(Kind.BAD_REQUEST, message);
    }

    public Kind kind() {
        return kind;
    }
}
