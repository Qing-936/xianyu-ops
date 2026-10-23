package io.github.qing936.xianyuops.module.xianyu.service.source.refine;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceImageRefineErrorCodeConstants.SOURCE_IMAGE_REFINE_FAILED;
import static io.github.qing936.xianyuops.module.xianyu.enums.SourceImageRefineErrorCodeConstants.SOURCE_IMAGE_REFINE_RESULT_INVALID;

/**
 * 货源图片微调 Dify 工作流客户端
 * <p>
 * 入参：html（货源清洗后的 HTML）、user_prompt（自然语言微调指令）。
 * 出参：工作流内部渲染好的图片地址（Dify 临时文件 /files/tools/xxx.bin，带签名参数）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceImageRefineDifyClient {

    /** 结束节点输出变量候选名，命中第一个图片地址即返回 */
    private static final String[] OUTPUT_KEYS = {"result", "image_url", "url", "output"};

    private final SourceImageRefineProperties properties;

    public RefineResult refine(String html, String userPrompt, String user, String traceId) {
        String runUrl = properties.getBaseUrl().replaceAll("/+$", "") + "/v1/workflows/run";
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("html", html);
        inputs.put("user_prompt", userPrompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inputs);
        body.put("response_mode", "blocking");
        body.put("user", user);

        log.info("[SourceImageRefine] 请求 Dify traceId={}, url={}", traceId, runUrl);
        String response;
        try {
            response = restClient().post().uri(runUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .header("X-Trace-Id", traceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(String.class);
        } catch (Exception e) {
            log.warn("[SourceImageRefine] Dify 调用失败 traceId={}, causeType={}", traceId, e.getClass().getSimpleName());
            throw exception(SOURCE_IMAGE_REFINE_FAILED);
        }
        return extractResult(response, traceId);
    }

    private RefineResult extractResult(String response, String traceId) {
        JsonNode data;
        try {
            data = JsonUtils.parseTree(response).path("data");
        } catch (Exception e) {
            log.error("[SourceImageRefine] 响应不是有效 JSON traceId={}", traceId);
            throw exception(SOURCE_IMAGE_REFINE_FAILED);
        }
        String status = data.path("status").asText();
        if (!"succeeded".equals(status)) {
            log.error("[SourceImageRefine] 工作流未成功 traceId={}, status={}, error={}",
                    traceId, status, data.path("error").asText());
            throw exception(SOURCE_IMAGE_REFINE_FAILED);
        }
        JsonNode outputs = data.path("outputs");
        String imageUrl = null;
        if (outputs.isTextual() && isImageUrl(outputs.asText())) {
            imageUrl = outputs.asText().trim();
        } else {
            for (String key : OUTPUT_KEYS) {
                JsonNode value = outputs.path(key);
                if (value.isTextual() && isImageUrl(value.asText())) {
                    imageUrl = value.asText().trim();
                    break;
                }
            }
        }
        if (imageUrl == null) {
            log.error("[SourceImageRefine] 未取到图片地址 traceId={}", traceId);
            throw exception(SOURCE_IMAGE_REFINE_RESULT_INVALID);
        }
        String newHtml = outputs.path("new_html").asText(null);
        if (newHtml != null && newHtml.isBlank()) {
            newHtml = null;
        }
        return new RefineResult(imageUrl, newHtml);
    }

    /** 微调工作流出参：渲染后图片地址 + 微调后的 HTML 源码 */
    public record RefineResult(String imageUrl, String newHtml) {}

    private boolean isImageUrl(String value) {
        String text = value == null ? "" : value.trim();
        return text.startsWith("http://") || text.startsWith("https://");
    }

    private RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
