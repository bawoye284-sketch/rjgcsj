package com.star.controller;

import com.star.common.Result;
import com.star.model.dto.risk.HealthReviewQuery;
import com.star.service.health.HealthReviewService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/health-review")
public class HealthReviewController {
    private final HealthReviewService service;
    public HealthReviewController(HealthReviewService service) { this.service=service; }
    @GetMapping("/patients") public Result<?> patients(HealthReviewQuery q) { return Result.success(service.patients(q)); }
    @GetMapping("/statistics") public Result<?> statistics(HealthReviewQuery q) { return Result.success(service.statistics(q)); }
    @GetMapping("/assessments") public Result<?> assessments(HealthReviewQuery q) { return Result.success(service.assessments(q)); }
    @GetMapping("/measurements") public Result<?> measurements(HealthReviewQuery q) { return Result.success(service.measurements(q)); }
    @GetMapping("/patients/{id}") public Result<?> patient(@PathVariable int id,@RequestParam(defaultValue="30") int days) { return Result.success(service.overview(id,days)); }
    @GetMapping("/assessments/{kind}/{id}") public Result<?> detail(@PathVariable String kind,@PathVariable String id) { return Result.success(service.detail(kind,id)); }
    @GetMapping("/models") public Result<?> models() { return Result.success(service.models()); }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Result<?>> status(ResponseStatusException e) { return ResponseEntity.status(e.getStatus()).body(new Result<>(e.getStatus().value(),e.getReason(),null)); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Result<?>> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(new Result<>(400,e.getMessage(),null)); }
    @ExceptionHandler(DataAccessException.class) public ResponseEntity<Result<?>> database(DataAccessException e) { return ResponseEntity.status(503).body(new Result<>(503,"健康资料查询失败，请检查数据库和升级脚本",null)); }
}
