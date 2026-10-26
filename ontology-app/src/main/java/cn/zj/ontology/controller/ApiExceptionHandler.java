package cn.zj.ontology.controller;

import cn.zj.ontology.core.OntologyValidationException;
import cn.zj.ontology.runtime.action.ActionException;
import cn.zj.ontology.runtime.function.FunctionException;
import cn.zj.ontology.runtime.query.ObjectQueryException;
import cn.zj.ontology.runtime.security.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** 把内部异常翻译成 RFC 9457 ProblemDetail，Agent 更容易按状态码分支处理。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ObjectQueryException.class)
    public ProblemDetail handleQuery(ObjectQueryException e) {
        HttpStatus status = e.kind() == ObjectQueryException.Kind.NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return problem(status, status == HttpStatus.NOT_FOUND ? "对象不存在" : "查询参数不合法", e.getMessage());
    }

    @ExceptionHandler(FunctionException.class)
    public ProblemDetail handleFunction(FunctionException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case NOT_IMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("函数执行失败", e);
        }
        String title = switch (e.kind()) {
            case NOT_FOUND -> "函数不存在";
            case BAD_REQUEST -> "函数参数不合法";
            case TIMEOUT -> "函数执行超时";
            case NOT_IMPLEMENTED -> "函数尚未实现";
            case INTERNAL -> "函数执行失败";
        };
        return problem(status, title, e.getMessage());
    }

    @ExceptionHandler(ActionException.class)
    public ProblemDetail handleAction(ActionException e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_IMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("动作执行失败", e);
        }
        String title = switch (e.kind()) {
            case NOT_FOUND -> "动作不存在";
            case BAD_REQUEST -> "动作参数不合法";
            case CONFLICT -> "动作状态冲突";
            case NOT_IMPLEMENTED -> "动作尚未实现";
            case INTERNAL -> "动作执行失败";
        };
        return problem(status, title, e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail handleForbidden(ForbiddenException e) {
        return problem(HttpStatus.FORBIDDEN, "没有权限", e.getMessage());
    }

    @ExceptionHandler(OntologyValidationException.class)
    public ProblemDetail handleOntology(OntologyValidationException e) {
        log.warn("本体校验失败", e);
        return problem(HttpStatus.BAD_REQUEST, "本体定义不合法", e.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("about:blank"));
        return problem;
    }
}
