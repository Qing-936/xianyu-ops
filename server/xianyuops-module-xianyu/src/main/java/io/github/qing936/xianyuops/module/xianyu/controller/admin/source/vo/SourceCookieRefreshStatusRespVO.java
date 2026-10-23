package io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class SourceCookieRefreshStatusRespVO {

    @JsonAlias("session_id")
    private String sessionId;

    private String status;

    private String message;
}
