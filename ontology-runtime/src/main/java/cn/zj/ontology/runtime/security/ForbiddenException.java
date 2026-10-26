package cn.zj.ontology.runtime.security;

/** 权限拒绝。Web 层翻译成 403，且不泄露"对象是否存在"。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
