package cn.zj.ontology.controller;

import cn.zj.ontology.core.OntologyValidationException;
import cn.zj.ontology.runtime.query.ObjectQueryException;
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
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        detail.setTitle(status == HttpStatus.NOT_FOUND ? "对象不存在" : "查询参数不合法");
        detail.setType(URI.create("about:blank"));
        return detail;
    }

    @ExceptionHandler(OntologyValidationException.class)
    public ProblemDetail handleOntology(OntologyValidationException e) {
        log.warn("本体校验失败", e);
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        detail.setTitle("本体定义不合法");
        return detail;
    }
}
