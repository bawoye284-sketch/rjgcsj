<template>
  <section class="health-notifications">
    <div class="heading"><strong>健康通知记录</strong><el-button type="text" :loading="loading" @click="load">刷新</el-button></div>
    <p v-if="capturePending" class="hint">还有 {{ capturePending }} 次检测的通知正在处理，请稍后刷新查看。</p>
    <el-alert v-if="captureErrors.length" title="部分通知暂未完成，系统将按照规则继续尝试。原测量和预警记录已保留。" type="warning" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="items" size="small" style="width:100%">
      <el-table-column type="expand"><template slot-scope="s"><p v-if="s.row.error_code">备注：{{ noticeNote(s.row.error_code) }}</p><p v-for="a in s.row.attempt_log" :key="a.attempt_no">{{ status({ status: a.status, channel: s.row.channel, error_code: a.error_code }) }} · {{ formatTime(a.started_at) }}<span v-if="a.error_code"> · {{ noticeNote(a.error_code) }}</span></p></template></el-table-column>
      <el-table-column label="提醒类别（生成时）" min-width="140"><template slot-scope="s"><span :style="alertStyle(notificationLevel(s.row.phase))">{{ level(notificationLevel(s.row.phase)) }}</span></template></el-table-column>
      <el-table-column label="通知对象" min-width="115"><template slot-scope="s">{{ recipient(s.row.recipient_type) }}</template></el-table-column>
      <el-table-column label="提醒类型" min-width="150"><template slot-scope="s">{{ phaseLabel(s.row.phase) }} · {{ channel(s.row.channel) }}</template></el-table-column>
      <el-table-column label="发送状态" min-width="140"><template slot-scope="s">{{ status(s.row) }}</template></el-table-column>
      <el-table-column label="备注" min-width="180"><template slot-scope="s">{{ noticeNote(s.row.error_code) || '—' }}</template></el-table-column>
      <el-table-column label="通知生成时间" min-width="150"><template slot-scope="s">{{ formatTime(s.row.created_at) }}</template></el-table-column>
      <el-table-column label="操作" min-width="100"><template slot-scope="s"><el-button v-if="!doctor && s.row.status === 'FAILED' && s.row.attempts < 3" type="text" :disabled="loading" @click="retry(s.row.id)">重试</el-button><el-button v-if="s.row.channel === 'IN_APP' && !s.row.read_at && s.row.status === 'SENT' && (doctor ? s.row.recipient_type === 'DOCTOR' : s.row.recipient_type === 'USER')" type="text" @click="read(s.row.id)">标为已读</el-button></template></el-table-column>
    </el-table>
    <el-pagination :current-page.sync="page" :page-size="10" :total="total" layout="prev, pager, next" @current-change="load" />
  </section>
</template>
<script>
import api from '@/api/healthCare'
import { notificationState, notificationRecipient, notificationPhase, notificationNote, displayError, notificationLevel, alertLevel, alertStyle, formatTime } from '@/utils/healthAlertDisplay'
export default {
  props: { doctor: Boolean },
  data: () => ({ items: [], page: 1, total: 0, capturePending: 0, captureErrors: [], loading: false, error: '' }),
  mounted() { this.load() },
  methods: {
    async load() { this.loading = true; try { const data = await api.notifications(this.page); this.items = data.items.filter(row => row.status !== 'MOCK_SENT').map(row => ({ ...row, emailServiceEnabled: data.emailServiceEnabled === true })); this.total = data.total; this.capturePending = data.capturePending || 0; this.captureErrors = data.captureErrors || []; this.error = '' } catch (e) { this.error = displayError(e) } finally { this.loading = false } },
    async retry(id) { try { await api.retry(id); await this.load() } catch (e) { this.error = displayError(e) } },
    async read(id) { try { await api.read(id); await this.load() } catch (e) { this.error = displayError(e) } },
    recipient: notificationRecipient,
    phaseLabel: notificationPhase,
    status: notificationState,
    noticeNote: notificationNote,
    notificationLevel, level: alertLevel, alertStyle, formatTime,
    channel(value) { return { EMAIL: '邮件提醒', IN_APP: '站内提醒' }[value] || '通知' }
  }
}
</script>
<style scoped>.heading {display:flex;justify-content:space-between;align-items:center}.hint {color:#606266;font-size:13px;line-height:1.7}.health-notifications >>> .cell {white-space:normal;word-break:break-word;line-height:1.7}</style>
