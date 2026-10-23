package io.github.qing936.xianyuops.module.xianyu.service.config;

import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigFileDetailRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigFileRespVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigFileSaveReqVO;
import io.github.qing936.xianyuops.module.xianyu.controller.admin.config.vo.ConfigServiceRespVO;

import java.util.List;

/**
 * 服务配置 Service
 */
public interface ConfigFileService {

    /** 服务列表 */
    List<ConfigServiceRespVO> getServices();

    /** 指定服务的配置文件列表（不含内容） */
    List<ConfigFileRespVO> getFiles(String serviceCode);

    /** 配置文件详情（含内容） */
    ConfigFileDetailRespVO getFile(String serviceCode, String fileName);

    /** 保存文件内容（md 会被编译校验，失败抛出带行号的中文错误） */
    void saveFile(ConfigFileSaveReqVO reqVO);

    /** 恢复为资源默认内容 */
    void resetFile(String serviceCode, String fileName);

}
