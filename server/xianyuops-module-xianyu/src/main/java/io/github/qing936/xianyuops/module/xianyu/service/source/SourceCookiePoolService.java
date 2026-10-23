package io.github.qing936.xianyuops.module.xianyu.service.source;

import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceCookieDO;
import io.github.qing936.xianyuops.module.xianyu.enums.SourceCookieStatusEnum;

import java.time.LocalDateTime;

public interface SourceCookiePoolService {

    Long saveOrUpdateByUnb(String unb, String cookieJson);

    SourceCookieDO acquireAvailableCookie();

    void recordRuntimeRefresh(Long id, String cookieJson);

    void markFailure(Long id, SourceCookieStatusEnum status, LocalDateTime cooldownUntil);
}
