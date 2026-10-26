<template>
  <section class="contact-settings">
    <el-divider>紧急联系人与预警授权</el-divider>
    <el-alert title="请在本人知情同意后设置联系人。接收健康邮件需获得授权并完成邮箱验证。" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="contacts" size="small" style="width:100%">
      <el-table-column prop="name" label="姓名/关系" min-width="100"><template slot-scope="s">{{ s.row.name }} / {{ s.row.relationship }}</template></el-table-column>
      <el-table-column prop="email" label="邮箱" min-width="180" />
      <el-table-column label="授权/验证" min-width="140"><template slot-scope="s">{{ s.row.authorized ? '已授权' : '未授权' }} / {{ verification(s.row.email_verification) }}</template></el-table-column>
      <el-table-column label="操作" min-width="165"><template slot-scope="s"><el-button type="text" @click="edit(s.row)">编辑</el-button><el-button type="text" :disabled="busy" @click="challenge(s.row)">验证邮箱</el-button><el-button type="text" :disabled="busy" @click="remove(s.row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-button type="text" @click="edit()">新增联系人</el-button>
    <el-form v-if="draft" label-position="top" class="editor">
      <el-row :gutter="12"><el-col :xs="24" :sm="12"><el-form-item label="姓名"><el-input v-model="draft.name" maxlength="60" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="关系"><el-input v-model="draft.relationship" maxlength="40" /></el-form-item></el-col></el-row>
      <el-row :gutter="12"><el-col :xs="24" :sm="12"><el-form-item label="邮箱"><el-input v-model="draft.email" maxlength="254" /></el-form-item></el-col><el-col :xs="24" :sm="12"><el-form-item label="手机号（暂不发送短信）"><el-input v-model="draft.phone" maxlength="32" /></el-form-item></el-col></el-row>
      <el-checkbox v-model="draft.authorized">本人知情并授权此联系人接收必要健康提醒</el-checkbox>
      <p><el-button type="primary" size="small" :loading="busy" @click="save">保存联系人</el-button><el-button size="small" @click="draft = null">取消</el-button></p>
      <p class="hint">修改邮箱或手机号后，需要重新验证相应联系方式；修改联系人资料会取消此前尚未发送的邮件。</p>
    </el-form>
    <el-form v-if="verifying" label-position="top" class="editor"><el-form-item :label="'验证 ' + verifying.name + ' 的邮箱'"><el-input v-model="token" placeholder="输入联系人收到的一次性验证码" maxlength="32" /></el-form-item><el-button size="small" :loading="busy" @click="verify">确认验证</el-button><el-button size="small" @click="verifying = null; token = ''">取消</el-button></el-form>
    <el-divider>主治医生与预警联动</el-divider>
    <el-alert title="绑定即授权该医生查看您的规则预警和已授权且完成验证的紧急联系人。新测量触发通知规则或发起紧急求助时，医生会收到站内提醒。可随时解除绑定或关闭预警授权。" type="info" show-icon :closable="false" />
    <div class="doctor-link-actions"><el-button size="small" type="primary" plain icon="el-icon-first-aid-kit" @click="$router.push('/doctor-consultation')">咨询医生 / 建立关联</el-button><span class="hint">也可以直接在下方选择主治医生</span></div>
    <el-table v-if="bindingOptions.length" :data="bindingOptions" size="small" class="binding-table"><el-table-column prop="name" label="医生" /><el-table-column prop="specialty" label="专业" /><el-table-column label="主治绑定"><template slot-scope="s"><el-switch :value="Boolean(s.row.bound)" :disabled="busy" active-text="已绑定" inactive-text="未绑定" @change="v => bindDoctor(s.row.doctor_id, v)" /></template></el-table-column></el-table>
    <p class="hint">下方可单独开关已绑定医生或进行中咨询医生的预警权限。独立绑定不随咨询结束而失效；撤销授权或解除绑定后，医生不再具有相应预警权限。</p>
    <el-table :data="doctors" size="small"><el-table-column prop="name" label="医生" /><el-table-column prop="specialty" label="专业" /><el-table-column label="预警授权"><template slot-scope="s"><el-switch :value="Boolean(s.row.authorized)" :disabled="busy" @change="v => authorize(s.row.doctor_id, v)" /></template></el-table-column></el-table>
    <HealthNotifications />
  </section>
</template>
<script>
import api from '@/api/healthCare'
import HealthNotifications from './HealthNotifications.vue'
import { displayError } from '@/utils/healthAlertDisplay'
export default {
  components: { HealthNotifications },
  data: () => ({ contacts: [], doctors: [], bindingOptions: [], draft: null, error: '', busy: false, verifying: null, token: '', demo: false }),
  mounted() { this.load(); this.loadBindings() },
  methods: {
    async load() { try { const [contacts, doctors] = await Promise.all([api.contacts(), api.doctors()]); this.contacts = contacts; this.doctors = doctors; this.error = '' } catch (e) { this.error = displayError(e) } },
    async loadBindings() { try { this.bindingOptions = await api.bindingOptions() } catch (e) { this.error = displayError(e) } },
    async bindDoctor(id, value) { this.busy = true; try { await api.bindDoctor(id, value); await Promise.all([this.load(), this.loadBindings()]); this.$message.success(value ? '主治医生已绑定并开启预警授权' : '已解除主治医生绑定') } catch (e) { this.error = displayError(e) } finally { this.busy = false } },
    edit(row) { this.draft = row ? { id: row.id, name: row.name, relationship: row.relationship, email: row.email, phone: row.phone || '', authorized: Boolean(row.authorized) } : { name: '', relationship: '', email: '', phone: '', authorized: false } },
    async save() { this.busy = true; try { await api.saveContact(this.draft); this.draft = null; this.verifying = null; this.token = ''; await this.load() } catch (e) { this.error = displayError(e) } finally { this.busy = false } },
    async remove(row) { try { await this.$confirm('删除此联系人并停止后续通知？', '删除联系人'); await api.deleteContact(row.id); await this.load() } catch (e) { if (e && e.message) this.error = displayError(e) } },
    async challenge(row) {
      this.busy = true
      try {
        const r = await api.challenge(row.id)
        this.token = ''
        if (r.mode === 'MOCK') {
          this.verifying = null
          this.error = '未配置邮件服务，暂时无法验证邮箱。'
          return
        }
        this.verifying = row
        this.error = ''
        this.$message.success('验证码已提交邮件服务器，请查收后输入')
      } catch (e) {
        // SMTP can accept the message before the HTTP response reaches the
        // browser. Keep the verification form available so the user can use
        // the code already received instead of requesting a duplicate.
        const message = e && e.message ? e.message : ''
        if (/超时|结果不明确|请稍后再申请验证/.test(message) || e.status === 429) {
          this.verifying = row
          this.token = ''
          this.error = '验证码邮件可能已经发送，请输入邮箱中收到的验证码；不要重复申请。'
        } else {
          this.error = displayError(e)
        }
      } finally { this.busy = false }
    },
    async verify() { this.busy = true; try { await api.verify(this.verifying.id, this.token); this.verifying = null; this.token = ''; await this.load() } catch (e) { this.error = displayError(e) } finally { this.busy = false } },
    async authorize(id, value) { this.busy = true; try { await api.authorize(id, value); await this.load() } catch (e) { this.error = displayError(e) } finally { this.busy = false } },
    verification(value) { return { NONE: '未完成邮箱验证', DEMO: '未完成邮箱验证', REAL: '邮箱已验证' }[value] || '验证状态暂不明确' }
  }
}
</script>
<style scoped>.contact-settings {margin-top:18px}.hint {font-size:13px;color:#606266;line-height:1.7}.editor {padding:14px;background:#f5f7fa;border-radius:5px;margin:12px 0}.doctor-link-actions {display:flex;align-items:center;gap:12px;margin:12px 0 4px;flex-wrap:wrap}.doctor-link-actions .hint {margin:0}</style>
