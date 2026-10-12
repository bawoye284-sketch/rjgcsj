
<template>
  <div class="risk-page">
    <h2>社区老人健康风险评估</h2>
    <el-alert
      title="当前结论综合五项生理指标模型与个人近期变化，并保留实测单项警报。模型参考 NEWS2 数值区间，但不是完整 NEWS2 或医学诊断。"
      type="info" :closable="false" style="margin-bottom:20px;" />

    <el-row :gutter="20">
      <el-col :span="12">
        <el-card>
          <div slot="header"><b>一、老人基础风险档案</b></div>
          <el-form label-width="130px" size="small">
            <el-form-item label="年龄">
              <el-input-number v-model="profile.age" :min="65" :max="100" />
            </el-form-item>
            <el-form-item label="性别">
              <el-radio-group v-model="profile.sexMale">
                <el-radio :label="1">男</el-radio>
                <el-radio :label="0">女</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="BMI">
              <el-input-number v-model="profile.bmi" :min="10" :max="80" :step="0.1" />
            </el-form-item>
            <el-form-item label="心血管病史">
              <el-switch v-model="profile.heartDisease" :active-value="1" :inactive-value="0" />
            </el-form-item>
            <el-form-item label="当前吸烟">
              <el-switch v-model="profile.smokingCurrent" :active-value="1" :inactive-value="0" />
            </el-form-item>
            <el-form-item label="是否饮酒">
              <el-switch v-model="profile.drinkingAny" :active-value="1" :inactive-value="0" />
            </el-form-item>
            <el-form-item label="每周饮酒量">
              <el-input-number v-model="profile.drinksPerWeek" :min="0" :max="100" :step="0.5" />
            </el-form-item>
            <el-form-item label="有身体活动">
              <el-switch v-model="profile.physicalActivity" :active-value="1" :inactive-value="0" />
            </el-form-item>
            <p class="trend-note">当前模型使用年龄、性别和 BMI；病史及生活习惯保存在档案中，不参与本次模型计算。</p>
            <el-button type="primary" @click="saveProfile">保存基础档案</el-button>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card>
          <div slot="header"><b>二、本次健康测量</b></div>
          <el-form label-width="130px" size="small">
            <el-form-item label="收缩压(mmHg)">
              <el-input-number v-model="measurement.systolicPressure" :min="40" :max="260" />
            </el-form-item>
            <el-form-item label="舒张压(mmHg)">
              <el-input-number v-model="measurement.diastolicPressure" :min="20" :max="180" />
            </el-form-item>
            <el-form-item label="心率(次/分)">
              <el-input-number v-model="measurement.heartRate" :min="30" :max="220" />
            </el-form-item>
            <el-form-item label="血氧(%)">
              <el-input-number v-model="measurement.spo2" :min="60" :max="100" :step="0.1" />
            </el-form-item>
            <el-form-item label="体温(℃)">
              <el-input-number v-model="measurement.temperature" :min="32" :max="43" :step="0.1" />
            </el-form-item>
            <el-form-item label="呼吸频率(次/分)">
              <el-input-number v-model="measurement.respiratoryRate" :min="1" :max="80" />
            </el-form-item>
            <p v-if="demoPrefilled" class="trend-note">演示账号已预填模拟测量值，仅供功能演示；记录真实数据时请改成实测值。</p>
            <p v-else class="trend-note">请尽量填写实测值；未测量的指标由模型估算，不能代替实际测量。</p>
            <el-alert v-if="measurement.systolicPressure > 180 || measurement.diastolicPressure > 120"
              title="当前输入的血压处于严重升高范围"
              description="如为真实测量，请重新测量并尽快联系医护人员；若伴胸痛、呼吸困难、视力变化或肢体无力等症状，立即拨打 120。不要依据模型分数判断当前是否安全。"
              type="error" :closable="false" show-icon class="measurement-alert" />
            <el-button type="success" @click="saveMeasurement">保存本次测量</el-button>
            <p class="trend-note">修改测量值后，请先保存，再点击“立即评估”。</p>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-card style="margin-top:20px;">
      <div slot="header">
        <b>三、当前生理状态</b>
        <el-button type="danger" size="small" style="float:right;" :loading="loading" @click="assess">
          立即评估
        </el-button>
      </div>

      <div v-if="result">
        <div class="metric-title">当前风险</div>
        <div class="metric-value">{{ result.riskLevel || '暂无法评估' }}</div>
        <el-alert v-if="result.singleAlerts && result.singleAlerts.length"
          title="单项警报" :description="result.singleAlerts.join('；')"
          type="error" :closable="false" show-icon class="measurement-alert" />
        <p v-if="!result.news2Complete" class="risk-explanation">本次部分指标未测量，模型参考近期实测或训练集估算值；请尽量补测后重新评估。</p>
        <p class="risk-explanation">“正常”仅表示本次未触发系统预警，不等于健康证明。模型由住院重症数据训练，社区老人效果尚未验证。</p>

        <el-divider></el-divider>
        <h3>综合判断的两部分</h3>
        <el-row :gutter="20">
          <el-col :span="12">
            <div class="metric-title">五项综合分未来预警概率</div>
            <div class="metric-value">{{ percent(result.groupRiskProbability) }}</div>
          </el-col>
          <el-col :span="12">
            <div class="metric-title">个人近期变化</div>
            <div class="metric-value">
              {{ result.baselineAvailable ? percent(result.personalChangeScore) : '历史数据不足' }}
            </div>
          </el-col>
        </el-row>
        <p class="risk-explanation">
          模型根据本次五项生理指标及基础资料估计后续五项综合分是否进入预警区间；近期变化比较本次测量与个人历史。两部分共同决定上方结论，实测严重异常还会单独触发警报。
        </p>

        <el-divider></el-divider>
        <h3>背景参考与近期变化</h3>
        <el-tag v-for="(r,i) in (result.reasons || [])" :key="i" style="margin:0 8px 8px 0;">
          {{ r }}
        </el-tag>
        <p style="color:#777;">演示预警模型：{{ result.modelName }}（{{ result.modelVersion }}）</p>
      </div>
      <el-empty v-else description="尚未评估或资料已更新，请点击立即评估"></el-empty>
    </el-card>
    <el-card class="trend-card">
      <div slot="header"><b>四、近30天测量趋势</b></div>
      <div v-if="history.length" class="trend-grid">
        <div v-for="metric in trendMetrics" :key="metric.key" class="trend-item">
          <div class="trend-title">{{ metric.name }} <span>{{ metric.unit }}</span></div>
          <svg v-if="trendPoints(metric.key).length >= 2" viewBox="0 0 300 110"
               preserveAspectRatio="none" class="trend-plot" :aria-label="metric.name + '趋势图'" role="img">
            <line x1="10" y1="94" x2="290" y2="94" stroke="#e4eaf0" />
            <polyline :points="trendLine(metric.key)" fill="none" stroke="#38b781"
                      stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
            <g v-for="(point, index) in trendPoints(metric.key)" :key="index">
              <circle :cx="point.x" :cy="point.y" r="4" fill="#38b781" />
              <text :x="point.x" :y="point.y - 9" text-anchor="middle" font-size="10" fill="#44515b">{{ point.value }}</text>
              <title>{{ point.time }}：{{ point.value }} {{ metric.unit }}</title>
            </g>
          </svg>
          <div v-else class="trend-empty">至少需要两次记录才能显示趋势</div>
          <div v-if="trendPoints(metric.key).length >= 2" class="trend-time">
            <span>{{ trendStart(metric.key) }}</span><span>{{ trendEnd(metric.key) }}</span>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无历史测量；保存记录后会显示趋势"></el-empty>
      <p class="trend-note">演示账号的初始历史记录为模拟数据；后续保存的测量会加入曲线。</p>
    </el-card>
  </div>
</template>

<script>
export default {
  data() {
    return {
      loading: false,
      demoPrefilled: false,
      profile: {
        age: 65, sexMale: 0, bmi: 23,
        heartDisease: 0, smokingCurrent: 0,
        drinkingAny: 0, drinksPerWeek: 0, physicalActivity: 1
      },
      measurement: {
        systolicPressure: undefined, diastolicPressure: undefined,
        heartRate: undefined, spo2: undefined, temperature: undefined,
        respiratoryRate: undefined
      },
      result: null,
      history: [],
      trendMetrics: [
        { key: 'systolicPressure', name: '收缩压', unit: 'mmHg' },
        { key: 'diastolicPressure', name: '舒张压', unit: 'mmHg' },
        { key: 'heartRate', name: '心率', unit: '次/分' },
        { key: 'respiratoryRate', name: '呼吸频率', unit: '次/分' },
        { key: 'spo2', name: '血氧', unit: '%' },
        { key: 'temperature', name: '体温', unit: '℃' }
      ]
    }
  },
  created() {
    this.loadDemoDefaults()
    this.loadProfile()
    this.loadLatest()
    this.loadHistory()
  },
  methods: {
    async loadDemoDefaults() {
      try {
        const res = await this.$axios.get('user/auth')
        const account = res.data && res.data.data && res.data.data.userAccount
        if (account === 'demo_elder' && Object.values(this.measurement).every(value => value === undefined)) {
          this.measurement = {
            systolicPressure: 130, diastolicPressure: 80,
            heartRate: 76, spo2: 98, temperature: 36.5,
            respiratoryRate: 16
          }
          this.demoPrefilled = true
        }
      } catch (e) {
        // Keep the form empty for users whose account cannot be verified.
      }
    },
    percent(v) {
      if (v === null || v === undefined) return '-'
      return (Number(v) * 100).toFixed(1) + '%'
    },
    async loadHistory() {
      try {
        const res = await this.$axios.get('/personalized-risk/history?days=30')
        this.history = (res.data && res.data.data) || []
      } catch (e) {
        this.history = []
      }
    },
    metricRecords(key) {
      return this.history
        .filter(row => row[key] !== null && row[key] !== undefined && Number.isFinite(Number(row[key])))
        .slice(-12)
    },
    trendPoints(key) {
      const records = this.metricRecords(key)
      if (!records.length) return []
      const values = records.map(row => Number(row[key]))
      const low = Math.min(...values)
      const high = Math.max(...values)
      const range = high - low
      return records.map((row, index) => ({
        x: records.length === 1 ? 150 : 14 + index * 272 / (records.length - 1),
        y: range === 0 ? 55 : 85 - (Number(row[key]) - low) * 60 / range,
        value: Number(row[key]),
        time: row.measuredAt
      }))
    },
    trendLine(key) {
      return this.trendPoints(key).map(point => `${point.x},${point.y}`).join(' ')
    },
    trendStart(key) {
      const records = this.metricRecords(key)
      return records.length ? String(records[0].measuredAt).slice(5, 16) : ''
    },
    trendEnd(key) {
      const records = this.metricRecords(key)
      return records.length ? String(records[records.length - 1].measuredAt).slice(5, 16) : ''
    },
    async loadProfile() {
      const res = await this.$axios.get('/personalized-risk/profile')
      if (res.data && res.data.data) {
        this.profile = Object.assign({}, this.profile, res.data.data)
      }
    },
    async loadLatest() {
      const res = await this.$axios.get('/personalized-risk/latest')
      const d = res.data && res.data.data
      if (d && d.assessmentBasis === 'NUMERIC5_AGGREGATE_HISTORY_V1') {
        this.result = {
          groupRiskProbability: d.groupRiskProbability,
          personalChangeScore: d.personalChangeScore,
          baselineAvailable: Number(d.baselineAvailable) === 1,
          riskLevel: d.riskLevel,
          news2Complete: Number(d.news2Complete) === 1,
          singleAlerts: d.singleAlerts || [],
          reasons: d.reasonsJson ? JSON.parse(d.reasonsJson) : [],
          modelName: d.modelName,
          modelVersion: d.modelVersion
        }
      }
    },
    async saveProfile() {
      const res = await this.$axios.post('/personalized-risk/profile', this.profile)
      if (res.data.code === 200) {
        this.result = null
        this.$message.success('基础档案已保存')
      }
    },
    async saveMeasurement() {
      const res = await this.$axios.post('/personalized-risk/measurement', this.measurement)
      if (res.data.code === 200) {
        this.result = null
        this.$message.success('本次测量已保存')
        await this.loadHistory()
      }
    },
    async assess() {
      this.loading = true
      try {
        await this.saveProfile()
        const res = await this.$axios.post('/personalized-risk/assess')
        this.result = res.data.data
        this.$message.success('风险评估完成')
      } catch (e) {
        this.$message.error('评估失败，请确认 Python 风险服务已启动且数据库升级脚本已执行')
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.risk-page { padding: 24px; }
.metric-title { color:#777; margin-bottom:10px; }
.metric-value { font-size:28px; font-weight:bold; }
.risk-explanation { margin: 20px 0 0; color: #667785; line-height: 1.7; }
.trend-card { margin-top: 20px; }
.trend-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; }
.trend-item { padding: 16px; border: 1px solid #e8edf0; border-radius: 8px; background: #fbfdfc; }
.trend-title { font-weight: 600; color: #263b32; }
.trend-title span { font-size: 12px; color: #84938b; font-weight: 400; }
.trend-plot { display: block; width: 100%; height: 120px; margin-top: 12px; overflow: visible; }
.trend-time { display: flex; justify-content: space-between; color: #84938b; font-size: 11px; }
.trend-empty { padding: 45px 0; color: #9aa9a0; text-align: center; font-size: 12px; }
.trend-note { margin: 16px 0 0; color: #87958d; font-size: 12px; }
.measurement-alert { margin-bottom: 16px; }
</style>
