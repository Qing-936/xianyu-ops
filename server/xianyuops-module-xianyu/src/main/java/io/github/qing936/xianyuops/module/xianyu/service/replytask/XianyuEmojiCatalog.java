package io.github.qing936.xianyuops.module.xianyu.service.replytask;

import io.github.qing936.xianyuops.framework.common.util.json.JsonUtils;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.replytask.vo.XianyuEmojiCatalogRespVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 闲鱼 PC Web 表情目录（15 号文档 §6.4）。
 * <p>
 * 资源由 vm100 localStorage（PC_SITE_idlefish_im_emoji_data）导出；单一真源在 Java，
 * 管理端选择器与创建任务 alt 白名单共用，Agent 只按 alt 写 token。
 */
@Component
@Slf4j
public class XianyuEmojiCatalog {

    private static final String RESOURCE = "xianyu/emoji-catalog.json";

    private final List<XianyuEmojiCatalogRespVO> items;
    private final Set<String> alts;

    public XianyuEmojiCatalog() {
        List<XianyuEmojiCatalogRespVO> loaded = List.of();
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            String text = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            List<XianyuEmojiCatalogRespVO> parsed = JsonUtils.parseArray(text, XianyuEmojiCatalogRespVO.class);
            if (parsed != null) {
                loaded = parsed;
            }
        } catch (Exception e) {
            log.error("[xianyu-emoji] 表情目录加载失败: {}", RESOURCE, e);
        }
        this.items = List.copyOf(loaded);
        this.alts = loaded.stream()
                .map(XianyuEmojiCatalogRespVO::getAlt)
                .filter(alt -> alt != null && !alt.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        log.info("[xianyu-emoji] 表情目录已加载 {} 条", items.size());
    }

    public List<XianyuEmojiCatalogRespVO> list() {
        return items;
    }

    public boolean containsAlt(String alt) {
        return alt != null && alts.contains(alt.trim());
    }
}
