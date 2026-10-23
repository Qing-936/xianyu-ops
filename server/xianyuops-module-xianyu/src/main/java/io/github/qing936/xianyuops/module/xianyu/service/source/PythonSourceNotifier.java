package io.github.qing936.xianyuops.module.xianyu.service.source;

import io.github.qing936.xianyuops.framework.common.util.monitor.TracerUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCookieRefreshRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCookieRefreshStatusRespVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python 采集任务通知组件
 * <p>
 * 向 Python 采集服务发送 HTTP POST 请求，触发任务执行。
 * 接口文档：POST /api/source/execute，Body: {"task_id": <id>}
 * <p>
 * 调用失败时抛出 RuntimeException，由调用方（submitToPython）捕获并写入任务表 error_message。
 */
@Slf4j
@Component
public class PythonSourceNotifier {

    private static final Duration INVENTORY_READ_TIMEOUT = Duration.ofSeconds(150);

    private final SourceProperties sourceProperties;
    private final RestTemplateBuilder restTemplateBuilder;
    private final ObjectMapper objectMapper;

    private RestTemplate restTemplate;
    private RestTemplate inventoryRestTemplate;

    public PythonSourceNotifier(SourceProperties sourceProperties, RestTemplateBuilder restTemplateBuilder,
                                ObjectMapper objectMapper) {
        this.sourceProperties = sourceProperties;
        this.restTemplateBuilder = restTemplateBuilder;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initRestTemplate() {
        Duration timeout = Duration.ofSeconds(sourceProperties.getTimeoutSeconds());
        restTemplate = createRestTemplate(timeout, timeout);
        inventoryRestTemplate = createRestTemplate(timeout, INVENTORY_READ_TIMEOUT);
    }

    private RestTemplate createRestTemplate(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return restTemplateBuilder.requestFactory(() -> requestFactory).build();
    }

    /**
     * 通知 Python 将采集详情写入货源库。
     */
    public void addInventory(Long detailId, String inventoryUser, Long tenantId) {
        String url = sourceProperties.getBaseUrl() + "/api/source/inventory/add";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail_id", detailId);
        body.put("inventory_user", inventoryUser);
        body.put("tenant_id", tenantId);
        postForSuccess(inventoryRestTemplate, url, body, "入库", "Python 服务处理失败");
    }

    /** 将原始 HTML 发送给本机 Python Playwright 服务并返回 PNG。 */
    public byte[] renderHtml(byte[] html) {
        String url = sourceProperties.getBaseUrl() + "/api/render-html";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/html; charset=UTF-8"));
        headers.setAccept(List.of(MediaType.IMAGE_PNG));
        headers.set("X-Trace-Id", TracerUtils.getTraceId());
        ResponseEntity<byte[]> response = restTemplate.postForEntity(url, new HttpEntity<>(html, headers), byte[].class);
        if (response.getBody() == null) {
            throw new RuntimeException("Python HTML 渲染服务未返回图片");
        }
        return response.getBody();
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-Id", TracerUtils.getTraceId());
        return headers;
    }

    private void postForSuccess(String url, Map<String, Object> body, String action, String failurePrefix) {
        postForSuccess(restTemplate, url, body, action, failurePrefix);
    }

    private void postForSuccess(RestTemplate client, String url, Map<String, Object> body,
                                String action, String failurePrefix) {
        postForData(client, url, body, action, failurePrefix);
    }

    private Map<String, Object> postForData(String url, Map<String, Object> body, String action, String failurePrefix) {
        return postForData(restTemplate, url, body, action, failurePrefix);
    }

    private Map<String, Object> postForData(RestTemplate client, String url, Map<String, Object> body,
                                            String action, String failurePrefix) {
        HttpHeaders headers = createHeaders();
        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            log.info("[postForSuccess][{}] 请求 Python 服务: url={}, body={}", action, url, bodyJson);
            ResponseEntity<String> response = client.postForEntity(url,
                    new HttpEntity<>(bodyJson, headers), String.class);
            String responseBody = response.getBody();
            log.info("[postForSuccess][{}] Python 响应: status={}, body={}", action, response.getStatusCode(), responseBody);
            return parseResponseData(responseBody, failurePrefix);
        } catch (ResourceAccessException e) {
            log.error("[postForSuccess][{}] Python 服务不可达", action, e);
            throw new RuntimeException("Python 采集服务不可达: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[postForSuccess][{}] 调用 Python 服务异常", action, e);
            throw new RuntimeException("调用 Python 采集服务异常: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> getForData(String url, String action, String failurePrefix) {
        try {
            log.info("[getForData][{}] 请求 Python 服务: url={}", action, url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(createHeaders()), String.class);
            String responseBody = response.getBody();
            log.info("[getForData][{}] Python 响应: status={}, body={}", action, response.getStatusCode(), responseBody);
            return parseResponseData(responseBody, failurePrefix);
        } catch (ResourceAccessException e) {
            log.error("[getForData][{}] Python 服务不可达", action, e);
            throw new RuntimeException("Python 采集服务不可达: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[getForData][{}] 调用 Python 服务异常", action, e);
            throw new RuntimeException("调用 Python 采集服务异常: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> parseResponseData(String responseBody, String failurePrefix) throws Exception {
        if (responseBody == null) {
            throw new RuntimeException("Python 服务未返回响应内容");
        }
        Map<String, Object> result = objectMapper.readValue(responseBody,
                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        if (!Boolean.TRUE.equals(result.get("success"))) {
            Object reason = result.get("message") != null ? result.get("message") : result.get("error");
            throw new RuntimeException(failurePrefix + ": "
                    + String.valueOf(reason != null ? reason : "Python 服务处理失败"));
        }
        Object data = result.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) dataMap;
            return casted;
        }
        return Map.of();
    }

    /**
     * 通知 Python 将链接解析并直接写入货源库。
     */
    public void manualImport(String detailUrl, String inventoryUser, Long tenantId) {
        String url = sourceProperties.getBaseUrl() + "/api/source/inventory/manual-import";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail_url", detailUrl);
        body.put("inventory_user", inventoryUser);
        body.put("tenant_id", tenantId);
        postForSuccess(url, body, "人工入库", "Python 服务处理失败");
    }

    public void refreshInventory(Long inventoryId, String inventoryUser, Long tenantId) {
        String url = sourceProperties.getBaseUrl() + "/api/source/inventory/refresh";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inventory_id", inventoryId);
        body.put("inventory_user", inventoryUser);
        body.put("tenant_id", tenantId);
        postForSuccess(url, body, "刷新货源", "Python 服务处理失败");
    }

     /*
     * @param taskId 任务 ID，对应 app_source_task.id
     * @throws RuntimeException 调用失败时抛出，包含失败原因
     */
    public boolean notifyTaskCreated(Long taskId, boolean switchCookie) {
        String url = sourceProperties.getBaseUrl() + "/api/source/execute";

        // Python 接口使用 snake_case；具体任务配置由 Python 根据 task_id 从数据库读取
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        body.put("switch_cookie", switchCookie);
        Map<String, Object> data = postForData(url, body, "执行任务", "Python 服务拒绝执行任务");
        boolean accepted = !Boolean.FALSE.equals(data.get("accepted"));
        if (!accepted) {
            log.info("[notifyTaskCreated][taskId={} Python 忽略重复重试, activeTaskId={}]",
                    taskId, data.get("active_task_id"));
        }
        return accepted;
    }

    public void deleteTask(Long taskId, Long tenantId) {
        String url = sourceProperties.getBaseUrl() + "/api/source/task/delete";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("task_id", taskId);
        body.put("tenant_id", tenantId);
        postForSuccess(url, body, "删除采集任务", "采集任务删除失败");
    }

    public SourceCookieRefreshRespVO refreshCookieQr() {
        String url = sourceProperties.getBaseUrl() + "/api/source/cookie/refresh";
        Map<String, Object> data = postForData(url, Map.of(), "刷新 Cookie 二维码", "Python 服务生成二维码失败");
        return objectMapper.convertValue(data, SourceCookieRefreshRespVO.class);
    }

    public SourceCookieRefreshStatusRespVO getCookieRefreshStatus(String sessionId) {
        String url = sourceProperties.getBaseUrl() + "/api/source/cookie/refresh/" + sessionId;
        Map<String, Object> data = getForData(url, "刷新 Cookie 状态", "Python 服务查询扫码状态失败");
        return objectMapper.convertValue(data, SourceCookieRefreshStatusRespVO.class);
    }
}
