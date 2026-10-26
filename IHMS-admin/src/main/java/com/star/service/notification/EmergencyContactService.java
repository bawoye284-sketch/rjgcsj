package com.star.service.notification;

import com.star.context.LocalThreadHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class EmergencyContactService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HealthMailTransport mail;
    public EmergencyContactService(JdbcTemplate jdbc,PlatformTransactionManager manager,HealthMailTransport mail) { this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.mail=mail; }
    public static int owner() {
        if(!Integer.valueOf(2).equals(LocalThreadHolder.getRoleId()) || LocalThreadHolder.getUserId()==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请使用本人普通用户账号");
        return LocalThreadHolder.getUserId();
    }
    // No toString containing private fields; the API cannot set verification flags.
    public static class Input {
        public String name;
        public String relationship;
        public String email;
        public String phone;
        public Boolean authorized;
    }
    public static class VerificationInput { public String token; }
    private void validate(Input r) {
        if(r==null || r.name==null || r.name.trim().isEmpty() || r.name.length()>60 || r.relationship==null || r.relationship.trim().isEmpty() || r.relationship.length()>40
                || r.email==null || r.email.length()>254 || !r.email.matches("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}$")
                || (r.phone!=null && !r.phone.matches("[0-9+() -]{0,32}")) || r.authorized==null) throw new IllegalArgumentException("请填写有效联系人姓名、关系、邮箱、手机号和授权选择");
        r.name=r.name.trim();r.relationship=r.relationship.trim();r.email=r.email.trim();r.phone=r.phone==null?"":r.phone.trim();
    }
    public Map<String,Object> contact(int user,long id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM health_emergency_contact WHERE id=? AND user_id=?",id,user);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"联系人不存在");return rows.get(0);
    }
    public List<Map<String,Object>> list(int user) { return jdbc.queryForList("SELECT id,name,relationship,email,phone,authorized,email_verification,phone_verification FROM health_emergency_contact WHERE user_id=? ORDER BY id",user); }
    public long save(int user,Long id,Input r) {
        validate(r);
        return tx.execute(s->{
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            if(id==null) {
                if(jdbc.queryForObject("SELECT COUNT(*) FROM health_emergency_contact WHERE user_id=?",Integer.class,user)>=10) throw new IllegalArgumentException("最多设置10名紧急联系人");
                GeneratedKeyHolder keys=new GeneratedKeyHolder();
                jdbc.update(c->{PreparedStatement p=c.prepareStatement("INSERT INTO health_emergency_contact (user_id,name,relationship,email,phone,authorized) VALUES (?,?,?,?,?,?)",new String[]{"id"});
                    Object[] v={user,r.name,r.relationship,r.email,r.phone,r.authorized?1:0};for(int i=0;i<v.length;i++)p.setObject(i+1,v[i]);return p;},keys);return keys.getKey().longValue();
            }
            Map<String,Object> old=contact(user,id);
            boolean emailChanged=!Objects.equals(old.get("email"),r.email),phoneChanged=!Objects.equals(old.get("phone"),r.phone);
            jdbc.update("UPDATE health_emergency_contact SET name=?,relationship=?,email=?,phone=?,authorized=?,email_verification=?,phone_verification=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",
                    r.name,r.relationship,r.email,r.phone,r.authorized?1:0,emailChanged?"NONE":old.get("email_verification"),phoneChanged?"NONE":old.get("phone_verification"),id,user);
            jdbc.update("DELETE FROM health_contact_verification WHERE contact_id=?",id);
            return id;
        });
    }
    public void delete(int user,long id) {
        tx.execute(s->{jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);contact(user,id);
            jdbc.update("DELETE FROM health_contact_verification WHERE contact_id=?",id);jdbc.update("DELETE FROM health_emergency_contact WHERE id=? AND user_id=?",id,user);return null;});
    }
    public Map<String,Object> challenge(int user,long id) {
        try {mail.checkVerificationScope(user,id);}
        catch(HealthMailTransport.DeliveryFailure forbidden) {throw new ResponseStatusException(HttpStatus.FORBIDDEN,"当前仅允许验证指定测试账号已授权的唯一测试邮箱");}
        boolean mock=mail.mockMode();
        byte[] bytes=new byte[24];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Map<String,Object> contact=tx.execute(s->{
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            Map<String,Object> r=contact(user,id);
            List<Timestamp> previous=jdbc.query("SELECT expires_at FROM health_contact_verification WHERE contact_id=?",(rs,n)->rs.getTimestamp(1),id);
            if(!previous.isEmpty() && previous.get(0).toLocalDateTime().isAfter(LocalDateTime.now().plusMinutes(14))) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"请稍后再申请验证");
            jdbc.update("DELETE FROM health_contact_verification WHERE contact_id=?",id);
            jdbc.update("INSERT INTO health_contact_verification (contact_id,token_hash,verification_kind,contact_revision,expires_at) VALUES (?,?,?,?,?)",id,hash(token),mock?"DEMO":"REAL",r.get("revision"),Timestamp.valueOf(LocalDateTime.now().plusMinutes(15)));
            return r;
        });
        if(!mock) {
            try { mail.send(contact.get("email").toString(),"健康提醒邮箱验证","有人将此邮箱设置为健康提醒联系人。如您同意接收提醒，请将以下一次性验证码交由本人在系统内完成验证（15分钟有效）：\n"+token+"\n如非本人意愿，请忽略。此邮件不包含健康档案。"); }
            catch(RuntimeException failure) {
                jdbc.update("DELETE FROM health_contact_verification WHERE contact_id=? AND token_hash=?",id,hash(token));
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"验证邮件未确认发送成功，请稍后重新申请");
            }
        }
        Map<String,Object> out=new LinkedHashMap<>();out.put("mode",mock?"MOCK":"SMTP");out.put("expiresInSeconds",900);if(mock)out.put("demoToken",token);return out;
    }
    public boolean verify(int user,long id,String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{32}")) throw new IllegalArgumentException("验证码无效");
        return Boolean.TRUE.equals(tx.execute(s->{
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? FOR UPDATE",Integer.class,user);
            Map<String,Object> c=contact(user,id);
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM health_contact_verification WHERE contact_id=?",id);
            if(rows.isEmpty()) return false;
            Map<String,Object> v=rows.get(0);
            if(((Number)v.get("attempts")).intValue()>=5 || ((Timestamp)v.get("expires_at")).toLocalDateTime().isBefore(LocalDateTime.now())
                    || !Objects.equals(v.get("contact_revision"),c.get("revision"))) return false;
            if(!MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),v.get("token_hash").toString().getBytes(StandardCharsets.US_ASCII))) {
                jdbc.update("UPDATE health_contact_verification SET attempts=attempts+1 WHERE contact_id=?",id);return false;
            }
            // DEMO always stays DEMO, even if real SMTP is enabled later.
            jdbc.update("UPDATE health_emergency_contact SET email_verification=? WHERE id=? AND user_id=?",v.get("verification_kind"),id,user);
            jdbc.update("DELETE FROM health_contact_verification WHERE contact_id=?",id);return true;
        }));
    }
    private String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
