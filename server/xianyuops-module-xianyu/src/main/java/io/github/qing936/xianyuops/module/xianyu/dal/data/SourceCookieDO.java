package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@TableName("app_source_cookie")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SourceCookieDO extends TenantBaseDO {

    @TableId
    private Long id;

    private String unb;

    private String cookieJson;

    private Integer status;

    private LocalDateTime lastUsedAt;

    private LocalDateTime lastRefreshAt;

    private LocalDateTime cooldownUntil;
}
