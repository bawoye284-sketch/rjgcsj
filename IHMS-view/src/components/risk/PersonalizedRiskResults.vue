<template>
  <section class="personal-results">
    <div class="result-heading"><h2>{{ readonly ? '个性化生理风险评估' : '四、个性化生理风险评估' }}</h2><el-tag :type="state === 'error' ? 'danger' : result || state === 'success' ? 'success' : 'info'">{{ stateLabel }}</el-tag></div>
    <el-alert v-if="message" :closable="false" show-icon :type="state === 'error' ? 'error' : 'info'" :title="message" />
    <el-alert v-if="result && stale" :closable="false" type="warning" title="以下为上次保存的个性化结果，当前汇总表尚未完成新的个性化评估。" />
    <el-card shadow="never" class="result-card" v-if="result">
      <div class="metrics"><div><div class="metric-title">当前风险</div><div class="metric-value">{{ result.riskLevel || '暂无法评估' }}</div></div>
        <div><div class="metric-title">五项综合分未来预警概率</div><div class="metric-value">{{ percent(result.groupRiskProbability) }}</div></div>
        <div><div class="metric-title">个人近期变化</div><div class="metric-value">{{ result.baselineAvailable ? percent(result.personalChangeScore) : '历史数据不足' }}</div></div></div>
      <el-alert v-if="result.singleAlerts && result.singleAlerts.length" title="实测单项警报" :description="result.singleAlerts.join('；')" type="error" :closable="false" show-icon />
      <p v-if="!result.news2Complete" class="note">部分指标未测，模型参考近期实测或训练集估算值；请尽量补测后重新评估。</p>
      <p class="note">当前结论综合五项生理指标模型、个人近期变化与实测单项警报。模型由住院重症数据训练，社区老人效果尚未验证；“正常”仅表示本次未触发系统预警。</p>
      <el-tag v-for="(reason,index) in (result.reasons || [])" :key="index" class="reason">{{ reason }}</el-tag>
      <p class="note">{{ result.modelName }} · {{ result.modelVersion }}<span v-if="assessedAt"> · {{ assessedAt }}</span></p>
    </el-card>
    <el-empty v-else :description="readonly ? '暂无已保存的个性化评估' : '填写BMI与本次测量后，点击上方“保存并联合预测”'" />
    <el-card v-if="showTrend" shadow="never" class="trend-card">
      <div slot="header"><b>近{{ trendDays }}天实测趋势</b></div>
      <div v-if="history.length" class="trend-grid">
        <div v-for="metric in metrics" :key="metric.key" class="trend-item">
          <div>{{ metric.name }} <span class="note">{{ metric.unit }}</span></div>
          <svg v-if="points(metric.key).length >= 2" viewBox="0 0 300 110" preserveAspectRatio="none" class="trend-plot" :aria-label="metric.name + '趋势图'" role="img">
            <line x1="10" y1="94" x2="290" y2="94" stroke="#e4eaf0" />
            <polyline :points="points(metric.key).map(p => `${p.x},${p.y}`).join(' ')" fill="none" stroke="#38b781" stroke-width="3" />
            <g v-for="(p,i) in points(metric.key)" :key="i"><circle :cx="p.x" :cy="p.y" r="4" fill="#38b781" /><text :x="p.x" :y="p.y-9" text-anchor="middle" font-size="10" fill="#44515b">{{ p.value }}</text><title>{{ p.time }}：{{ p.value }} {{ metric.unit }}</title></g>
          </svg>
          <div v-else class="trend-empty">至少需要两次记录才能显示趋势</div>
          <div v-if="points(metric.key).length >= 2" class="trend-time"><span>{{ edgeTime(metric.key, false) }}</span><span>{{ edgeTime(metric.key, true) }}</span></div>
        </div>
      </div>
      <el-empty v-else description="暂无实测历史，完成个性化评估后会保存本次测量" />
    </el-card>
  </section>
</template>

<script>
export default {
  name: 'PersonalizedRiskResults',
  props: { result: { default: null }, history: { type: Array, default: () => [] }, state: { type: String, default: 'idle' }, message: { type: String, default: '' }, stale: Boolean, assessedAt: { type: String, default: '' },showTrend:{type:Boolean,default:true},readonly:Boolean,trendDays:{type:Number,default:30} },
  data() { return { metrics: [{ key: 'systolicPressure', name: '收缩压', unit: 'mmHg' }, { key: 'diastolicPressure', name: '舒张压', unit: 'mmHg' }, { key: 'heartRate', name: '心率', unit: '次/分' }, { key: 'respiratoryRate', name: '呼吸频率', unit: '次/分' }, { key: 'spo2', name: '血氧', unit: '%' }, { key: 'temperature', name: '体温', unit: '℃' }] } },
  computed: { stateLabel() { if(this.readonly) return this.result ? '已保存结果' : '暂无评估'; return { idle: '待联合评估', running: '评估中', success: '本次已完成', error: '本次未完成', skipped: '本次未运行' }[this.state] } },
  methods: {
    percent(value) { return value == null ? '—' : (Number(value)*100).toFixed(2) + '%' },
    records(key) { return this.history.filter(r => r[key] != null && Number.isFinite(Number(r[key]))).slice(-12) },
    points(key) {
      const records = this.records(key), values = records.map(r => Number(r[key])); if (!records.length) return []
      const low = Math.min(...values), range = Math.max(...values)-low
      return records.map((r,i) => ({ x: records.length === 1 ? 150 : 14+i*272/(records.length-1), y: range === 0 ? 55 : 85-(Number(r[key])-low)*60/range, value: Number(r[key]), time: r.measuredAt }))
    },
    edgeTime(key, last) { const records = this.records(key); return records.length ? String(records[last ? records.length-1 : 0].measuredAt).slice(5,16) : '' }
  }
}
</script>

<style scoped>
.personal-results { margin-top: 30px; }.result-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 18px; }h2 { font-size: 19px; margin: 0; }.result-card,.trend-card { margin-top: 18px; border-radius: 10px; }.metrics { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 20px; margin-bottom: 24px; }.metric-title,.note { font-size: 12px; color: #73838c; line-height: 1.8; }.metric-value { font-size: 25px; color: #315d69; margin-top: 10px; }.reason { margin: 0 8px 8px 0; max-width: 100%; height: auto; white-space: normal; }.trend-grid { display: grid; grid-template-columns: repeat(auto-fit,minmax(250px,1fr)); gap: 16px; }.trend-item { padding: 16px; border: 1px solid #e8edf0; border-radius: 8px; }.trend-plot { display: block; width: 100%; height: 120px; margin-top: 12px; overflow: visible; }.trend-empty { padding: 45px 0; color: #9aa9a0; font-size: 12px; text-align: center; }.trend-time { display: flex; justify-content: space-between; font-size: 11px; color: #84938b; }@media(max-width:600px) { .metrics { grid-template-columns: 1fr; }.trend-grid { grid-template-columns: minmax(0,1fr); } }
</style>
