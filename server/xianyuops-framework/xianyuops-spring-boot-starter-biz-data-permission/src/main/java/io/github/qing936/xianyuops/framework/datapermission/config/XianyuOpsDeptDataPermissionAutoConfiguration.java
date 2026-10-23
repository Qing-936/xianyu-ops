package io.github.qing936.xianyuops.framework.datapermission.config;

import io.github.qing936.xianyuops.framework.common.biz.system.permission.PermissionCommonApi;
import io.github.qing936.xianyuops.framework.datapermission.core.rule.dept.DeptDataPermissionRule;
import io.github.qing936.xianyuops.framework.datapermission.core.rule.dept.DeptDataPermissionRuleCustomizer;
import io.github.qing936.xianyuops.framework.security.core.LoginUser;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 基于部门的数据权限 AutoConfiguration
 *
 * @author XianyuOps系统
 */
@AutoConfiguration
@ConditionalOnClass(LoginUser.class)
@ConditionalOnBean(value = {DeptDataPermissionRuleCustomizer.class})
public class XianyuOpsDeptDataPermissionAutoConfiguration {

    @Bean
    public DeptDataPermissionRule deptDataPermissionRule(PermissionCommonApi permissionApi,
                                                         List<DeptDataPermissionRuleCustomizer> customizers) {
        // 创建 DeptDataPermissionRule 对象
        DeptDataPermissionRule rule = new DeptDataPermissionRule(permissionApi);
        // 补全表配置
        customizers.forEach(customizer -> customizer.customize(rule));
        return rule;
    }

}
