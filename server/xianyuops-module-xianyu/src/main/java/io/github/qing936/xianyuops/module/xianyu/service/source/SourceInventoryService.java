package io.github.qing936.xianyuops.module.xianyu.service.source;

import io.github.qing936.xianyuops.framework.common.pojo.PageResult;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryManualImportRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryPageReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceCleanSkillRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanSubmitReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanSubmitRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanStatusRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.source.vo.SourceInventoryCleanRecommendRespVO;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

public interface SourceInventoryService {

    void addFromSourceDetail(Long detailId, String inventoryUser, Long tenantId);

    SourceInventoryManualImportRespVO manualImportBatch(List<String> detailUrls, String inventoryUser, Long tenantId);

    void refreshInventory(Long id, String inventoryUser, Long tenantId);

    PageResult<SourceInventoryRespVO> getInventoryPage(SourceInventoryPageReqVO reqVO);

    void deleteInventory(Long id);

    void deleteBatch(List<Long> ids);

    void exportCsv(SourceInventoryPageReqVO reqVO, HttpServletResponse response);

    SourceInventoryCleanSubmitRespVO submitCleanTask(SourceInventoryCleanSubmitReqVO reqVO);

    SourceInventoryCleanRecommendRespVO recommendCleanSkills(List<Long> inventoryIds);

    SourceInventoryCleanStatusRespVO getCleanTaskStatus(Long taskId);

    List<SourceCleanSkillRespVO> getCleanSkillOptions();
}
