package com.star.controller;

import com.star.common.Result;
import com.star.service.notification.DoctorAlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/doctor-health-alerts")
public class DoctorHealthAlertController {
    private final DoctorAlertService doctors;
    public DoctorHealthAlertController(DoctorAlertService doctors) {this.doctors=doctors;}
    @GetMapping
    public Result<?> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,
                          @RequestParam(required=false) String level,@RequestParam(required=false) String state,
                          @RequestParam(required=false) String handling,@RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        return new HealthCareController.PrivateResult(doctors.list(DoctorAlertService.doctor(),page,size,level,state,handling,from,to));
    }
    @GetMapping("/{id}")
    public Result<?> detail(@PathVariable long id) {return new HealthCareController.PrivateResult(doctors.detail(DoctorAlertService.doctor(),id));}
    public static class ActionInput {public String note;}
    @PostMapping("/{id}/acknowledge")
    public Result<?> acknowledge(@PathVariable long id,@RequestBody ActionInput input) {doctors.action(DoctorAlertService.doctor(),id,"ACKNOWLEDGED",input==null?null:input.note);return new HealthCareController.PrivateResult(null);}
    @PostMapping("/{id}/resolve")
    public Result<?> resolve(@PathVariable long id,@RequestBody ActionInput input) {doctors.action(DoctorAlertService.doctor(),id,"RESOLVED",input==null?null:input.note);return new HealthCareController.PrivateResult(null);}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<?>> denied(ResponseStatusException e) {return ResponseEntity.status(e.getStatus()).body(new Result<>(e.getStatus().value(),e.getReason(),null));}
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> invalid(IllegalArgumentException e) {return ResponseEntity.badRequest().body(new Result<>(400,e.getMessage(),null));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Result<?>> unavailable() {return ResponseEntity.status(503).body(new Result<>(503,"预警管理暂不可用，请稍后重试",null));}
}
