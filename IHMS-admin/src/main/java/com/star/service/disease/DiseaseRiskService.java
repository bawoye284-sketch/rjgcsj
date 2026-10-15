package com.star.service.disease;

import com.star.mapper.DiseaseRiskRepository;
import com.star.model.dto.disease.DiseaseAssessmentRequest;
import com.star.model.dto.disease.DiseaseProfileRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class DiseaseRiskService {
    private final DiseaseRiskRepository repository;
    private final DiseaseRiskModelClient model;
    public DiseaseRiskService(DiseaseRiskRepository repository, DiseaseRiskModelClient model) {
        this.repository = repository; this.model = model;
    }
    public Map<String, Object> profile(int user) { return repository.profile(user); }
    public Map<String, Object> save(int user, DiseaseProfileRequest request) {
        DiseaseRiskContract.validate(request);
        try { return repository.saveProfile(user, request); }
        catch (DuplicateKeyException conflict) { throw new ResponseStatusException(HttpStatus.CONFLICT, "档案已被其他请求创建，请刷新"); }
    }
    public Map<String, Object> models() { return model.models(); }
    public Map<String, Object> latest(int user) { return repository.latest(user); }
    public Map<String, Object> history(int user, int page, int size) { return repository.history(user, page, size); }
    public Map<String, Object> detail(int user, String id) { return repository.assessment(user, id); }

    public Map<String, Object> assess(int user, DiseaseAssessmentRequest request) {
        if (request == null || request.getProfileRevision() == null || request.getProfileRevision() < 1
                || request.getRequestId() == null || !request.getRequestId().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("请提供已保存的档案版本和有效requestId");
        Map<String, Object> existing = repository.byRequest(user, request.getRequestId());
        if (existing != null) return sameRequest(existing, request);
        Map<String, Object> snapshot = repository.profile(user);
        if (((Number) snapshot.get("revision")).longValue() != request.getProfileRevision())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "档案版本已变化，请刷新后评估");
        Map<String, Object> metadata = model.models();
        Map<String, Object> response = model.predict(DiseaseRiskContract.pythonRequest(snapshot));
        if (!Objects.equals(metadata.get("model_version"), response.get("model_version")))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "评估期间模型版本发生变化，请重试");
        String hash;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(DiseaseRiskRepository.json(snapshot).getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(); for (byte b : bytes) encoded.append(String.format("%02x", b & 0xff)); hash = encoded.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        try { return repository.saveAssessment(user, request.getRequestId(), request.getProfileRevision(), hash, snapshot, metadata, response); }
        catch (DuplicateKeyException duplicate) {
            Map<String, Object> saved = repository.byRequest(user, request.getRequestId());
            if (saved == null) throw duplicate;
            return sameRequest(saved, request);
        }
    }
    private Map<String, Object> sameRequest(Map<String, Object> existing, DiseaseAssessmentRequest request) {
        if (((Number) existing.get("profileRevision")).longValue() != request.getProfileRevision())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该requestId已用于另一份资料，请重新发起评估");
        return existing;
    }
}
