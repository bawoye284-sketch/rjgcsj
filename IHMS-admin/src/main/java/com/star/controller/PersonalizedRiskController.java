
package com.star.controller;

import com.star.common.Result;
import com.star.context.LocalThreadHolder;
import com.star.model.dto.risk.ElderRiskProfileDto;
import com.star.model.dto.risk.PersonalizedRiskResponse;
import com.star.model.dto.risk.VitalMeasurementDto;
import com.star.service.risk.PersonalizedRiskService;
import org.springframework.web.bind.annotation.*;

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
        return Result.success(personalizedRiskService.getProfile(LocalThreadHolder.getUserId()));
    }

    @PostMapping("/profile")
    public Result<Void> saveProfile(@RequestBody ElderRiskProfileDto dto) {
        personalizedRiskService.saveProfile(LocalThreadHolder.getUserId(), dto);
        return Result.success();
    }

    @PostMapping("/measurement")
    public Result<Void> saveMeasurement(@RequestBody VitalMeasurementDto dto) {
        personalizedRiskService.saveMeasurement(LocalThreadHolder.getUserId(), dto);
        return Result.success();
    }

    @GetMapping("/history")
    public Result<List<Map<String, Object>>> history(@RequestParam(defaultValue = "30") Integer days) {
        return Result.success(personalizedRiskService.getHistory(LocalThreadHolder.getUserId(), days));
    }

    @PostMapping("/assess")
    public Result<PersonalizedRiskResponse> assess() {
        return Result.success(personalizedRiskService.assess(LocalThreadHolder.getUserId()));
    }

    @GetMapping("/latest")
    public Result<Map<String, Object>> latest() {
        return Result.success(personalizedRiskService.latest(LocalThreadHolder.getUserId()));
    }
}
