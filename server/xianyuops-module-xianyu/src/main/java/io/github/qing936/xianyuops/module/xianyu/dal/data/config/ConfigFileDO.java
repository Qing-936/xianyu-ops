package io.github.qing936.xianyuops.module.xianyu.dal.data.config;

import io.github.qing936.xianyuops.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 服务配置 - 配置文件
 */
@TableName("app_config_file")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ConfigFileDO extends TenantBaseDO {

    @TableId
    private Long id;

    /** 服务编码，如 source_clean */
    private String serviceCode;

    /** 文件名，如 skills.md */
    private String fileName;

    /** 标签：prompt/config/template/data */
    private String tag;

    /** 文件用途说明 */
    private String description;

    /** 当前内容 */
    private String content;

    /** 是否被人工修改（0=跟随资源默认，1=已修改不再被覆盖） */
    private Boolean modified;

    /** 排序 */
    private Integer sortOrder;

}
