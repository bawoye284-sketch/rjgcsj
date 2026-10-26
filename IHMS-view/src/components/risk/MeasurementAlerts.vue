<template>
  <el-card class="measurement-alerts" shadow="never">
    <div slot="header" class="alert-heading"><strong>健康测量预警</strong><el-button type="text" :loading="loading" @click="load(measurementId)">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="alert-overview">
      <div class="overview-card"><span>活动异常</span><strong :class="{danger: activeCount > 0}">{{ activeCount }}</strong><small>项指标持续关注</small></div>
      <div class="overview-card"><span>连续异常</span><strong :class="{danger: repeatedCount > 0}">{{ repeatedCount }}</strong><small>项需要重点留意</small></div>
      <div class="overview-card"><span>最近检测</span><strong class="overview-time">{{ latestEventTime }}</strong><small>以规则检测记录为准</small></div>
      <div class="overview-card advice"><span>建议</span><strong>{{ overviewAdvice }}</strong><small>异常持续或不适时联系医生</small></div>
    </div>
    <div class="alert-dashboard">
      <div :class="['signal-light', signalTone]"><i class="signal-dot"></i><span>{{ signalTitle }}</span><small>{{ signalDescription }}</small></div>
      <div class="risk-meter"><div class="meter-heading"><b>预警驾驶舱</b><span>基于最近规则检测</span></div><div class="meter-track"><i :style="{width: riskScore + '%'}"></i></div><div class="meter-foot"><strong>{{ riskScore }}<em>/100</em></strong><span>{{ nextAction }}</span></div></div>
      <div class="streak-panel"><span>连续异常进度</span><div class="streak-dots"><i v-for="n in 3" :key="n" :class="{on: n <= repeatedCount}"></i></div><small>{{ repeatedCount >= 3 ? '已达到连续异常提醒条件' : '连续记录可帮助系统识别趋势' }}</small></div>
    </div>
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
    summary() { return measurementSummary(this.current.result) },
    activeCount() { return this.history.items.filter(item => item.status === 'ACTIVE').length },
    repeatedCount() { return this.history.items.filter(item => item.status === 'ACTIVE' && item.level === 'REPEATED').length },
    latestEventTime() { return this.history.items.length ? this.formatTime(this.history.items[0].updatedAt) : '暂无' },
    overviewAdvice() { return this.repeatedCount ? '优先复测并咨询医生' : this.activeCount ? '复测并观察趋势' : '继续按计划记录' }
    ,riskScore() { return Math.min(100, this.repeatedCount * 30 + Math.max(0, this.activeCount - this.repeatedCount) * 15 + (this.current && this.current.result && this.current.result.outcome === 'ALERT' ? 20 : 0)) },
    signalTone() { return this.riskScore >= 70 ? 'critical' : this.riskScore >= 30 ? 'watch' : 'safe' },
    signalTitle() { return { safe: '状态稳定', watch: '需要关注', critical: '重点预警' }[this.signalTone] },
    signalDescription() { return { safe: '暂未发现持续异常信号', watch: '建议复测并观察接下来变化', critical: '建议尽快复测并联系医生' }[this.signalTone] },
    nextAction() { return this.riskScore >= 70 ? '优先联系医生' : this.riskScore >= 30 ? '24小时内复测' : '按计划继续记录' }
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
.alert-overview { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 4px 0 18px; }
.overview-card { min-height: 82px; padding: 14px 16px; border: 1px solid #e7edf3; border-radius: 10px; background: linear-gradient(135deg, #f8fbff, #fff); box-sizing: border-box; }
.overview-card span, .overview-card small { display: block; color: #718096; font-size: 12px; }
.overview-card strong { display: block; margin: 5px 0 3px; color: #2f5f78; font-size: 24px; line-height: 1.1; }
.overview-card strong.danger { color: #d97706; }
.overview-card .overview-time { font-size: 16px; }
.overview-card.advice strong { font-size: 15px; color: #276749; }
.alert-dashboard { display: grid; grid-template-columns: minmax(150px, .8fr) minmax(240px, 1.6fr) minmax(160px, .9fr); gap: 12px; margin-bottom: 18px; }
.signal-light, .risk-meter, .streak-panel { min-height: 112px; padding: 16px; border-radius: 12px; box-sizing: border-box; }
.signal-light { display: flex; flex-direction: column; justify-content: center; color: #276749; background: #effaf3; border: 1px solid #c7ead1; }
.signal-light.watch { color: #a15c00; background: #fff8e8; border-color: #f3d58c; }
.signal-light.critical { color: #b42318; background: #fff0ef; border-color: #f2b8b5; }
.signal-dot { width: 14px; height: 14px; margin-bottom: 8px; border-radius: 50%; background: currentColor; box-shadow: 0 0 0 6px rgba(39,103,73,.12); }
.signal-light span { font-size: 20px; font-weight: 700; }.signal-light small, .streak-panel small { margin-top: 5px; font-size: 12px; opacity: .82; }
.risk-meter { border: 1px solid #e3edf2; background: linear-gradient(135deg, #f8fbff, #fff); }.meter-heading, .meter-foot { display: flex; justify-content: space-between; align-items: center; }.meter-heading span { color: #8b9aa5; font-size: 12px; }.meter-track { height: 12px; margin: 25px 0 11px; border-radius: 99px; background: #e8eff2; overflow: hidden; }.meter-track i { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, #63b68a, #f3b547, #df6657); transition: width .3s; }.meter-foot strong { color: #2f5f78; font-size: 22px; }.meter-foot em { color: #8b9aa5; font-size: 12px; font-style: normal; }.meter-foot span { color: #617887; font-size: 12px; }
.streak-panel { border: 1px solid #e6edf0; background: #f8fafb; }.streak-panel > span { color: #48616d; font-size: 13px; }.streak-dots { display: flex; gap: 9px; margin: 20px 0 10px; }.streak-dots i { width: 22px; height: 8px; border-radius: 99px; background: #dce6ea; }.streak-dots i.on { background: #e47755; }
@media (max-width: 900px) { .alert-overview { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 520px) { .alert-overview { grid-template-columns: 1fr; } }
@media (max-width: 900px) { .alert-dashboard { grid-template-columns: 1fr 1fr; }.risk-meter { grid-column: span 2; } }
@media (max-width: 520px) { .alert-dashboard { grid-template-columns: 1fr; }.risk-meter { grid-column: auto; } }
</style>
