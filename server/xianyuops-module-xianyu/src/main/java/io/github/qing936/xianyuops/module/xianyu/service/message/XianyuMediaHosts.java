package io.github.qing936.xianyuops.module.xianyu.service.message;

import java.util.List;

/**
 * 闲鱼媒体可信域名（入站/出站共用，避免两套白名单漂移）。
 */
public final class XianyuMediaHosts {

    public static final List<String> HOSTS = List.of(
            "alicdn.com", "taobaocdn.com", "tbcdn.cn", "aliyuncs.com", "goofish.com",
            "dingtalk.com", "dingtalk.cn", "aliimg.com");

    private XianyuMediaHosts() {
    }
}
