package io.github.qing936.xianyuops.module.xianyu.service.source;

import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryImportCreateReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryImportParseRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryImportResultRespVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 货源库批量导入 Service 接口
 *
 * <p>流程：解析 Excel 生成预览（不落库）→ 前端确认后逐条写入货源库。</p>
 */
public interface SourceInventoryImportService {

    /**
     * 解析 Excel 文件，生成货源库导入预览数据（不落库）
     *
     * @param file Excel 文件（XML 2003 / xlsx，表头与商品批量导入一致）
     * @return 解析预览列表（含是否已入库标记）
     */
    List<SourceInventoryImportParseRespVO> parse(MultipartFile file);

    /**
     * 批量导入到货源库：逐条写入，单条失败不影响其他条
     *
     * @param reqVO 导入请求
     * @param inventoryUser 入库操作人（当前登录用户 ID）
     * @return 每条商品的导入结果
     */
    List<SourceInventoryImportResultRespVO> create(SourceInventoryImportCreateReqVO reqVO, String inventoryUser);

}
