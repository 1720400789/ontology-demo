package cn.zj.ontology.core;

import cn.zj.ontology.core.model.OntologyDefinition;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** YAML → 内存模型。解析不做业务校验，校验交给 {@link OntologyValidator}。 */
public final class OntologyParser {

    private final ObjectMapper yaml = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public OntologyDefinition parse(InputStream in) {
        return yaml.readValue(in, OntologyDefinition.class);
    }

    public OntologyDefinition parse(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("读取本体文件失败: " + path, e);
        }
    }
}
