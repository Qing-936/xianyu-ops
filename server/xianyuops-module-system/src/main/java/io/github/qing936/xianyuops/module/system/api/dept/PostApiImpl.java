package io.github.qing936.xianyuops.module.system.api.dept;

import io.github.qing936.xianyuops.framework.common.util.object.BeanUtils;
import io.github.qing936.xianyuops.module.system.api.dept.dto.PostRespDTO;
import io.github.qing936.xianyuops.module.system.dal.dataobject.dept.PostDO;
import io.github.qing936.xianyuops.module.system.service.dept.PostService;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collection;
import java.util.List;

/**
 * 岗位 API 实现类
 *
 * @author XianyuOps系统
 */
@Service
public class PostApiImpl implements PostApi {

    @Resource
    private PostService postService;

    @Override
    public void validPostList(Collection<Long> ids) {
        postService.validatePostList(ids);
    }

    @Override
    public List<PostRespDTO> getPostList(Collection<Long> ids) {
        List<PostDO> list = postService.getPostList(ids);
        return BeanUtils.toBean(list, PostRespDTO.class);
    }

}
