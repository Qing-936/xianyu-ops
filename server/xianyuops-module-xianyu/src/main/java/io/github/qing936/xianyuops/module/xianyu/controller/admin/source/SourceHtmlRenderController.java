package io.github.qing936.xianyuops.module.xianyu.controller.admin.source;

import io.github.qing936.xianyuops.framework.tenant.core.aop.TenantIgnore;
import io.github.qing936.xianyuops.module.xianyu.service.source.PythonSourceNotifier;
import io.github.qing936.xianyuops.module.xianyu.service.source.SourceProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.security.MessageDigest;

@Tag(name = "工作流 HTML 渲染")
@RestController
@RequestMapping("/app/source")
@Validated
@PermitAll
@TenantIgnore
public class SourceHtmlRenderController {

    private static final int MAX_HTML_BYTES = 1_000_000;

    @Resource
    private PythonSourceNotifier pythonSourceNotifier;
    @Resource
    private SourceProperties sourceProperties;

    @PostMapping(value = "/render-html", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "将 HTML 渲染为 PNG，供 Dify 工作流调用")
    public ResponseEntity<byte[]> renderHtml(
            @RequestHeader(value = "X-Render-Token", required = false) String token,
            HttpServletRequest request) {
        if (!isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (request.getContentLengthLong() > MAX_HTML_BYTES) {
            return ResponseEntity.status(413).build();
        }

        final byte[] html;
        try {
            html = request.getInputStream().readNBytes(MAX_HTML_BYTES + 1);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        if (html.length == 0) {
            return ResponseEntity.badRequest().build();
        }
        if (html.length > MAX_HTML_BYTES) {
            return ResponseEntity.status(413).build();
        }

        try {
            byte[] png = pythonSourceNotifier.renderHtml(html);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    private boolean isAuthorized(String token) {
        String expected = sourceProperties.getHtmlRenderApiKey();
        return expected != null && !expected.isBlank() && token != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
