<template>
  <main class="sos-page">
    <h1 :style="alertStyle('EMERGENCY')">紧急求助</h1>
    <p>如遇紧急情况，请立即联系当地急救服务。</p>
    <p>当您需要紧急帮助时，可直接发起求助，无需填写健康数据。</p>
    <el-button class="sos-button" type="danger" :loading="submitting" :disabled="active && !reminderReady" @click="active ? remind() : submit()">{{ active ? '再次发送求助提醒' : '发起紧急求助' }}</el-button>
    <p v-if="active && !reminderReady" class="hint">{{ latestCurrent && latestCurrent.reminderLimitReached ? '已达到本次求助的提醒上限。' : '可再次提醒时间：' + formatTime(latestCurrent && latestCurrent.reminderNextAt) }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-card v-if="current" shadow="never">
      <div slot="header" class="heading"><strong>{{ selectedId ? '历史求助详情' : '紧急求助已提交' }}</strong><span><el-button v-if="selectedId" type="text" @click="selectedId = null; load()">返回最近求助</el-button><el-button type="text" :loading="loading" @click="load">刷新</el-button></span></div>
      <el-descriptions :column="1"><el-descriptions-item label="求助类型"><span :style="alertStyle('EMERGENCY')">紧急求助</span></el-descriptions-item><el-descriptions-item label="求助时间">{{ formatTime(current.opened_at) }}</el-descriptions-item><el-descriptions-item label="当前状态">{{ state(current.status) }}</el-descriptions-item><el-descriptions-item label="通知进展">{{ notificationProgress }}</el-descriptions-item><el-descriptions-item label="医生响应状态">{{ doctorResponse }}</el-descriptions-item></el-descriptions>
      <p v-if="current.status === 'ACKNOWLEDGED'">医生已确认收到：{{ formatTime(current.acknowledged_at) }}。处理尚未结束。</p>
      <p v-if="current.status === 'RESOLVED'">已处理：{{ formatTime(current.resolved_at) }}。</p>
      <el-alert v-if="current.notification_error" title="部分提醒暂未完成，请稍后查看。" type="warning" :closable="false" />
      <p v-if="!externalNotices">暂未有发给紧急联系人或关联医生的有效通知。请检查个人中心授权设置，并主动联系身边人员。</p>
      <el-table :data="businessNotifications" size="small" style="width:100%">
        <el-table-column label="通知对象" min-width="115"><template slot-scope="s">{{ recipient(s.row.recipient_type) }}</template></el-table-column>
        <el-table-column label="提醒类型" min-width="115"><template slot-scope="s">{{ phaseLabel(s.row.phase) }}</template></el-table-column>
        <el-table-column label="发送状态" min-width="140"><template slot-scope="s">{{ noticeState(s.row) }}</template></el-table-column>
        <el-table-column label="备注" min-width="190"><template slot-scope="s">{{ noticeNote(s.row.error_code) || (s.row.channel === 'EMAIL' ? '邮件提醒' : s.row.channel === 'IN_APP' ? '站内提醒' : '通知方式暂不明确') }}</template></el-table-column>
      </el-table>
      <h3>医生确认与处理记录</h3>
      <el-table :data="current.actions" size="small"><el-table-column prop="doctor_name" label="医生姓名" /><el-table-column label="处理状态"><template slot-scope="s">{{ actionLabel(s.row.action) }}</template></el-table-column><el-table-column prop="note" label="处理说明" min-width="180" /><el-table-column label="操作时间" min-width="150"><template slot-scope="s">{{ formatTime(s.row.created_at) }}</template></el-table-column></el-table>
    </el-card>
    <el-collapse class="history"><el-collapse-item title="历史求助记录" name="history"><el-table :data="history.items" size="small"><el-table-column label="求助时间" min-width="150"><template slot-scope="s">{{ formatTime(s.row.opened_at) }}</template></el-table-column><el-table-column label="当前状态"><template slot-scope="s">{{ state(s.row.status) }}</template></el-table-column><el-table-column label="操作"><template slot-scope="s"><el-button type="text" @click="inspect(s.row.id)">查看详情</el-button></template></el-table-column></el-table><el-pagination :current-page.sync="page" :page-size="10" :total="history.total" layout="prev, pager, next" @current-change="loadHistory" /></el-collapse-item></el-collapse>
  </main>
</template>
<script>
import api from '@/api/sos'
import { notificationState, notificationRecipient, notificationPhase, notificationNote, displayError, alertStyle, formatTime, actionState } from '@/utils/healthAlertDisplay'
function requestId() { return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => { const n = window.crypto.getRandomValues(new Uint8Array(1))[0] % 16; return (c === 'x' ? n : (n & 3) | 8).toString(16) }) }
export default {
  data: () => ({ policy: { demo: true }, current: null, latestCurrent: null, selectedId: null, latestActive: false, history: { items: [], total: 0 }, page: 1, loading: false, submitting: false, pendingId: null, pendingReminderId: null, pendingReminderEventId: null, error: '', timer: null }),
  computed: {
    active() { return this.latestActive },
    reminderReady() { return Boolean(this.latestCurrent && this.latestCurrent.reminderAllowed) },
    businessNotifications() { return this.current ? this.current.notifications.filter(n => n.status !== 'MOCK_SENT').map(n => ({ ...n, emailServiceEnabled: this.current.emailServiceEnabled === true })) : [] },
    externalNotices() { return this.businessNotifications.some(n => n.recipient_type !== 'USER' && !['CANCELLED', 'SKIPPED'].includes(n.status) && !(n.channel === 'EMAIL' && n.status === 'PENDING' && n.emailServiceEnabled === false)) },
    doctorResponse() {
      if (this.current.status === 'RESOLVED') return '已处理'
      if (this.current.status === 'ACKNOWLEDGED' || this.current.actions.some(a => a.action === 'ACKNOWLEDGED')) return '已确认收到'
      return this.current.status === 'OPEN' ? '待确认' : '医生响应状态尚不明确'
    },
    notificationProgress() {
      const rows = this.businessNotifications
      if (this.current.reminderPending) return '正在处理求助提醒'
      if (this.current.notification_error) return '部分通知暂未完成，系统将按照规则继续尝试。'
      if (!rows.length) return this.current.emailServiceEnabled === false ? '求助记录已创建；未配置邮件服务' : '求助记录已创建，正在处理通知'
      if (rows.some(n => n.status === 'UNKNOWN')) return '通知发送结果尚不明确'
      if (rows.some(n => n.status === 'FAILED')) return '通知发送未成功'
      if (rows.some(n => n.status === 'SENDING')) return '发送中'
      if (rows.some(n => n.status === 'PENDING' && !(n.channel === 'EMAIL' && n.emailServiceEnabled === false))) return '等待发送'
      if (Number.isInteger(this.current.planned_stage) && this.current.planned_stage < this.current.escalation_stage) return '正在处理通知'
      const hasMail = rows.some(n => n.channel === 'EMAIL' && n.status === 'SENT')
      const hasInApp = rows.some(n => n.channel === 'IN_APP' && n.status === 'SENT')
      if (this.current.emailServiceEnabled === false && !hasMail) return (hasInApp ? '站内消息已发送；' : '') + '未配置邮件服务'
      if (rows.every(n => n.status === 'SENT')) return hasMail ? hasInApp ? '站内消息已发送；邮件已提交邮件服务器' : '已提交邮件服务器' : '站内消息已发送'
      return '请查看下方各接收人的通知状态'
    }
  },
  mounted() { this.load(); this.timer = setInterval(this.load, 5000) },
  beforeDestroy() { clearInterval(this.timer) },
  methods: {
    async load() { if (this.loading || this.submitting) return; this.loading = true; try { const [policy, current] = await Promise.all([api.policy(), api.current()]); this.policy = policy; this.latestCurrent = current; this.latestActive = Boolean(current && current.status !== 'RESOLVED'); this.current = this.selectedId ? await api.detail(this.selectedId) : current; this.error = ''; await this.loadHistory() } catch (e) { this.error = displayError(e) } finally { this.loading = false } },
    async loadHistory() { try { this.history = await api.history(this.page) } catch (e) { this.error = displayError(e) } },
    async inspect(id) { try { this.current = await api.detail(id); this.selectedId = id } catch (e) { this.error = displayError(e) } },
    async submit() {
      if (this.submitting || this.active) return
      const message = '确认发起紧急求助？系统将按当前可用渠道发送提醒。'
      try { await this.$confirm(message, '确认发起紧急求助', { type: 'warning', confirmButtonText: '确认发起求助', cancelButtonText: '取消', customClass: 'sos-confirm' }) } catch (_) { return }
      if (this.submitting || this.active) return
      this.submitting = true; this.error = ''
      if (!this.pendingId) this.pendingId = requestId()
      try { this.current = await api.create(this.pendingId); this.latestCurrent = this.current; this.selectedId = null; this.latestActive = this.current.status !== 'RESOLVED'; this.pendingId = null; await this.loadHistory(); this.$message.success('紧急求助已提交') }
      catch (e) { this.error = displayError(e) + ' 您可以稍后刷新查看或重试；如情况紧急，请立即联系当地急救服务。' } finally { this.submitting = false }
    },
    async remind() {
      if (this.submitting || !this.active || !this.reminderReady) return
      const id = this.latestCurrent.id
      const storageKey = 'sos-reminder-' + id
      if (this.pendingReminderEventId !== id) this.pendingReminderId = null
      this.pendingReminderEventId = id
      try { this.pendingReminderId = sessionStorage.getItem(storageKey) || this.pendingReminderId } catch (_) { /* Browser storage may be disabled. */ }
      if (!this.pendingReminderId) this.pendingReminderId = requestId()
      try { sessionStorage.setItem(storageKey, this.pendingReminderId) } catch (_) { /* Keep the key in memory for retries. */ }
      this.submitting = true; this.error = ''
      try { this.current = await api.remind(id, this.pendingReminderId); this.latestCurrent = this.current; this.latestActive = this.current.status !== 'RESOLVED'; this.selectedId = null; this.pendingReminderId = null; try { sessionStorage.removeItem(storageKey) } catch (_) { /* No browser storage. */ } this.$message.success('求助提醒已提交'); await this.loadHistory() }
      catch (e) { this.error = displayError(e) } finally { this.submitting = false }
    },
    state(value) { return { OPEN: '求助记录已创建', ACKNOWLEDGED: '已确认收到', RESOLVED: '已处理' }[value] || '求助状态尚不明确' },
    actionLabel: actionState, alertStyle, formatTime,
    recipient: notificationRecipient,
    phaseLabel: notificationPhase,
    noticeState: notificationState,
    noticeNote: notificationNote
  }
}
</script>
<style scoped>.sos-page {max-width:1160px;margin:0 auto;padding:24px;box-sizing:border-box;font-size:16px;line-height:1.8}.sos-button {font-size:24px;min-height:76px;margin:20px 0;width:100%;max-width:520px}.hint {font-size:14px;color:#606266;line-height:1.8}.heading {display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap}.phone {font-size:20px;color:#d93025}.history {margin-top:24px}.sos-page >>> .el-button span {white-space:normal}@media(max-width:600px){.sos-page{padding:12px}}</style>
<style>.sos-confirm .el-message-box__message {white-space:pre-line}</style>
