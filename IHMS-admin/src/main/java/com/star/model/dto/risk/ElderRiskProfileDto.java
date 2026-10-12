
package com.star.model.dto.risk;

import lombok.Data;

@Data
public class ElderRiskProfileDto {
    private Integer age;
    private Integer sexMale;
    private Double bmi;
    private Integer heartDisease;
    private Integer smokingCurrent;
    private Integer drinkingAny;
    private Double drinksPerWeek;
    private Integer physicalActivity;
}
