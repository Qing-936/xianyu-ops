package io.github.qing936.xianyuops.module.xianyu.service.replytask;

import cn.hutool.core.util.StrUtil;
import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuReplyTaskCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.service.message.XianyuMediaHosts;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 出站媒体归一：image 必须有 HTTPS 白名单 URL；emoji 必须有 token（url 可选，仅展示）。
 * <p>
 * 与入站共用 {@link XianyuMediaHosts}；出站 ext_json 不计算 dedupKey/sourceMessageId。
 * 注意：入站仍容忍 http→https 归一（历史数据），出站强制 HTTPS；该差异是有意的。
 */
public final class XianyuOutboundMediaNormalizer {

    private static final int URL_MAX_LENGTH = 4096;
    private static final int ALT_MAX_LENGTH = 500;

    public record Media(String type, String url, String alt) {
    }

    private XianyuOutboundMediaNormalizer() {
    }

    public static Media normalize(String contentType, XianyuReplyTaskCreateReqVO.MediaVO media) {
        if (media == null) {
            throw new IllegalArgumentException("媒体内容不能为空");
        }
        if (StringUtils.hasText(media.getType()) && !contentType.equals(media.getType())) {
            throw new IllegalArgumentException("media.type 必须与 contentType 一致");
        }
        if ("image".equals(contentType)) {
            String url = normalizeUrl(media.getUrl());
            if (url == null) {
                throw new IllegalArgumentException("图片 URL 不能为空");
            }
            return new Media("image", url, null);
        }
        if ("emoji".equals(contentType)) {
            String alt = StrUtil.trimToNull(media.getAlt());
            if (alt == null || alt.length() > ALT_MAX_LENGTH) {
                throw new IllegalArgumentException("表情 token 不能为空且不能超过 " + ALT_MAX_LENGTH + " 字符");
            }
            return new Media("emoji", normalizeUrl(media.getUrl()), alt);
        }
        throw new IllegalArgumentException("媒体类型仅支持 image/emoji");
    }

    public static String toExtJson(Media media) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", media.type());
        if (media.url() != null) {
            item.put("url", media.url());
        }
        if (media.alt() != null) {
            item.put("alt", media.alt());
        }
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("type", "media");
        ext.put("items", List.of(item));
        return JsonUtils.toJsonString(ext);
    }

    public static Media fromExtJson(String extJson) {
        if (!StringUtils.hasText(extJson)) {
            return null;
        }
        try {
            Map<String, Object> ext = JsonUtils.parseObject(
                    extJson, new TypeReference<Map<String, Object>>() {
                    });
            if (ext == null || !"media".equals(ext.get("type"))
                    || !(ext.get("items") instanceof List<?> items) || items.isEmpty()
                    || !(items.get(0) instanceof Map<?, ?> item)) {
                return null;
            }
            Object type = item.get("type");
            Object url = item.get("url");
            Object alt = item.get("alt");
            if (!(type instanceof String typeText)) {
                return null;
            }
            return new Media(typeText,
                    url instanceof String urlText ? urlText : null,
                    alt instanceof String altText ? altText : null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 出站 URL 只接受 HTTPS + 可信域名 + 无 userinfo/默认端口，并归一为小写 host。
     */
    private static String normalizeUrl(String value) {
        String url = StrUtil.trimToNull(value);
        if (url == null) {
            return null;
        }
        if (url.length() > URL_MAX_LENGTH || hasControl(url)) {
            throw new IllegalArgumentException("媒体 URL 不合法");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (!"https".equalsIgnoreCase(scheme) || host == null || uri.getRawUserInfo() != null
                    || (port != -1 && port != 443)
                    || !uri.getRawAuthority().equalsIgnoreCase(host + (port == -1 ? "" : ":" + port))
                    || hasControl(uri.getSchemeSpecificPart()) || hasControl(uri.getFragment())) {
                throw new IllegalArgumentException("媒体 URL 必须为无用户信息、默认端口的 HTTPS 地址");
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (XianyuMediaHosts.HOSTS.stream().noneMatch(domain -> normalizedHost.equals(domain)
                    || normalizedHost.endsWith("." + domain))) {
                throw new IllegalArgumentException("媒体 URL 域名不在可信媒体白名单内");
            }
            String path = StrUtil.isEmpty(uri.getRawPath()) ? "/" : uri.getRawPath();
            String normalized = new URI("https://" + normalizedHost + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment())).toASCIIString();
            if (normalized.length() > URL_MAX_LENGTH) {
                throw new IllegalArgumentException("媒体 URL 超过长度上限");
            }
            return normalized;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("媒体 URL 格式不合法");
        }
    }

    private static boolean hasControl(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }
}
