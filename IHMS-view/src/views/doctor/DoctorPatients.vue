<template>
  <div class="doctor-patients">
    <header class="page-header">
      <div>
        <h1>患者身体数据</h1>
        <p>按患者查看与我有咨询记录的人员及其健康测量。</p>
      </div>
      <el-button icon="el-icon-back" @click="$router.push('/doctor-home')">返回工作台</el-button>
    </header>

    <div class="page-grid">
      <el-card class="patient-panel" v-loading="listLoading">
        <div slot="header">我的患者（{{ patients.length }}）</div>
        <el-input v-model="search" size="small" clearable prefix-icon="el-icon-search"
                  placeholder="按姓名或账号查找" />
        <div v-if="filteredPatients.length" class="patient-list">
          <button v-for="patient in filteredPatients" :key="patient.patientId" type="button"
                  class="patient-item" :class="{ active: selectedId === patient.patientId }"
                  @click="selectPatient(patient.patientId)">
            <span class="patient-name">{{ patient.patientName || '未填写姓名' }}</span>
            <span class="patient-account">{{ patient.account }}</span>
            <span class="patient-date">最近咨询：{{ patient.lastConsultationAt || '暂无时间' }}</span>
          </button>
        </div>
        <el-empty v-else :description="patients.length ? '没有匹配的患者' : '暂无咨询患者'" />
      </el-card>

      <div class="detail-panel" v-loading="detailLoading">
        <template v-if="detail">
          <el-card class="detail-card">
            <div slot="header" class="detail-heading">
              <div>
                <strong>{{ detail.patient.patientName || '未填写姓名' }}</strong>
                <span class="account">{{ detail.patient.account }}</span>
              </div>
              <el-select v-model="days" size="small" @change="selectPatient(selectedId)">
                <el-option :value="7" label="近7天" />
                <el-option :value="30" label="近30天" />
                <el-option :value="90" label="近90天" />
              </el-select>
            </div>
            <div v-if="latestVital" class="vital-grid">
              <div v-for="metric in metrics" :key="metric.key" class="vital-item">
                <span>{{ metric.name }}</span>
                <strong>{{ displayValue(latestVital[metric.key]) }}</strong>
                <small>{{ metric.unit }}</small>
              </div>
            </div>
            <el-empty v-else description="该患者在所选时间内没有基础测量记录" />
            <p v-if="latestVital" class="last-time">最近测量：{{ latestVital.measuredAt }}</p>
            <el-alert v-if="latestVital && (latestVital.systolicPressure > 180 || latestVital.diastolicPressure > 120)"
                      title="最近一次血压处于严重升高范围，请结合实际情况及时核实。"
                      type="warning" :closable="false" show-icon />
          </el-card>

          <el-card v-if="detail.latestRisk" class="detail-card">
            <div slot="header"><strong>最近一次生理状态评估</strong></div>
            <div class="risk-summary">
              <span>当前风险：<b>{{ detail.latestRisk.riskLevel }}</b></span>
              <span>五项综合分未来预警概率：<b>{{ percent(detail.latestRisk.groupRiskProbability) }}</b></span>
              <span>个人近期变化：<b>{{ percent(detail.latestRisk.personalChangeScore) }}</b></span>
              <span>评估时间：{{ detail.latestRisk.assessmentAt }}</span>
            </div>
            <el-alert v-if="detail.latestRisk.singleAlerts && detail.latestRisk.singleAlerts.length"
                      title="单项警报" :description="detail.latestRisk.singleAlerts.join('；')"
                      type="error" :closable="false" show-icon />
          </el-card>

          <el-card class="detail-card">
            <div slot="header"><strong>身体指标趋势</strong></div>
            <el-radio-group v-model="selectedMetric" size="small" class="metric-switch">
              <el-radio-button v-for="metric in metrics" :key="metric.key" :label="metric.key">
                {{ metric.name }}
              </el-radio-button>
            </el-radio-group>
            <div v-if="trendPoints.length >= 2" class="trend-wrap">
              <svg viewBox="0 0 560 150" preserveAspectRatio="none" role="img"
                   :aria-label="selectedMetricName + '测量趋势'" class="trend-chart">
                <line x1="22" y1="126" x2="538" y2="126" stroke="#d9e4dd" />
                <polyline :points="trendLine" fill="none" stroke="#38b781" stroke-width="3"
                          stroke-linecap="round" stroke-linejoin="round" />
                <g v-for="(point, index) in trendPoints" :key="index">
                  <circle :cx="point.x" :cy="point.y" r="5" fill="#38b781" />
                  <text :x="point.x" :y="point.y - 10" text-anchor="middle" font-size="11" fill="#354b40">
                    {{ point.value }}
                  </text>
                  <title>{{ point.time }}：{{ point.value }}</title>
                </g>
              </svg>
              <div class="trend-dates"><span>{{ trendStart }}</span><span>{{ trendEnd }}</span></div>
            </div>
            <el-empty v-else description="至少两次测量后才会显示曲线" />
          </el-card>

          <el-card class="detail-card">
            <div slot="header"><strong>测量明细</strong></div>
            <el-table :data="detail.vitals" stripe height="280" empty-text="暂无测量记录">
              <el-table-column prop="measuredAt" label="测量时间" min-width="170" />
              <el-table-column prop="systolicPressure" label="收缩压" min-width="95" />
              <el-table-column prop="diastolicPressure" label="舒张压" min-width="95" />
              <el-table-column prop="heartRate" label="心率" min-width="80" />
              <el-table-column prop="respiratoryRate" label="呼吸频率" min-width="100" />
              <el-table-column prop="spo2" label="血氧" min-width="80" />
              <el-table-column prop="temperature" label="体温" min-width="80" />
            </el-table>
          </el-card>

          <el-card class="detail-card">
            <div slot="header"><strong>日常健康记录</strong></div>
            <el-table :data="detail.healthRecords" stripe height="280" empty-text="暂无健康记录">
              <el-table-column prop="recordedAt" label="记录时间" min-width="170" />
              <el-table-column prop="name" label="指标" min-width="130" />
              <el-table-column label="数值" min-width="130">
                <template slot-scope="scope">{{ scope.row.value }} {{ scope.row.unit || '' }}</template>
              </el-table-column>
            </el-table>
          </el-card>
        </template>
        <el-card v-else><el-empty description="请从左侧选择患者" /></el-card>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'DoctorPatients',
  data() {
    return {
      patients: [],
      selectedId: null,
      detail: null,
      search: '',
      days: 30,
      selectedMetric: 'systolicPressure',
      listLoading: false,
      detailLoading: false,
      metrics: [
        { key: 'systolicPressure', name: '收缩压', unit: 'mmHg' },
        { key: 'diastolicPressure', name: '舒张压', unit: 'mmHg' },
        { key: 'heartRate', name: '心率', unit: '次/分' },
        { key: 'respiratoryRate', name: '呼吸频率', unit: '次/分' },
        { key: 'spo2', name: '血氧', unit: '%' },
        { key: 'temperature', name: '体温', unit: '℃' }
      ]
    }
  },
  computed: {
    filteredPatients() {
      const keyword = this.search.trim().toLowerCase()
      return this.patients.filter(patient =>
        !keyword || String(patient.patientName || '').toLowerCase().includes(keyword) ||
        String(patient.account || '').toLowerCase().includes(keyword))
    },
    latestVital() {
      return this.detail && this.detail.vitals.length ? this.detail.vitals[0] : null
    },
    selectedMetricName() {
      const metric = this.metrics.find(item => item.key === this.selectedMetric)
      return metric ? metric.name : ''
    },
    trendPoints() {
      const rows = this.detail ? this.detail.vitals.slice().reverse()
        .filter(row => row[this.selectedMetric] !== null && row[this.selectedMetric] !== undefined)
        .slice(-12) : []
      if (!rows.length) return []
      const values = rows.map(row => Number(row[this.selectedMetric]))
      const low = Math.min(...values)
      const range = Math.max(...values) - low
      return rows.map((row, index) => ({
        x: rows.length === 1 ? 280 : 24 + index * 512 / (rows.length - 1),
        y: range === 0 ? 76 : 115 - (Number(row[this.selectedMetric]) - low) * 90 / range,
        value: Number(row[this.selectedMetric]),
        time: row.measuredAt
      }))
    },
    trendLine() {
      return this.trendPoints.map(point => `${point.x},${point.y}`).join(' ')
    },
    trendStart() {
      return this.trendPoints.length ? this.trendPoints[0].time : ''
    },
    trendEnd() {
      return this.trendPoints.length ? this.trendPoints[this.trendPoints.length - 1].time : ''
    }
  },
  created() {
    this.loadPatients()
  },
  methods: {
    displayValue(value) {
      return value === null || value === undefined ? '-' : Number(value)
    },
    percent(value) {
      return value === null || value === undefined ? '-' : (Number(value) * 100).toFixed(1) + '%'
    },
    async loadPatients() {
      this.listLoading = true
      try {
        const response = await this.$axios.get('/doctor-patients')
        if (response.data.code !== 200) throw new Error(response.data.msg)
        this.patients = response.data.data || []
        const requested = Number(this.$route.query.patientId)
        const first = this.patients.find(patient => patient.patientId === requested) || this.patients[0]
        if (first) await this.selectPatient(first.patientId)
      } catch (error) {
        this.$message.error('加载患者列表失败，请确认已用医生账号登录')
      } finally {
        this.listLoading = false
      }
    },
    async selectPatient(patientId) {
      if (!patientId) return
      this.selectedId = patientId
      this.detailLoading = true
      try {
        const response = await this.$axios.get(`/doctor-patients/${patientId}/health`, {
          params: { days: this.days }
        })
        if (response.data.code !== 200) throw new Error(response.data.msg)
        this.detail = response.data.data
      } catch (error) {
        this.detail = null
        this.$message.error('加载该患者的身体数据失败')
      } finally {
        this.detailLoading = false
      }
    }
  }
}
</script>

<style scoped>
.doctor-patients { padding: 24px; min-height: 100vh; background: #f6f8f7; }
.page-header { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 22px; }
.page-header h1 { margin: 0 0 6px; font-size: 25px; }
.page-header p { margin: 0; color: #819087; }
.page-grid { display: grid; grid-template-columns: minmax(240px, 300px) minmax(0, 1fr); gap: 18px; align-items: start; }
.patient-list { margin-top: 14px; max-height: 75vh; overflow-y: auto; }
.patient-item { display: flex; flex-direction: column; align-items: flex-start; width: 100%; margin-bottom: 8px; padding: 12px; border: 1px solid #e6ece8; border-radius: 8px; background: #fff; text-align: left; cursor: pointer; }
.patient-item:hover, .patient-item.active { border-color: #38b781; background: #f0faf5; }
.patient-name { font-weight: 600; color: #273c31; }
.patient-account, .patient-date { color: #83928a; font-size: 12px; margin-top: 3px; }
.detail-card { margin-bottom: 18px; }
.detail-heading { display: flex; justify-content: space-between; align-items: center; }
.detail-heading strong { font-size: 18px; }
.account { margin-left: 12px; color: #88968f; font-size: 13px; }
.vital-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(125px, 1fr)); gap: 10px; }
.vital-item { background: #f3faf6; border-radius: 8px; padding: 14px; }
.vital-item span { display: block; color: #678075; font-size: 12px; }
.vital-item strong { font-size: 24px; margin-right: 4px; }
.vital-item small { color: #88968f; }
.last-time { color: #87958d; font-size: 12px; }
.risk-summary { display: flex; flex-wrap: wrap; gap: 12px 26px; color: #5a6d62; }
.risk-summary b { color: #273c31; }
.metric-switch { margin-bottom: 12px; }
.trend-wrap { padding: 8px 0 0; }
.trend-chart { width: 100%; height: 190px; overflow: visible; }
.trend-dates { display: flex; justify-content: space-between; color: #87958d; font-size: 12px; }
@media (max-width: 850px) {
  .doctor-patients { padding: 14px; }
  .page-header { align-items: flex-start; }
  .page-grid { grid-template-columns: 1fr; }
  .patient-list { max-height: 240px; }
}
</style>
