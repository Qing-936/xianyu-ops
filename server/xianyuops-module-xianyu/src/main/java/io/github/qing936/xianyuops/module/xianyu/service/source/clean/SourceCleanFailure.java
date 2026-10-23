package io.github.qing936.xianyuops.module.xianyu.service.source.clean;

/** 仅包含程序定义的安全错误信息，不得包含远端响应、密钥或图片URL。 */
public class SourceCleanFailure extends IllegalStateException {
    public SourceCleanFailure(String message) {
        super(message);
    }
}
