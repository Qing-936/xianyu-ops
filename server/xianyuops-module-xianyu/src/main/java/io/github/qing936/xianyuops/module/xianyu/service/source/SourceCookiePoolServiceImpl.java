package io.github.qing936.xianyuops.module.xianyu.service.source;

import cn.hutool.core.util.StrUtil;
import io.github.qing936.xianyuops.module.xianyu.dal.data.SourceCookieDO;
import io.github.qing936.xianyuops.module.xianyu.dal.mysql.SourceCookieMapper;
import io.github.qing936.xianyuops.module.xianyu.enums.SourceCookieStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Validated
public class SourceCookiePoolServiceImpl implements SourceCookiePoolService {

    @Resource
    private SourceCookieMapper sourceCookieMapper;

    @Override
    public Long saveOrUpdateByUnb(String unb, String cookieJson) {
        if (StrUtil.isBlank(unb)) {
            throw new IllegalArgumentException("unb 不能为空");
        }
        if (StrUtil.isBlank(cookieJson)) {
            throw new IllegalArgumentException("cookieJson 不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        SourceCookieDO existing = sourceCookieMapper.selectByUnb(unb);
        if (existing != null) {
            SourceCookieDO update = new SourceCookieDO();
            update.setId(existing.getId());
            update.setCookieJson(cookieJson);
            update.setStatus(SourceCookieStatusEnum.ACTIVE.getStatus());
            update.setLastRefreshAt(now);
            update.setCooldownUntil(null);
            sourceCookieMapper.updateById(update);
            return existing.getId();
        }

        SourceCookieDO create = new SourceCookieDO();
        create.setUnb(unb);
        create.setCookieJson(cookieJson);
        create.setStatus(SourceCookieStatusEnum.ACTIVE.getStatus());
        create.setLastRefreshAt(now);
        create.setTenantId(0L);
        sourceCookieMapper.insert(create);
        return create.getId();
    }

    @Override
    public SourceCookieDO acquireAvailableCookie() {
        List<SourceCookieDO> available = sourceCookieMapper.selectAvailableList(LocalDateTime.now());
        if (available.isEmpty()) {
            return null;
        }
        SourceCookieDO cookie = available.get(0);
        sourceCookieMapper.touchLastUsedAt(cookie.getId(), LocalDateTime.now());
        return cookie;
    }

    @Override
    public void recordRuntimeRefresh(Long id, String cookieJson) {
        if (id == null || StrUtil.isBlank(cookieJson)) {
            throw new IllegalArgumentException("刷新 Cookie 参数不完整");
        }
        sourceCookieMapper.updateRuntimeRefresh(id, cookieJson, LocalDateTime.now());
    }

    @Override
    public void markFailure(Long id, SourceCookieStatusEnum status, LocalDateTime cooldownUntil) {
        if (id == null || status == null) {
            throw new IllegalArgumentException("失败状态参数不完整");
        }
        sourceCookieMapper.updateFailureState(id, status.getStatus(), cooldownUntil);
    }
}
