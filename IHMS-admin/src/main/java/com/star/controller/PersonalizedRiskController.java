
package com.star.controller;

import com.star.common.Result;
import com.star.context.LocalThreadHolder;
import com.star.model.dto.risk.ElderRiskProfileDto;
import com.star.model.dto.risk.CurrentRiskAssessmentDto;
import com.star.model.dto.risk.PersonalizedRiskResponse;
import com.star.model.dto.risk.VitalMeasurementDto;
import com.star.service.risk.PersonalizedRiskService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.dao.DataAccessException;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/personalized-risk")
public class PersonalizedRiskController {

    @Resource
    private PersonalizedRiskService personalizedRiskService;

    @GetMapping("/profile")
    public Result<Map<String, Object>> profile() {
        requireOwnUser();
        return Result.success(personalizedRiskService.getProfile(LocalThreadHolder.getUserId()));
    }

    @PostMapping("/profile")
    public Result<Void> saveProfile(@RequestBody ElderRiskProfileDto dto) {
        requireOwnUser();
        personalizedRiskService.saveProfile(LocalThreadHolder.getUserId(), dto);
        return Result.success();
    }

    @PostMapping("/basic-profile")
    public ResponseEntity<Result<?>> saveBasicProfile(@RequestBody ElderRiskProfileDto dto) {
        requireOwnUser();
        try { personalizedRiskService.saveBasicProfile(LocalThreadHolder.getUserId(), dto); return ResponseEntity.ok(Result.success()); }
        catch (IllegalArgumentException error) { return failure(400, error.getMessage()); }
        catch (DataAccessException error) { return failure(503, "个性化基础档案暂未保存，请检查数据库后重试"); }
    }

    @PostMapping("/assess-current")
    public ResponseEntity<Result<?>> assessCurrent(@RequestBody CurrentRiskAssessmentDto dto) {
        requireOwnUser();
        try { return ResponseEntity.ok(Result.success(personalizedRiskService.assessCurrent(LocalThreadHolder.getUserId(), dto))); }
        catch (IllegalArgumentException error) { return failure(400, error.getMessage()); }
        catch (RestClientException error) { return failure(503, "个性化模型连接中断、响应超时或返回错误，请稍后重试并查看后端日志"); }
        catch (DataAccessException error) { return failure(503, "个性化评估未保存，请检查数据库后重试"); }
        catch (IllegalStateException error) { return failure(503, "个性化模型尚未就绪，请稍后重试并查看后端日志"); }
    }

    private void requireOwnUser() {
        Integer role = LocalThreadHolder.getRoleId();
        if (role == null || (role != 1 && role != 2))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "请使用本人用户账号进行联合评估");
    }

    private ResponseEntity<Result<?>> failure(int code, String message) {
        return ResponseEntity.status(code).body(new Result<>(code, message, null));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Result<?>> status(org.springframework.web.server.ResponseStatusException error) {
        return failure(error.getStatus().value(),error.getReason());
    }

    @PostMapping("/measurement")
    public Result<Void> saveMeasurement(@RequestBody VitalMeasurementDto dto) {
        requireOwnUser();
        personalizedRiskService.saveMeasurement(LocalThreadHolder.getUserId(), dto);
        return Result.success();
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(@RequestParam(defaultValue = "30") Integer days) {
        requireOwnUser();
        return Result.success(personalizedRiskService.getHistory(LocalThreadHolder.getUserId(), days));
    }

    @PostMapping("/assess")
    public Result<PersonalizedRiskResponse> assess() {
        requireOwnUser();
        return Result.success(personalizedRiskService.assess(LocalThreadHolder.getUserId()));
    }

    @GetMapping("/latest")
    public Result<Map<String, Object>> latest() {
        requireOwnUser();
        return Result.success(personalizedRiskService.latest(LocalThreadHolder.getUserId()));
    }
}
