package com.star.service.impl;

import com.star.context.LocalThreadHolder;
import com.star.mapper.HealthModelConfigMapper;
import com.star.common.CommonResult;
import com.star.common.CommonPageResult;
import com.star.common.Result;
import com.star.model.dto.query.extend.HealthModelConfigQueryDto;
import com.star.model.entity.HealthModelConfig;
import com.star.model.vo.HealthModelConfigVO;
import com.star.service.IHealthModelConfigService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import com.star.service.health.MeasurementAlertService;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 健康模型业务逻辑实现
 */
@Service
public class HealthModelConfigServiceImpl implements IHealthModelConfigService {

    @Resource
    private HealthModelConfigMapper healthModelConfigMapper;

    @Resource private JdbcTemplate jdbc;

    private void writable(long id) {
        List<java.util.Map<String,Object>> rows=jdbc.queryForList("SELECT user_id,is_global FROM health_model_config WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"指标配置不存在");
        java.util.Map<String,Object> row=rows.get(0);
        Object flag=row.get("is_global");
        boolean global=Boolean.TRUE.equals(flag) || (flag instanceof Number && ((Number)flag).intValue()==1);
        if(!Integer.valueOf(1).equals(LocalThreadHolder.getRoleId()) && (global || !Integer.valueOf(2).equals(LocalThreadHolder.getRoleId()) || !java.util.Objects.equals(row.get("user_id"),LocalThreadHolder.getUserId())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只能修改自己的私有指标，不能修改全局配置");
    }

    /**
     * 健康模型新增
     *
     * @param healthModelConfig 参数
     * @return Result<Void>
     */
    @Override
    public Result<Void> save(HealthModelConfig healthModelConfig) {
        if(Boolean.TRUE.equals(healthModelConfig.getIsGlobal()) && !Integer.valueOf(1).equals(LocalThreadHolder.getRoleId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可新增全局指标");
        MeasurementAlertService.parseRange(healthModelConfig.getValueRange());
        // 将用户的ID设置上
        healthModelConfig.setUserId(LocalThreadHolder.getUserId());
        healthModelConfigMapper.save(healthModelConfig);
        return CommonResult.success();
    }

    /**
     * 健康模型删除
     *
     * @param ids 参数
     * @return Result<Void>
     */
    @Override
    @Transactional
    public Result<Void> batchDelete(List<Long> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>100) throw new IllegalArgumentException("请选择1至100条指标");
        ids.stream().sorted().forEach(this::writable);
        healthModelConfigMapper.batchDelete(ids);
        return CommonResult.success();
    }

    /**
     * 健康模型修改
     *
     * @param healthModelConfig 参数
     * @return Result<Void>
     */
    @Override
    @Transactional
    public Result<Void> update(HealthModelConfig healthModelConfig) {
        if(healthModelConfig.getId()==null) throw new IllegalArgumentException("指标ID不能为空");
        writable(healthModelConfig.getId());
        if(healthModelConfig.getValueRange()!=null) MeasurementAlertService.parseRange(healthModelConfig.getValueRange());
        healthModelConfigMapper.update(healthModelConfig);
        return CommonResult.success();
    }

    /**
     * 查询用户自己配置的模型及全局模型
     *
     * @param healthModelConfigQueryDto 查询参数
     * @return Result<List < HealthModelConfigVO>>
     */
    @Override
    public Result<List<HealthModelConfigVO>> modelList() {
        HealthModelConfigQueryDto healthModelConfigQueryDto = new HealthModelConfigQueryDto();
        healthModelConfigQueryDto.setUserId(LocalThreadHolder.getUserId());
        List<HealthModelConfigVO> modelConfigs = healthModelConfigMapper.query(healthModelConfigQueryDto);
        healthModelConfigQueryDto.setUserId(null);
        healthModelConfigQueryDto.setIsGlobal(true);
        List<HealthModelConfigVO> modelConfigsGlobal = healthModelConfigMapper.query(healthModelConfigQueryDto);
        List<HealthModelConfigVO> modelAll = new ArrayList<>();
        modelAll.addAll(modelConfigs);
        modelAll.addAll(modelConfigsGlobal);
        return CommonResult.success(modelAll);
    }

    /**
     * 健康模型查询
     *
     * @param healthModelConfigQueryDto 查询参数
     * @return Result<List < HealthModelConfigVO>>
     */
    @Override
    public Result<List<HealthModelConfigVO>> query(HealthModelConfigQueryDto healthModelConfigQueryDto) {
        List<HealthModelConfigVO> modelConfigs = healthModelConfigMapper.query(healthModelConfigQueryDto);
        Integer totalCount = healthModelConfigMapper.queryCount(healthModelConfigQueryDto);
        return CommonPageResult.success(modelConfigs, totalCount);
    }

}
