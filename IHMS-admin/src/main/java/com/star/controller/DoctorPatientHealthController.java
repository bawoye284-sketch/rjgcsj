package com.star.controller;

import com.star.common.Result;
import com.star.context.LocalThreadHolder;
import com.star.service.doctor.DoctorPatientHealthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/doctor-patients")
public class DoctorPatientHealthController {

    @Resource
    private DoctorPatientHealthService patientHealthService;

    @GetMapping
    public Result<List<Map<String, Object>>> patients() {
        return Result.success(patientHealthService.patients(currentDoctorId()));
    }

    @GetMapping("/{patientId}/health")
    public Result<Map<String, Object>> health(@PathVariable Integer patientId,
                                               @RequestParam(defaultValue = "30") Integer days) {
        return Result.success(patientHealthService.health(currentDoctorId(), patientId, days));
    }

    private Integer currentDoctorId() {
        if (!Integer.valueOf(3).equals(LocalThreadHolder.getRoleId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅医生可查看患者身体数据");
        }
        return LocalThreadHolder.getUserId();
    }
}
