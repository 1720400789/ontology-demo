package cn.zj.ontology;

import cn.zj.ontology.core.OntologyParser;
import cn.zj.ontology.core.model.OntologyDefinition;
import cn.zj.ontology.runtime.loader.OntologyLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/** 启动时把 YAML 灌进元模型。定义有问题就直接启动失败，避免"带病运行"。 */
@Component
public class OntologyLoadRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OntologyLoadRunner.class);

    private final OntologyProperties properties;
    private final OntologyLoader loader;
    private final OntologyParser parser;
    private final ResourceLoader resourceLoader;

    public OntologyLoadRunner(OntologyProperties properties,
                              OntologyLoader loader,
                              OntologyParser parser,
                              ResourceLoader resourceLoader) {
        this.properties = properties;
        this.loader = loader;
        this.parser = parser;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.loader().enabled()) {
            log.info("ontology.loader.enabled=false，跳过本体加载");
            return;
        }
        String location = properties.loader().location();
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("找不到本体文件: " + location);
        }
        OntologyDefinition definition;
        try (InputStream in = resource.getInputStream()) {
            definition = parser.parse(in);
        }
        var result = loader.load(definition);
        if (result.skipped()) {
            log.info("本体 {} 已是最新（{}），无需写入", result.domain(), location);
        } else {
            log.info("本体 {} 加载完成：对象 {}、新版本 {}、关系 {}、函数 {}、动作 {}、权限 {}、发布 {}",
                    result.domain(), result.objectTypes(), result.newVersions(), result.relations(),
                    result.functions(), result.actions(), result.permissions(), result.releaseVersion());
        }
    }
}
