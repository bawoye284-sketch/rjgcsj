package com.star.controller;

import com.star.annotation.Pager;
import com.star.context.LocalThreadHolder;
import com.star.common.Result;
import com.star.model.dto.query.base.QueryDto;
import com.star.model.dto.query.extend.UserHealthQueryDto;
import com.star.model.entity.UserHealth;
import com.star.model.vo.ChartVO;
import com.star.model.vo.UserHealthVO;
import com.star.service.IUserHealthService;
import com.star.service.health.HealthAccessService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import com.star.utils.DateUtil;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * 用户健康记录的 Controller
 */
@RestController
@RequestMapping(value = "/user-health")
public class UserHealthController {

    @Resource
    private IUserHealthService userHealthService;

    @Resource private HealthAccessService healthAccess;
    @Resource private JdbcTemplate jdbc;

    private void writable() {
        if (healthAccess.role()==3) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"医生仅可查看关联患者健康记录");
    }
    private void recordOwner(Number id) {
        if(id==null) throw new IllegalArgumentException("健康记录编号不能为空");
        List<Integer> owners=jdbc.query("SELECT user_id FROM user_health WHERE id=?",(rs,n)->rs.getInt(1),id.longValue());
        if(owners.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"健康记录不存在");
        healthAccess.patient(owners.get(0));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<?>> accessError(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatus()).body(new Result<>(e.getStatus().value(),e.getReason(),null));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> inputError(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new Result<>(400,e.getMessage(),null));
    }


    /**
     * 用户健康记录新增
     *
     * @param userHealths 新增数据
     * @return Result<Void> 通用响应体
     */
    @PostMapping(value = "/save")
    public Result<Void> save(@RequestBody List<UserHealth> userHealths) {
        writable();
        return userHealthService.save(userHealths);
    }

    /**
     * 用户健康记录删除
     *
     * @param ids 要删除的用户健康记录ID列表
     * @return Result<Void> 通用响应体
     */
    @PostMapping(value = "/batchDelete")
    public Result<Void> batchDelete(@RequestBody List<Long> ids) {
        writable();
        if(ids==null || ids.isEmpty()) throw new IllegalArgumentException("请选择健康记录");
        ids.forEach(this::recordOwner);
        return userHealthService.batchDelete(ids);
    }

    /**
     * 用户健康记录修改
     *
     * @param userHealth 参数
     * @return Result<Void> 响应
     */
    @PutMapping(value = "/update")
    public Result<Void> update(@RequestBody UserHealth userHealth) {
        writable();recordOwner(userHealth.getId());
        if(healthAccess.role()==2) userHealth.setUserId(LocalThreadHolder.getUserId());
        else if(userHealth.getUserId()!=null) healthAccess.patient(userHealth.getUserId());
        return userHealthService.update(userHealth);
    }

    /**
     * 用户健康记录查询
     *
     * @param userHealthQueryDto 查询参数
     * @return Result<List < UserHealthVO>> 通用响应
     */
    @Pager
    @PostMapping(value = "/query")
    public Result<List<UserHealthVO>> query(@RequestBody UserHealthQueryDto userHealthQueryDto) {
        int role=healthAccess.role();
        if(userHealthQueryDto.getId()!=null) recordOwner(userHealthQueryDto.getId());
        if(role==2) {
            if(userHealthQueryDto.getUserId()!=null) healthAccess.patient(userHealthQueryDto.getUserId());
            userHealthQueryDto.setUserId(LocalThreadHolder.getUserId());
        } else if(role==3) {
            if(userHealthQueryDto.getUserId()==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请指定已关联的患者");
            healthAccess.patient(userHealthQueryDto.getUserId());
        }
        return userHealthService.query(userHealthQueryDto);
    }

    /**
     * 用户健康记录查询
     *
     * @param id  健康模型ID
     * @param day 往前查多少天
     * @return Result<List < UserHealthVO>> 通用响应
     */
    @GetMapping(value = "/timeQuery/{id}/{day}")
    public Result<List<UserHealthVO>> timeQuery(@PathVariable Integer id,
                                                @PathVariable Integer day) {
        writable();
        // 有了时间范围，也有了健康模型ID，还需要什么？
        Integer userId = LocalThreadHolder.getUserId();
        QueryDto queryDto = DateUtil.startAndEndTime(day);
        UserHealthQueryDto userHealthQueryDto = new UserHealthQueryDto();
        userHealthQueryDto.setHealthModelConfigId(id);
        userHealthQueryDto.setUserId(userId);
        userHealthQueryDto.setStartTime(queryDto.getStartTime());
        userHealthQueryDto.setEndTime(queryDto.getEndTime());
        return userHealthService.query(userHealthQueryDto);
    }

    /**
     * 统计模型存量数据
     *
     * @return Result<List < ChartVO>> 响应结果
     */
    @GetMapping(value = "/daysQuery/{day}")
    @ResponseBody
    public Result<List<ChartVO>> query(@PathVariable Integer day) {
        writable();
        return userHealthService.daysQuery(day);
    }

}
