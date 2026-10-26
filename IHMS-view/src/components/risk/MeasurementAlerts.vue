<template>
  <el-card class="measurement-alerts" shadow="never">
    <div slot="header" class="alert-heading"><strong>健康测量预警</strong><el-button type="text" :loading="loading" @click="load(measurementId)">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="current">
      <p>测量时间：{{ formatTime(current.measuredAt) }} · {{ statusText }}</p>
      <p v-if="current.status === 'PENDING'">检测中或等待前序测量重试，测量已保存。</p>
      <p v-if="current.blockingMeasurementId">此前一次测量检测未完成。<el-button type="text" :loading="loading" @click="retry">重试前序检测</el-button></p>
      <p v-if="current.status === 'FAILED'">本次测量已保存，预警检测暂未完成，不能据此判断是否正常。<el-button type="text" :loading="loading" @click="retry">重试检测</el-button></p>
      <template v-if="current.result">
        <h3 :style="alertStyle(current.result.outcome === 'ALERT' ? current.result.level : 'NONE')">{{ summary.title }}</h3>
        <p v-if="current.result.outcome === 'ALERT'" :style="alertStyle(current.result.level)">{{ level(current.result.level) }}{{ current.result.level === 'REPEATED' ? ' · 需要关注' : '' }}</p>
        <p v-for="note in summary.notes" :key="note">{{ note }}</p>
        <el-table :data="current.result.items" size="small" style="width:100%">
          <el-table-column label="指标" min-width="80"><template slot-scope="s"><span :style="alertStyle(s.row.state === 'ALERT' ? s.row.level : 'NONE')">{{ s.row.name }}</span></template></el-table-column>
          <el-table-column label="本次测量值" min-width="100"><template slot-scope="s">{{ measurementValue(s.row) }}</template></el-table-column>
          <el-table-column label="当前参考范围" min-width="120"><template slot-scope="s">{{ measurementRange(s.row) }}</template></el-table-column>
          <el-table-column label="检查与原因" min-width="240"><template slot-scope="s">
            <div v-for="reason in reasons(s.row)" :key="reason">{{ reason }}</div>
          </template></el-table-column>
        </el-table>
      </template>
    </template>
    <p v-else-if="!loading && !error">尚无新增测量的规则检测记录。原有历史数据不会自动补报。</p>
    <el-collapse class="history" @change="loadHistory"><el-collapse-item title="历史预警记录" name="history">
      <el-table :data="history.items" size="small" style="width:100%">
        <el-table-column label="指标" min-width="85"><template slot-scope="s">{{ s.row.details.name }}</template></el-table-column>
        <el-table-column label="预警等级" min-width="120"><template slot-scope="s"><span :style="eventStyle(s.row)">{{ level(s.row.level) }}</span></template></el-table-column>
        <el-table-column label="风险状态" min-width="110"><template slot-scope="s">{{ s.row.status === 'ACTIVE' ? '异常持续中' : s.row.status === 'RECOVERED' ? '测量已恢复' : '状态暂不明确' }}</template></el-table-column>
        <el-table-column prop="occurrences" label="异常次数" min-width="80" />
        <el-table-column label="最近更新" min-width="150"><template slot-scope="s">{{ formatTime(s.row.updatedAt) }}</template></el-table-column>
        <el-table-column label="最近原因" min-width="220"><template slot-scope="s"><div v-for="reason in reasons(s.row.details)" :key="reason">{{ reason }}</div></template></el-table-column>
      </el-table>
      <el-pagination :current-page.sync="page" :page-size="10" :total="history.total" layout="prev, pager, next" @current-change="loadHistory" />
    </el-collapse-item></el-collapse>
  </el-card>
</template>
<script>
import api from '@/api/healthAlerts'
import { alertLevel, alertStyle, eventStyle, formatTime, measurementValue, measurementRange, measurementReasons, measurementSummary, displayError } from '@/utils/healthAlertDisplay'
export default {
  data: () => ({ current: null, measurementId: null, history: { items: [], total: 0 }, page: 1, error: '', loading: false, timer: null, generation: 0 }),
  computed: {
    statusText() { return { PENDING: '检测中', FAILED: '检测失败，待重试', COMPLETE: '检测完成' }[this.current.status] || '未知状态' },
    summary() { return measurementSummary(this.current.result) }
  },
  mounted() { this.load() },
  beforeDestroy() { clearTimeout(this.timer); this.generation++ },
  methods: {
    async load(id) {
      clearTimeout(this.timer)
      const generation = ++this.generation
      this.measurementId = id || null; this.loading = true; this.error = ''
      try {
        const result = await api.status(this.measurementId)
        if (generation !== this.generation) return
        this.current = result
        if (result && result.result) {
          (result.result.items || []).filter(item => item.state === 'UNCONFIGURED').forEach(item => {
            console.debug('[measurement-alerts] rule configuration unavailable', { metric: item.metric, state: item.state, ruleRevision: item.ruleRevision })
          })
        }
        if (result && result.status === 'PENDING') this.timer = setTimeout(() => this.load(this.measurementId), 5000)
        await this.loadHistory()
      } catch (e) { if (generation === this.generation) this.error = displayError(e) }
      finally { if (generation === this.generation) this.loading = false }
    },
    async loadHistory() { try { this.history = await api.history(this.page) } catch (e) { this.error = displayError(e) } },
    async retry() { this.loading = true; try { await api.retry(this.current.blockingMeasurementId || this.current.measurementId); await this.load(this.current.measurementId) } catch (e) { this.error = displayError(e) } finally { this.loading = false } },
    level: alertLevel, alertStyle, eventStyle, formatTime, measurementValue, measurementRange,
    reasons: measurementReasons
  }
}
</script>
<style scoped>
.measurement-alerts { margin: 18px 0; }
.alert-heading { display: flex; align-items: center; justify-content: space-between; }
.hint { color: #606266; font-size: 13px; line-height: 1.7; }
.history { margin-top: 16px; }
.measurement-alerts >>> .cell { white-space: normal; word-break: break-word; line-height: 1.7; }
</style>
