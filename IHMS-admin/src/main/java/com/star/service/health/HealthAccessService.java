package com.star.service.health;

import com.star.context.LocalThreadHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
public class HealthAccessService {
    private final JdbcTemplate jdbc;
    public HealthAccessService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public int role() {
        Integer role = LocalThreadHolder.getRoleId();
        if (LocalThreadHolder.getUserId() == null || role == null || role < 1 || role > 3)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录");
        return role;
    }
    public void requireReviewer() {
        if (role() == 2) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅医生或管理员可查询患者列表");
    }
    public void patient(int userId) {
        List<Object> args = new java.util.ArrayList<>();
        String scope = scope("u.id", args); args.add(0, userId);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM `user` u WHERE u.id=? AND " + scope, Integer.class, args.toArray());
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该用户的健康资料");
    }
    public String scope(String userColumn, List<Object> args) {
        int role = role();
        if (role == 1) return "1=1";
        args.add(LocalThreadHolder.getUserId());
        return role == 2 ? userColumn + "=?" : "EXISTS (SELECT 1 FROM doctor_consultation hc WHERE hc.patient_id=" + userColumn + " AND hc.doctor_id=?)";
    }
}
