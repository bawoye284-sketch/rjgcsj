export const diseases = [{ code: 'hypertension', name: '高血压' }, { code: 'diabetes', name: '糖尿病' }, { code: 'stroke', name: '卒中 / 中风' }, { code: 'heart_attack', name: '心脏病发作' }, { code: 'osteoporosis', name: '骨质疏松' }]
export const profileLabels = { age: '年龄', sexMale: '性别', bmi: 'BMI', weightKg: '体重（kg）', selfRatedHealth: '自评健康', smokingCurrent: '当前吸烟', drinkingAny: '过去一年饮酒', exerciseRegular: '规律运动', highCholesterolHistory: '高胆固醇史', healthWorsened: '过去一年健康变差', weightGain10lb: '过去一年增重至少约4.54kg', weightLoss10lb: '过去一年减重至少约4.54kg', hipFractureHistory: '髋部骨折史' }
export const measurementLabels = { systolicPressure: '收缩压（mmHg）', diastolicPressure: '舒张压（mmHg）', heartRate: '心率（次/分）', spo2: '血氧（%）', temperature: '体温（℃）', respiratoryRate: '呼吸频率（次/分）' }
export const percent = value => value == null ? '—' : (Number(value)*100).toFixed(2) + '%'
export const statusName = status => ({ completed: '评估完成', completed_with_skips: '含未评估项目', predicted: '预测完成', already_diagnosed: '已有病史', insufficient_data: '资料不足', not_applicable: '不适用', model_unavailable: '模型不可用' }[status] || status)
export const diseaseName = code => (diseases.find(d => d.code === code) || {}).name || code
export function profileValue(key, value) {
  if (value == null) return '不清楚 / 未填写'
  if (key === 'sexMale') return Number(value) === 1 ? '男' : '女'
  if (key === 'selfRatedHealth') return ['极好','很好','好','一般','差'][Number(value)-1] || value
  if (['age','weightKg','bmi'].includes(key)) return value
  return Number(value) === 1 ? '是' : '否'
}
export function snapshotFields(snapshot) {
  if (!snapshot) return []
  const rows = Object.entries(snapshot.profile || {}).filter(([key]) => profileLabels[key]).map(([key,value]) => ({ key, label: profileLabels[key], value: profileValue(key,value) }))
  diseases.forEach(d => {
    if (snapshot.diseaseHistory) rows.push({ key:d.code, label:d.name+'病史', value: snapshot.diseaseHistory[d.code] == null ? '不清楚' : Number(snapshot.diseaseHistory[d.code]) === 1 ? '有' : '无' })
    const year=(snapshot.diagnosisYears || {})[d.code]
    if (year!=null) rows.push({ key:d.code+'-year',label:d.name+'确诊年份',value:year })
  })
  Object.entries(snapshot.measurement || {}).forEach(([key,value]) => { if (measurementLabels[key]) rows.push({ key,label:measurementLabels[key],value:value==null?'未测量':value }) })
  if (snapshot.measuredAt) rows.push({key:'measuredAt',label:'本次测量时间',value:snapshot.measuredAt})
  return rows
}
