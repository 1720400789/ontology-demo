package cn.zj.ontology.core;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 规范化 JSON 与内容哈希。哈希用于判断"定义有没有变"，变了才发新版本。 */
public final class CanonicalJson {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private CanonicalJson() {
    }

    public static String of(Object value) {
        return JSON.writeValueAsString(value);
    }

    public static String sha256(Object value) {
        return sha256(of(value));
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
