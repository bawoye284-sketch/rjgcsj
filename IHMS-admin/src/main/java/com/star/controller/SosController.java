package com.star.controller;

import com.star.common.Result;
import com.star.service.notification.EmergencyContactService;
import com.star.service.notification.SosService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/sos")
public class SosController {
    private final SosService sos;
    public SosController(SosService sos) {this.sos=sos;}
    public static class Input {public String requestId;}
    @PostMapping
    public Result<?> create(@RequestBody Input input) {int user=EmergencyContactService.owner();long id=sos.create(user,input==null?null:input.requestId);return new HealthCareController.PrivateResult(sos.businessView(sos.detail(user,id)));}
    @GetMapping("/policy")
    public Result<?> policy() {EmergencyContactService.owner();return new HealthCareController.PrivateResult(sos.policy());}
    @PostMapping("/{id}/reminders")
    public Result<?> remind(@PathVariable long id,@RequestBody Input input) {int user=EmergencyContactService.owner();sos.remind(user,id,input==null?null:input.requestId);return new HealthCareController.PrivateResult(sos.businessView(sos.detail(user,id)));}
    @GetMapping("/current")
    public Result<?> current() {return new HealthCareController.PrivateResult(sos.businessView(sos.current(EmergencyContactService.owner())));}
    @GetMapping("/{id}")
    public Result<?> detail(@PathVariable long id) {return new HealthCareController.PrivateResult(sos.businessView(sos.detail(EmergencyContactService.owner(),id)));}
    @GetMapping
    public Result<?> history(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size) {return new HealthCareController.PrivateResult(sos.history(EmergencyContactService.owner(),page,size));}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<?>> denied(ResponseStatusException e) {return ResponseEntity.status(e.getStatus()).body(new Result<>(e.getStatus().value(),e.getReason(),null));}
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> invalid(IllegalArgumentException e) {return ResponseEntity.badRequest().body(new Result<>(400,e.getMessage(),null));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Result<?>> unavailable() {return ResponseEntity.status(503).body(new Result<>(503,"SOS暂不可用；若严重不适请立即主动联系当地急救，电子通知不能替代急救响应",null));}
}
