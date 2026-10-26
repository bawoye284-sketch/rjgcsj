package com.star.controller;

import com.star.common.Result;
import com.star.context.LocalThreadHolder;
import com.star.service.health.MeasurementAlertService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/health-alerts")
public class MeasurementAlertController {
    private final MeasurementAlertService alerts;
    public MeasurementAlertController(MeasurementAlertService alerts) { this.alerts=alerts; }
    private int owner() {
        Integer role=LocalThreadHolder.getRoleId(), id=LocalThreadHolder.getUserId();
        if(id==null || role==null || (role!=1 && role!=2)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请使用本人用户账号查看测量预警");
        return id;
    }
    private void admin() {
        if(!Integer.valueOf(1).equals(LocalThreadHolder.getRoleId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可配置预警规则");
    }
    @GetMapping("/status")
    public Result<Map<String,Object>> status(@RequestParam(required=false) Long measurementId) { return Result.success(alerts.status(owner(),measurementId)); }
    @GetMapping
    public Result<Map<String,Object>> history(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size) { return Result.success(alerts.history(owner(),page,size)); }
    @PostMapping("/{measurementId}/retry")
    public Result<Map<String,Object>> retry(@PathVariable long measurementId) { int user=owner();alerts.retry(user,measurementId);return Result.success(alerts.status(user,measurementId)); }
    @GetMapping("/rules")
    public Result<List<Map<String,Object>>> rules() { admin();return Result.success(alerts.rules()); }
    @PutMapping("/rules/{metric}")
    public Result<Void> update(@PathVariable String metric,@RequestBody MeasurementAlertService.RuleUpdate input) { admin();alerts.updateRule(metric,input);return Result.success(); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<?>> denied(ResponseStatusException e) { return ResponseEntity.status(e.getStatus()).body(new Result<>(e.getStatus().value(),e.getReason(),null)); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(new Result<>(400,e.getMessage(),null)); }
}
