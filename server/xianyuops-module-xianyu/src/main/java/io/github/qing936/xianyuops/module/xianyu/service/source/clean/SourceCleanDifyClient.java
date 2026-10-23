package io.github.qing936.xianyuops.module.xianyu.service.source.clean;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SourceCleanDifyClient {
    private static final int MAX_START_ATTEMPTS = 2;
    private static final long RECOVERY_POLL_MILLIS = 1000L;

    private final SourceCleanProperties properties;

    public ObjectNode run(JsonNode inputs, String user, String traceId) {
        return run(inputs, user, traceId, properties.getApiKey(),
                Duration.ofSeconds(properties.getTimeoutSeconds()));
    }

    public ObjectNode runRecommendation(JsonNode inputs, String user, String traceId) {
        return run(inputs, user, traceId, properties.getRecommendationApiKey(), Duration.ZERO);
    }

    private ObjectNode run(JsonNode inputs, String user, String traceId, String apiKey, Duration timeout) {
        RestClient client = restClient(timeout);
        String baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
        String runUrl = baseUrl + "/v1/workflows/run";
        StreamProgress progress = new StreamProgress();
        for (int attempt = 1; attempt <= MAX_START_ATTEMPTS; attempt++) {
            try {
                log.info("[SourceCleanDify] 请求 Dify traceId={}, attempt={}, url={}", traceId, attempt, runUrl);
                JsonNode completed = executeStreaming(client, runUrl, inputs, user, traceId, apiKey, progress);
                return result(completed, progress.workflowRunId, traceId);
            } catch (SourceCleanFailure e) {
                throw e;
            } catch (Exception e) {
                if (progress.workflowRunId != null) {
                    log.warn("[SourceCleanDify] 流连接中断，按运行 ID 恢复 traceId={}, workflowRunId={}, causeType={}",
                            traceId, progress.workflowRunId, rootCause(e).getClass().getSimpleName());
                    JsonNode completed = awaitCompletion(client, baseUrl, progress.workflowRunId,
                            apiKey, timeout, traceId);
                    return result(completed, progress.workflowRunId, traceId);
                }
                if (attempt < MAX_START_ATTEMPTS) {
                    log.warn("[SourceCleanDify] 工作流启动前连接中断，重试一次 traceId={}, causeType={}",
                            traceId, rootCause(e).getClass().getSimpleName());
                    continue;
                }
                log.warn("[SourceCleanDify] 请求异常 traceId={}, errorType={}, causeType={}", traceId,
                        e.getClass().getSimpleName(), rootCause(e).getClass().getSimpleName());
                throw new SourceCleanFailure("Dify 调用失败或超时，请按任务追踪号检查工作流日志");
            }
        }
        throw new SourceCleanFailure("Dify 调用失败或超时，请按任务追踪号检查工作流日志");
    }

    private JsonNode executeStreaming(RestClient client, String url, JsonNode inputs, String user,
                                      String traceId, String apiKey, StreamProgress progress) {
        return client.post().uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(Map.of("inputs", inputs, "response_mode", "streaming", "user", user))
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        log.warn("[SourceCleanDify] HTTP 请求失败 traceId={}, status={}",
                                traceId, response.getStatusCode().value());
                        throw new SourceCleanFailure("Dify 调用失败或超时，请按任务追踪号检查工作流日志");
                    }
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            JsonNode event = JsonUtils.parseTree(line.substring(5).trim());
                            String workflowRunId = event.path("workflow_run_id").asText();
                            if (!workflowRunId.isBlank()) {
                                progress.workflowRunId = workflowRunId;
                            }
                            if ("workflow_finished".equals(event.path("event").asText())) {
                                return event.path("data");
                            }
                        }
                    }
                    throw new EOFException("Dify SSE ended before workflow_finished");
                });
    }

    private JsonNode awaitCompletion(RestClient client, String baseUrl, String workflowRunId,
                                     String apiKey, Duration timeout, String traceId) {
        long deadline = timeout.isZero() ? 0 : System.nanoTime() + timeout.toNanos();
        String url = baseUrl + "/v1/workflows/run/" + workflowRunId;
        while (timeout.isZero() || System.nanoTime() < deadline) {
            try {
                String body = client.get().uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve().body(String.class);
                JsonNode run = JsonUtils.parseTree(body);
                String status = run.path("status").asText();
                if ("succeeded".equals(status) || "failed".equals(status) || "stopped".equals(status)) {
                    log.info("[SourceCleanDify] 已恢复工作流结果 traceId={}, workflowRunId={}, status={}",
                            traceId, workflowRunId, status);
                    return run;
                }
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != 404 && !e.getStatusCode().is5xxServerError()) {
                    log.warn("[SourceCleanDify] 查询运行结果失败 traceId={}, workflowRunId={}, status={}",
                            traceId, workflowRunId, e.getStatusCode().value());
                    throw new SourceCleanFailure("Dify 运行结果查询失败");
                }
            } catch (RestClientException e) {
                log.warn("[SourceCleanDify] 查询运行结果连接异常 traceId={}, workflowRunId={}, causeType={}",
                        traceId, workflowRunId, rootCause(e).getClass().getSimpleName());
            }
            sleepBeforePoll();
        }
        throw new SourceCleanFailure("Dify 工作流结果恢复超时");
    }

    private ObjectNode result(JsonNode completed, String workflowRunId, String traceId) {
        String status = completed.path("status").asText();
        if (!"succeeded".equals(status)) {
            log.error("[SourceCleanDify] 工作流未成功 traceId={}, workflowRunId={}, status={}, error={}",
                    traceId, workflowRunId, status, completed.path("error").asText());
            throw new SourceCleanFailure("Dify 工作流未成功完成：" + completed.path("error").asText());
        }
        try {
            JsonNode outputs = completed.path("outputs");
            if (outputs.isTextual()) {
                outputs = JsonUtils.parseTree(outputs.asText());
            }
            JsonNode value = outputs.path("result");
            if (value.isTextual()) {
                value = JsonUtils.parseTree(value.asText());
            }
            if (!(value instanceof ObjectNode output)) {
                log.error("[SourceCleanDify] 缺少 result 对象 traceId={}, workflowRunId={}", traceId, workflowRunId);
                throw new SourceCleanFailure("Dify 缺少 result 对象");
            }
            JsonNode html = outputs.path("html");
            if (html.isTextual() && !html.asText().isBlank()) {
                output.put("cleaned_html", html.asText());
            }
            output.put("workflow_run_id", workflowRunId == null ? completed.path("id").asText() : workflowRunId);
            log.info("[SourceCleanDify] 收到成功结果 traceId={}, workflowRunId={}", traceId,
                    output.path("workflow_run_id").asText());
            return output;
        } catch (SourceCleanFailure e) {
            throw e;
        } catch (Exception e) {
            log.error("[SourceCleanDify] 解析响应失败 traceId={}, workflowRunId={}, errorType={}",
                    traceId, workflowRunId, e.getClass().getSimpleName());
            throw new SourceCleanFailure("Dify 响应不是有效的工作流 JSON");
        }
    }

    private RestClient restClient(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    private void sleepBeforePoll() {
        try {
            Thread.sleep(RECOVERY_POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceCleanFailure("Dify 工作流结果恢复被中断");
        }
    }

    private Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static final class StreamProgress {
        private String workflowRunId;
    }
}
