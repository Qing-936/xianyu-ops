package io.github.qing936.xianyuops.module.xianyu.dal.data;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("app_source_clean_skill")
public class SourceCleanSkillDO extends TenantBaseDO {
    @TableId
    private Long id;
    private String skillCode;
    private String skillType;
    private String skillName;
    private String version;
    private String config;
    private Boolean customized;
    private Boolean enabled;
}
