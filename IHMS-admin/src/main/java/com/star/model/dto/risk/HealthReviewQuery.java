package com.star.model.dto.risk;

import lombok.Data;

@Data
public class HealthReviewQuery {
    private Integer userId;
    private String keyword;
    private String kind;
    private String diseaseCode;
    private String status;
    private String from;
    private String to;
    private int page = 1;
    private int pageSize = 10;
}
