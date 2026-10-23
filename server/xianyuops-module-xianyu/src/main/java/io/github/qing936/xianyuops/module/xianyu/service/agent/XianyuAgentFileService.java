package io.github.qing936.xianyuops.module.xianyu.service.agent;

import io.github.qing936.xianyuops.module.infra.api.file.FileApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static io.github.qing936.xianyuops.framework.common.exception.util.ServiceExceptionUtil.exception;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.SELF_ORDER_UPLOAD_EXCEED_LIMIT;
import static io.github.qing936.xianyuops.module.xianyu.enums.XianyuErrorCodeConstants.SELF_ORDER_UPLOAD_NOT_IMAGE;
import static io.github.qing936.xianyuops.module.infra.enums.ErrorCodeConstants.FILE_IS_EMPTY;

/**
 * 闲鱼 Agent 验证截图上传（03 号文档 D-03-1）
 * <p>
 * Agent 不持 OSS 凭证：验证截图经 Agent 侧文件接口上传，Java 走 infra 文件服务落 OSS 后返回 URL，
 * Agent 回传至 reply-task result 的 verifyImageUrl。
 * <p>
 * 上传校验错误码复用 self-order 同款（文本通用「仅支持上传图片文件/图片大小不能超过 10MB」），
 * 按 02 号文档「不新增错误码」约束执行。
 */
@Service
@Slf4j
public class XianyuAgentFileService {

    private static final long MAX_UPLOAD_SIZE = 10 * 1024 * 1024L; // 10MB

    @Resource
    private FileApi fileApi;

    /**
     * 上传验证截图至 OSS，返回可访问 URL。
     *
     * @param agentId Agent ID（从 token 推导，仅作审计日志，不影响存储路径）
     */
    public String upload(MultipartFile file, Long agentId) {
        // 上传防护：非空、仅 image/*、≤10MB（02 号文档 §4.6）
        if (file == null || file.isEmpty()) {
            throw exception(FILE_IS_EMPTY);
        }
        if (file.getContentType() == null || !file.getContentType().startsWith("image/")) {
            throw exception(SELF_ORDER_UPLOAD_NOT_IMAGE);
        }
        if (file.getSize() > MAX_UPLOAD_SIZE) {
            throw exception(SELF_ORDER_UPLOAD_EXCEED_LIMIT);
        }
        try {
            // infra 文件服务落 OSS（内部失败抛 infra 文件服务错误码，不新增）
            String url = fileApi.createFile(file.getBytes(), file.getOriginalFilename(),
                    "xianyu-agent-verify", file.getContentType());
            log.info("[xianyu-agent] 验证截图上传成功: agentId={}, url={}", agentId, url);
            return url;
        } catch (IOException e) {
            throw new IllegalStateException("读取上传文件失败", e);
        }
    }

}
