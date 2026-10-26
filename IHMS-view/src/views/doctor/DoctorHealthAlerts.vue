<template>
  <main class="doctor-alerts">
    <div class="heading"><h1>患者健康预警</h1><el-button @click="$router.push('/doctor-home')">返回医生工作台</el-button></div>
    <el-alert title="仅展示已建立有效关联并获得授权的患者预警。医生的确认和处理记录将分别保存，线上提醒不能替代实际医疗处置。" type="info" :closable="false" />
    <el-form inline class="filters">
      <el-form-item label="等级"><el-select v-model="filters.level" clearable placeholder="全部"><el-option label="紧急求助" value="EMERGENCY" /><el-option label="普通异常提醒" value="GENERAL" /><el-option label="连续测量异常" value="REPEATED" /></el-select></el-form-item>
      <el-form-item label="处理状态"><el-select v-model="filters.handling" clearable placeholder="全部"><el-option label="待确认" value="OPEN" /><el-option label="已确认收到" value="ACKNOWLEDGED" /><el-option label="已处理" value="RESOLVED" /></el-select></el-form-item>
      <el-form-item label="日期"><el-date-picker v-model="dates" type="daterange" value-format="yyyy-MM-dd" range-separator="至" start-placeholder="开始" end-placeholder="结束" /></el-form-item>
      <el-button type="primary" :loading="loading" @click="filters.page = 1; load()">查询</el-button>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="items" style="width:100%">
      <el-table-column prop="patient_name" label="患者" min-width="90" />
      <el-table-column label="预警内容" min-width="100"><template slot-scope="s">{{ s.row.kind === 'SOS' ? '患者主动求助' : s.row.details.name }}</template></el-table-column>
      <el-table-column label="风险状态" min-width="140"><template slot-scope="s"><span :style="eventStyle(s.row)">{{ eventLabel(s.row) }}</span></template></el-table-column>
      <el-table-column label="处理状态" min-width="115"><template slot-scope="s">{{ handling(s.row.handling) }}</template></el-table-column>
      <el-table-column label="最近发生时间" min-width="150"><template slot-scope="s">{{ formatTime(s.row.updated_at) }}</template></el-table-column>
      <el-table-column label="操作" width="90"><template slot-scope="s"><el-button type="text" @click="open(s.row.id)">查看详情</el-button></template></el-table-column>
    </el-table>
    <el-pagination :current-page.sync="filters.page" :page-size="10" :total="total" layout="prev, pager, next" @current-change="load" />
    <el-collapse><el-collapse-item title="站内健康通知" name="notifications"><HealthNotifications :doctor="true" /></el-collapse-item></el-collapse>
    <el-dialog title="预警详情与处理" :visible.sync="visible" width="min(960px, 94vw)" @closed="detail = null; note = ''">
      <template v-if="detail"><p>{{ detail.patient_name }} · {{ detail.sos ? '患者主动求助' : detail.details.name }}</p>
        <p>风险状态：<span :style="eventStyle(detail)">{{ eventLabel(detail) }}</span>；处理状态：{{ handling(detail.handling) }}。</p>
        <p v-if="detail.sos">患者主动发起紧急求助，无需提交健康测量或等待风险评估。</p><template v-else><p v-for="reason in reasons(detail.details)" :key="reason">{{ reason }}</p></template>
        <p>首次：{{ formatTime(detail.opened_at) }}；最近：{{ formatTime(detail.updated_at) }}。</p>
        <p v-if="detail.sos">确认时限：{{ formatTime(detail.sos.confirmation_deadline) }}。未确认时，系统将按通知设置继续提醒。确认收到与记录处理结果分别保存。</p>
        <p v-else>累计异常 {{ detail.occurrences }} 次。</p>
        <el-alert v-if="detail.emergency_contacts && detail.emergency_contacts.length" title="患者已授权的紧急联系人" type="success" :closable="false" show-icon>
          <div v-for="contact in detail.emergency_contacts" :key="contact.email" class="contact-row"><b>{{ contact.name }}</b><span>{{ contact.relationship }}</span><span>{{ contact.email }}</span><span v-if="contact.phone">{{ contact.phone }}</span></div>
        </el-alert>
        <p v-else class="contact-empty">患者暂未授权并完成邮箱验证的紧急联系人。</p>
        <el-input v-model="note" type="textarea" maxlength="1000" show-word-limit placeholder="请填写确认情况或后续处理说明。" />
        <p><el-button :disabled="acting || detail.handling !== 'OPEN'" @click="action('acknowledge')">确认收到</el-button><el-button type="primary" :disabled="acting || detail.handling === 'RESOLVED'" @click="action('resolve')">记录处理结果</el-button></p>
        <h3>确认与处理记录</h3><el-table :data="detail.actions" size="small"><el-table-column prop="doctor_name" label="医生姓名" /><el-table-column label="处理状态"><template slot-scope="s">{{ actionLabel(s.row.action) }}</template></el-table-column><el-table-column prop="note" label="处理说明" min-width="180" /><el-table-column label="操作时间" min-width="150"><template slot-scope="s">{{ formatTime(s.row.created_at) }}</template></el-table-column></el-table>
        <template v-if="!detail.sos"><h3>测量与等级历史（最近100次）</h3><el-table :data="detail.measurements" size="small"><el-table-column type="index" label="序号" /><el-table-column label="实测值"><template slot-scope="s">{{ measurementValue(s.row.item) }}</template></el-table-column><el-table-column label="等级" min-width="120"><template slot-scope="s"><span :style="alertStyle(s.row.item.level, detail.status === 'RECOVERED')">{{ level(s.row.item.level) }}</span></template></el-table-column><el-table-column label="原因" min-width="240"><template slot-scope="s"><div v-for="reason in reasons(s.row.item)" :key="reason">{{ reason }}</div></template></el-table-column></el-table></template>
      </template>
    </el-dialog>
  </main>
</template>
<script>
import api from '@/api/healthCare'
import healthSync from '@/utils/healthSync'
import HealthNotifications from '@/components/risk/HealthNotifications.vue'
import { displayError, alertLevel, alertStyle, eventStyle, eventRisk, handlingState, actionState, formatTime, measurementReasons, measurementValue } from '@/utils/healthAlertDisplay'
export default {
  mixins: [healthSync],
  components: { HealthNotifications },
  data: () => ({ filters: { page: 1, size: 10, level: '', handling: '' }, dates: [], items: [], total: 0, loading: false, error: '', detail: null, visible: false, note: '', acting: false }),
  mounted() { this.load() },
  methods: {
    async refreshHealthData() { if (!this.loading && !this.acting && !this.visible) await this.load() },
    async load() { this.loading = true; try { const r = await api.alerts({ ...this.filters, from: this.dates && this.dates[0], to: this.dates && this.dates[1] }); this.items = r.items; this.total = r.total; this.error = '' } catch (e) { this.error = displayError(e) } finally { this.loading = false } },
    async open(id) { try { this.detail = await api.detail(id); this.visible = true; this.note = '' } catch (e) { this.detail = null; this.visible = false; this.error = displayError(e) } },
    async action(action) { this.acting = true; try { await api.action(this.detail.id, action, this.note); await this.open(this.detail.id); await this.load() } catch (e) { this.$message.error(displayError(e)) } finally { this.acting = false } },
    level: alertLevel, alertStyle, eventStyle,
    handling: handlingState, eventLabel: eventRisk, actionLabel: actionState,
    formatTime, reasons: measurementReasons, measurementValue
  }
}
</script>
<style scoped>.doctor-alerts {max-width:1320px;margin:0 auto;padding:24px;box-sizing:border-box}.heading {display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap}.filters {margin-top:20px}.filters .el-select {width:160px}.contact-row {display:flex;gap:14px;align-items:center;flex-wrap:wrap;padding:3px 0;color:#315d69}.contact-empty {color:#8b9aa5;font-size:13px}@media(max-width:700px){.doctor-alerts {padding:12px}.filters >>> .el-date-editor {max-width:100%}}.doctor-alerts >>> .cell {white-space:pre-line;word-break:break-word;line-height:1.7}</style>
