-- Adult resting references, not emergency criteria. Preserve administrator customizations.
-- https://medlineplus.gov/ency/article/002341.htm
CREATE TABLE IF NOT EXISTS health_reference_activation (
 metric_key VARCHAR(40) NOT NULL PRIMARY KEY,
 first_measurement_id BIGINT NOT NULL
);
INSERT INTO health_reference_activation (metric_key,first_measurement_id)
SELECT 'temperature',(SELECT COALESCE(MAX(id),0)+1 FROM elder_vital_measurement)
WHERE NOT EXISTS (SELECT 1 FROM health_reference_activation WHERE metric_key='temperature')
AND (NOT EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='temp' AND value_range IS NOT NULL AND TRIM(value_range)<>'')
 OR EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='temp' AND detail LIKE '成人静息参考%'));
INSERT INTO health_reference_activation (metric_key,first_measurement_id)
SELECT 'respiratory_rate',(SELECT COALESCE(MAX(id),0)+1 FROM elder_vital_measurement)
WHERE NOT EXISTS (SELECT 1 FROM health_reference_activation WHERE metric_key='respiratory_rate')
AND (NOT EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='rr' AND value_range IS NOT NULL AND TRIM(value_range)<>'')
 OR EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='rr' AND detail LIKE '成人静息参考%'));
INSERT INTO health_model_config (name,detail,unit,symbol,is_global,value_range)
SELECT '体温','成人静息参考；需结合个体基线及测量部位，不作为急症判定','℃','temp',1,'36.5,37.3'
WHERE NOT EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='temp');
INSERT INTO health_model_config (name,detail,unit,symbol,is_global,value_range)
SELECT '呼吸频率','成人静息参考；需结合个体情况，不作为急症判定','次/分','rr',1,'12,18'
WHERE NOT EXISTS (SELECT 1 FROM health_model_config WHERE is_global=1 AND LOWER(symbol)='rr');
UPDATE health_model_config SET value_range='36.5,37.3' WHERE is_global=1 AND LOWER(symbol)='temp' AND (value_range IS NULL OR TRIM(value_range)='');
UPDATE health_model_config SET value_range='12,18' WHERE is_global=1 AND LOWER(symbol)='rr' AND (value_range IS NULL OR TRIM(value_range)='');
