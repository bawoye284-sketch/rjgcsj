package com.star.controller;

import com.star.common.Result;
import com.star.context.LocalThreadHolder;
import com.star.service.notification.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class HealthCareController {
    private final EmergencyContactService contacts;
    private final DoctorAlertService doctors;
    private final HealthNotificationService notifications;
    public HealthCareController(EmergencyContactService contacts,DoctorAlertService doctors,HealthNotificationService notifications) {this.contacts=contacts;this.doctors=doctors;this.notifications=notifications;}
    /** Framework DEBUG response logging must not print private contact fields or verification tokens. */
    public static class PrivateResult extends Result<Object> {
        public PrivateResult(Object data) {super(200,"操作成功",data);}
        @Override public String toString() {return "HealthCareResult[private payload omitted]";}
    }
    private Result<?> ok(Object data) {return new PrivateResult(data);}
    @GetMapping("/emergency-contacts")
    public Result<?> contacts() {return ok(contacts.list(EmergencyContactService.owner()));}
    @PostMapping("/emergency-contacts")
    public Result<?> create(@RequestBody EmergencyContactService.Input input) {return ok(Collections.singletonMap("id",contacts.save(EmergencyContactService.owner(),null,input)));}
    @PutMapping("/emergency-contacts/{id}")
    public Result<?> update(@PathVariable long id,@RequestBody EmergencyContactService.Input input) {contacts.save(EmergencyContactService.owner(),id,input);return ok(null);}
    @DeleteMapping("/emergency-contacts/{id}")
    public Result<?> delete(@PathVariable long id) {contacts.delete(EmergencyContactService.owner(),id);return ok(null);}
    @PostMapping("/emergency-contacts/{id}/verification")
    public Result<?> challenge(@PathVariable long id) {return ok(contacts.challenge(EmergencyContactService.owner(),id));}
    @PostMapping("/emergency-contacts/{id}/verification/confirm")
    public Result<?> verify(@PathVariable long id,@RequestBody EmergencyContactService.VerificationInput input) {
        if(input==null || !contacts.verify(EmergencyContactService.owner(),id,input.token))throw new IllegalArgumentException("验证码错误、过期或已使用");return ok(null);
    }
    public static class AuthorizationInput {public Boolean authorized;}
    @GetMapping("/health-doctor-authorizations")
    public Result<?> authorizations() {return ok(doctors.authorizations(EmergencyContactService.owner()));}
    @PutMapping("/health-doctor-authorizations/{doctorId}")
    public Result<?> authorize(@PathVariable int doctorId,@RequestBody AuthorizationInput input) {
        if(input==null || input.authorized==null)throw new IllegalArgumentException("请明确是否授权");doctors.authorize(EmergencyContactService.owner(),doctorId,input.authorized);return ok(null);
    }
    @GetMapping("/health-notifications")
    public Result<?> notifications(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size) {
        return ok(notifications.list(role(),LocalThreadHolder.getUserId(),page,size));
    }
    private int role() {
        Integer role=LocalThreadHolder.getRoleId();
        if(LocalThreadHolder.getUserId()==null || role==null || (role!=2 && role!=3))throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"请使用本人用户或医生账号");return role;
    }
    @PostMapping("/health-notifications/{id}/retry")
    public Result<?> retry(@PathVariable long id) {notifications.retry(role(),LocalThreadHolder.getUserId(),id);return ok(null);}
    @PutMapping("/health-notifications/{id}/read")
    public Result<?> read(@PathVariable long id) {notifications.read(role(),LocalThreadHolder.getUserId(),id);return ok(null);}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<?>> denied(ResponseStatusException e) {return ResponseEntity.status(e.getStatus()).body(new Result<>(e.getStatus().value(),e.getReason(),null));}
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> invalid(IllegalArgumentException e) {return ResponseEntity.badRequest().body(new Result<>(400,e.getMessage(),null));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Result<?>> unavailable() {return ResponseEntity.status(503).body(new Result<>(503,"健康通知服务暂不可用，请稍后重试",null));}
}
