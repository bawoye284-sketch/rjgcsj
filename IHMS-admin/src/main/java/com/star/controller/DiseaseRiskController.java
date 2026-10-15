package com.star.controller;

import com.star.common.Result;
import com.star.context.LocalThreadHolder;
import com.star.model.dto.disease.*;
import com.star.service.disease.DiseaseRiskService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/disease-risk")
public class DiseaseRiskController {
    private final DiseaseRiskService service;
    public DiseaseRiskController(DiseaseRiskService service) { this.service = service; }
    private int currentUser() {
        if (LocalThreadHolder.getRoleId() == null || (LocalThreadHolder.getRoleId() != 1 && LocalThreadHolder.getRoleId() != 2))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请使用本人用户账号访问慢性病预测");
        return LocalThreadHolder.getUserId();
    }
    @GetMapping("/profile") public Result<?> profile() { return Result.success(service.profile(currentUser())); }
    @PostMapping("/profile") public Result<?> save(@RequestBody DiseaseProfileRequest request) { return Result.success(service.save(currentUser(), request)); }
    @GetMapping("/models") public Result<?> models() { currentUser(); return Result.success(service.models()); }
    @PostMapping("/assess") public Result<?> assess(@RequestBody DiseaseAssessmentRequest request) { return Result.success(service.assess(currentUser(), request)); }
    @GetMapping("/latest") public Result<?> latest() { return Result.success(service.latest(currentUser())); }
    @GetMapping("/history") public Result<?> history(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int pageSize) {
        return Result.success(service.history(currentUser(), page, pageSize));
    }
    @GetMapping("/assessments/{id}") public Result<?> detail(@PathVariable String id) { return Result.success(service.detail(currentUser(), id)); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<?>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatus()).body(new Result<>(error.getStatus().value(), error.getReason(), null));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new Result<>(400, error.getMessage(), null));
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<?>> database(DataAccessException error) {
        return ResponseEntity.status(503).body(new Result<>(503, "慢性病数据库暂不可用或表结构未就绪，请检查数据库迁移", null));
    }
}
