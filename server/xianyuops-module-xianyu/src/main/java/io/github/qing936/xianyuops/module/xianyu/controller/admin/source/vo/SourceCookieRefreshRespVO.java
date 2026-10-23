package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class SourceCookieRefreshRespVO {

    @JsonAlias("session_id")
    private String sessionId;

    private String status;

    @JsonAlias("qr_url")
    private String qrUrl;

    @JsonAlias("expire_seconds")
    private Integer expireSeconds;

    private String message;
}
