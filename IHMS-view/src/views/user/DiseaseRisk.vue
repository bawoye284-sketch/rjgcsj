<template>
  <div class="disease-page" v-loading="loading">
    <div class="page-heading">
      <div><div class="eyebrow">长期健康 · 研究预测</div><h1>慢性病风险预测</h1>
        <p>完善健康资料，分别查看五种疾病的研究模型估计，并保留每次评估记录。</p></div>
      <el-tag effect="plain" type="info">研究演示</el-tag>
    </div>
    <el-alert :closable="false" type="info" show-icon
      title="本模型来自历史美国随访数据，预测调查间隔内的新报告，尚未验证中国社区老人的实际风险。结果不能用于确诊，未达到研究阈值也不代表安全。" />
    <div class="service-line">
      <span :class="['service-dot', { ready: modelReady }]" />
      <span>{{ modelReady ? '五病种研究服务已就绪' : (modelError || '模型服务尚未就绪，仍可保存资料和查看历史') }}</span>
      <el-button type="text" size="small" @click="refreshModels">重新检查</el-button>
      <el-button type="text" size="small" @click="showModels = !showModels">模型说明</el-button>
    </div>
    <el-card v-if="showModels" shadow="never" class="section model-info">
      <p>版本：{{ modelInfo.model_version || '未连接' }}。当前优化结果属于探索性比较，需要新的独立数据验证。</p>
      <el-table :data="modelInfo.diseases || []" size="small">
        <el-table-column prop="name" label="病种" min-width="135" />
        <el-table-column label="适用年龄" width="110"><template slot-scope="s">{{ s.row.age_min }}–{{ s.row.age_max }}岁</template></el-table-column>
        <el-table-column label="AUC" width="90"><template slot-scope="s">{{ decimal(s.row.metrics && s.row.metrics.roc_auc) }}</template></el-table-column>
        <el-table-column label="召回率" width="100"><template slot-scope="s">{{ percent(s.row.metrics && s.row.metrics.sensitivity) }}</template></el-table-column>
        <el-table-column prop="quality_note" label="适用性说明" min-width="280" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="section">
      <div slot="header" class="section-heading"><div><b>一、健康资料</b><span>按字段标注填写，选填项可以留空</span></div>
        <div><el-button type="text" size="small" :disabled="busy" @click="reloadProfile">重新读取资料</el-button>
          <el-button type="text" size="small" :disabled="busy" @click="importExisting">引用已有基础资料</el-button></div></div>
      <el-form label-position="top" :disabled="busy" class="profile-form">
        <el-row :gutter="24">
          <el-col :xs="24" :sm="8"><el-form-item label="年龄（岁，必填）" required :error="fieldErrors.age">
            <el-input-number :value="numberValue(form.profile.age)" :min="0" :max="110" :precision="0" placeholder="请填写实际年龄" controls-position="right" @change="v => setNumber('age', v)" />
          </el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="性别（预测必填）" required :error="fieldErrors.sexMale">
            <el-select :value="triValue(form.profile.sexMale)" @change="v => setTri(form.profile, 'sexMale', v)">
              <el-option label="请选择性别" :value="-1" /><el-option label="男" :value="1" /><el-option label="女" :value="0" />
            </el-select>
          </el-form-item></el-col>
        </el-row>
        <div class="field-group-heading" :class="{ 'has-error': basicError }">健康与生活习惯（预测时以下五项至少填写两项，已填 {{ knownBasicCount }}/5 项）</div>
        <p v-if="basicError" class="field-error">{{ basicError }}</p>
        <el-row :gutter="24">
          <el-col :xs="24" :sm="8"><el-form-item label="当前体重（kg，五项任选）">
            <el-input-number :value="numberValue(form.profile.weightKg)" :min="25" :max="320" :precision="1" controls-position="right" @change="v => setNumber('weightKg', v)" />
          </el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="自评健康（五项任选）">
            <el-select :value="triValue(form.profile.selfRatedHealth)" @change="v => setTri(form.profile, 'selfRatedHealth', v)">
              <el-option label="不清楚 / 未填写" :value="-1" /><el-option v-for="(label, index) in healthLabels" :key="index" :label="label" :value="index + 1" />
            </el-select>
          </el-form-item></el-col>
          <el-col v-for="field in basicChoices" :key="field.key" :xs="24" :sm="8"><el-form-item :label="field.label + '（五项任选）'">
            <el-select :value="triValue(form.profile[field.key])" @change="v => setTri(form.profile, field.key, v)">
              <el-option label="不清楚" :value="-1" /><el-option label="是" :value="1" /><el-option label="否" :value="0" />
            </el-select>
          </el-form-item></el-col>
        </el-row>
        <el-row :gutter="24">
            <el-col v-for="field in extraChoices" :key="field.key" :xs="24" :sm="8"><el-form-item :label="field.label + '（选填）'" :error="fieldErrors[field.key]">
              <el-select :value="triValue(form.profile[field.key])" @change="v => setTri(form.profile, field.key, v)">
                <el-option label="不清楚" :value="-1" /><el-option label="是" :value="1" /><el-option label="否" :value="0" />
              </el-select>
            </el-form-item></el-col>
        </el-row>
      </el-form>
      <p class="muted">当前模型适用年龄为 {{ ageRange.min }}～{{ ageRange.max }} 岁，请填写实际年龄。只保存资料时可暂缺预测信息；开始预测前必须填写性别，并在体重、自评健康、吸烟、饮酒、运动中至少填写两项。选填项保持“不清楚”即可，无需猜测。</p>
    </el-card>

    <el-card shadow="never" class="section">
      <div slot="header" class="section-heading"><div><b>二、五项疾病史</b><span>预测对应病种前必填；未知可保存，但该病不生成预测</span></div></div>
      <div v-for="disease in diseases" :key="disease.code" class="history-answer">
        <strong>{{ disease.name }}<span class="required-mark"> *</span></strong>
        <el-radio-group :value="triValue(form.diseaseHistory[disease.code])" :disabled="busy" @input="v => setTri(form.diseaseHistory, disease.code, v)">
          <el-radio :label="1">有</el-radio><el-radio :label="0">无</el-radio><el-radio :label="-1">不清楚</el-radio>
        </el-radio-group>
        <span class="history-hint">{{ form.diseaseHistory[disease.code] === 0 ? '可评估首次新报告风险' : form.diseaseHistory[disease.code] === 1 ? '已有病史，本次跳过该病' : '病史未确认，本次跳过该病' }}</span>
        <el-input-number v-if="form.diseaseHistory[disease.code] === 1" :value="numberValue(form.diagnosisYears[disease.code])"
          :disabled="busy" :min="1900" :max="currentYear" :precision="0" :controls="false" placeholder="确诊年份（选填）"
          @change="v => $set(form.diagnosisYears, disease.code, v == null ? null : v)" />
      </div>
      <el-alert class="prediction-readiness" :closable="false" show-icon :type="predictionIssues.length ? 'warning' : 'info'"
        :title="predictionIssues.length ? '预测前还需完成：' + predictionIssues.join('；') : '本次可预测 ' + eligibleDiseases.length + ' 种疾病，其余已有病史或未确认的项目将说明跳过原因。'" />
      <div class="actions">
        <el-button :loading="saving" :disabled="busy" @click="saveProfile">保存资料</el-button>
        <el-button type="primary" :loading="assessing" :disabled="busy" @click="predict">{{ modelReady ? '保存并预测' : '重新连接并预测' }}</el-button>
        <span class="muted">{{ dirty ? '有未保存的修改' : (form.revision ? '资料已保存' : '尚未保存资料') }}</span>
      </div>
    </el-card>

    <section class="section results-section">
      <div class="section-heading"><div><h2>三、预测结果</h2><span v-if="result">{{ result.assessedAt }} · 资料版本 {{ result.profileRevision }}</span></div></div>
      <el-alert v-if="result && (dirty || result.profileRevision !== form.revision)" :closable="false" type="warning" title="以下结果对应较早的资料状态，请重新预测以更新结果。" />
      <el-alert v-if="assessmentError" :closable="false" type="error" :title="assessmentError + '。下方如有结果，为上次成功保存的记录。'" />
      <el-row v-if="result" :gutter="18" class="disease-grid">
        <el-col v-for="item in result.results" :key="item.disease_code" :xs="24" :sm="12" :lg="8">
          <el-card shadow="never" class="disease-card">
            <div class="card-top"><h3>{{ diseaseName(item.disease_code) }}</h3><el-tag size="small" type="info">{{ statusName(item.status) }}</el-tag></div>
            <template v-if="item.status === 'predicted'">
              <div class="probability">{{ percent(item.probability) }}</div><div class="estimate-label">研究模型估计</div>
              <p class="quality-note">{{ quality(item.disease_code, result) }}</p>
              <el-collapse><el-collapse-item title="查看研究阈值" :name="item.disease_code">
                <p>本版本阈值 {{ percent(item.threshold) }}；{{ item.alert ? '达到研究阈值' : '未达到研究阈值' }}。</p>
                <p class="muted">该操作点只用于研究比较，不能据此判断有病、无病或安全。</p>
              </el-collapse-item></el-collapse>
            </template>
            <template v-else><p class="skip-reason">{{ item.reason }}</p>
              <p v-if="item.missing_fields.length" class="muted">待补充：{{ missingLabels(item.missing_fields) }}</p></template>
          </el-card>
        </el-col>
      </el-row>
      <el-empty v-else description="保存健康资料后，开始首次五病种研究预测" />
      <p v-if="result" class="muted">结果来自 {{ result.modelVersion }}。五项结果彼此独立，概率不相加。当前结果不是精确的未来五年发病率。</p>
    </section>

    <el-card shadow="never" class="section">
      <div slot="header" class="section-heading"><b>四、评估历史</b><el-button type="text" @click="loadHistory">刷新</el-button></div>
      <el-table :data="history.items" empty-text="暂无评估记录">
        <el-table-column prop="assessedAt" label="评估时间" min-width="170" />
        <el-table-column prop="modelVersion" label="模型版本" min-width="230" />
        <el-table-column prop="profileRevision" label="资料版本" width="95" />
        <el-table-column label="结果" min-width="150"><template slot-scope="s">{{ s.row.status === 'completed' ? '五项已评估' : '含不适用或未评估项目' }}</template></el-table-column>
        <el-table-column label="操作" width="90"><template slot-scope="s"><el-button type="text" @click="openDetail(s.row.id)">查看详情</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="pagination" layout="prev, pager, next, total" :total="history.total" :page-size="10" :current-page="page" @current-change="changePage" />
    </el-card>

    <el-dialog title="历史评估详情" :visible.sync="detailVisible" width="85%" top="6vh">
      <div v-if="detail">
        <p>{{ detail.assessedAt }} · {{ detail.modelVersion }} · 资料版本 {{ detail.profileRevision }}</p>
        <p class="muted">以下是该次保存的输入快照；当前资料修改不会覆盖这份记录。</p>
        <el-descriptions :column="2" border size="small"><el-descriptions-item v-for="field in snapshotFields(detail)" :key="field.key" :label="field.label">{{ field.value }}</el-descriptions-item></el-descriptions>
        <el-table :data="detail.results" class="detail-results">
          <el-table-column label="病种" min-width="120"><template slot-scope="s">{{ diseaseName(s.row.disease_code) }}</template></el-table-column>
          <el-table-column label="状态" min-width="130"><template slot-scope="s">{{ statusName(s.row.status) }}</template></el-table-column>
          <el-table-column label="研究估计" width="120"><template slot-scope="s">{{ percent(s.row.probability) }}</template></el-table-column>
          <el-table-column prop="reason" label="说明" min-width="260" />
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import diseaseApi from '@/api/diseaseRisk'
const diseases = [
  { code: 'hypertension', name: '高血压' }, { code: 'diabetes', name: '糖尿病' },
  { code: 'stroke', name: '卒中 / 中风' }, { code: 'heart_attack', name: '心脏病发作' },
  { code: 'osteoporosis', name: '骨质疏松' }
]
const basicChoices = [{ key: 'smokingCurrent', label: '当前是否吸烟' }, { key: 'drinkingAny', label: '过去一年是否饮酒' }, { key: 'exerciseRegular', label: '是否规律运动 / 体育锻炼' }]
const extraChoices = [
  { key: 'highCholesterolHistory', label: '医生是否告知过高胆固醇' }, { key: 'healthWorsened', label: '过去一年健康是否变差' },
  { key: 'weightGain10lb', label: '过去一年增重至少约4.54kg' }, { key: 'weightLoss10lb', label: '过去一年减重至少约4.54kg' },
  { key: 'hipFractureHistory', label: '是否有髋部骨折史' }
]
const labels = { age: '年龄', sexMale: '性别', weightKg: '体重（kg）', selfRatedHealth: '自评健康' }
basicChoices.concat(extraChoices).forEach(f => { labels[f.key] = f.label })
const wire = { sex_male: '性别', weight_kg: '体重', self_rated_health: '自评健康', smoking_current: '吸烟', drinking_any: '饮酒', exercise_regular: '规律运动', high_cholesterol_history: '高胆固醇史', health_worsened: '近期健康变化', weight_gain_10lb: '增重情况', weight_loss_10lb: '减重情况', hip_fracture_history: '髋部骨折史' }
function emptyForm() {
  const profile = {}, diseaseHistory = {}, diagnosisYears = {}
  Object.keys(labels).forEach(key => { profile[key] = null })
  diseases.forEach(d => { diseaseHistory[d.code] = null; diagnosisYears[d.code] = null })
  return { profile, diseaseHistory, diagnosisYears, revision: 0 }
}
function snapshot(form) { return JSON.stringify({ profile: form.profile, diseaseHistory: form.diseaseHistory, diagnosisYears: form.diagnosisYears }) }
function requestId() { return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => { const n = window.crypto.getRandomValues(new Uint8Array(1))[0] % 16; return (c === 'x' ? n : (n & 3) | 8).toString(16) }) }

export default {
  name: 'DiseaseRisk',
  data() {
    return { diseases, basicChoices, extraChoices, healthLabels: ['极好', '很好', '好', '一般', '差'], currentYear: new Date().getFullYear(),
      form: emptyForm(), savedSnapshot: '', loading: false, saving: false, assessing: false, modelInfo: {}, modelError: '',
      showModels: false, result: null, assessmentError: '', fieldErrors: {}, basicError: '', history: { items: [], total: 0 }, page: 1, pendingRequest: null,
      detail: null, detailVisible: false }
  },
  computed: {
    busy() { return this.saving || this.assessing },
    dirty() { return snapshot(this.form) !== this.savedSnapshot },
    modelReady() { return this.modelInfo.enabled && this.modelInfo.ok && (this.modelInfo.diseases || []).length === 5 },
    knownBasicCount() { return ['weightKg', 'selfRatedHealth', 'smokingCurrent', 'drinkingAny', 'exerciseRegular'].filter(k => this.form.profile[k] != null).length },
    eligibleDiseases() { return this.diseases.filter(d => this.form.diseaseHistory[d.code] === 0) },
    ageRange() {
      const models = (this.modelInfo.diseases || []).filter(d => Number.isFinite(d.age_min) && Number.isFinite(d.age_max))
      return models.length ? { min: Math.min(...models.map(d => d.age_min)), max: Math.max(...models.map(d => d.age_max)) } : { min: 65, max: 90 }
    },
    predictionIssues() {
      const p = this.form.profile, issues = []
      if (p.age == null) issues.push('填写年龄')
      else if (p.age < this.ageRange.min || p.age > this.ageRange.max) issues.push(`当前模型只适用${this.ageRange.min}～${this.ageRange.max}岁，实际年龄超出范围时无法预测`)
      if (p.sexMale == null) issues.push('选择性别')
      if (this.knownBasicCount < 2) issues.push('健康与生活习惯五项至少填写两项')
      if (!this.eligibleDiseases.length) issues.push('至少确认一种疾病的病史为“无”，已有病史或不清楚的项目不能预测首次新报告风险')
      if (p.weightGain10lb === 1 && p.weightLoss10lb === 1) issues.push('过去一年增重和减重至少约4.54kg不能同时为“是”')
      return issues
    }
  },
  created() { this.initialise() },
  methods: {
    async initialise() {
      this.loading = true
      await Promise.all([
        diseaseApi.profile().then(data => this.applyProfile(data)).catch(e => this.$message.error(e.message)),
        diseaseApi.latest().then(data => { this.result = data }).catch(e => this.$message.error(e.message)),
        this.loadHistory(), this.refreshModels()
      ])
      this.loading = false
    },
    applyProfile(data) { this.form = { profile: data.profile, diseaseHistory: data.diseaseHistory, diagnosisYears: data.diagnosisYears, revision: data.revision }; this.savedSnapshot = snapshot(this.form); this.fieldErrors = {}; this.basicError = '' },
    triValue(value) { return value == null ? -1 : value },
    numberValue(value) { return value == null ? undefined : value },
    setNumber(key, value) { this.$set(this.form.profile, key, value == null || Number.isNaN(value) ? null : value); this.$delete(this.fieldErrors, key); if (this.knownBasicCount >= 2) this.basicError = '' },
    setTri(object, key, value) {
      this.$set(object, key, value === -1 ? null : value)
      this.$delete(this.fieldErrors, key)
      if (this.knownBasicCount >= 2) this.basicError = ''
      if (key === 'weightGain10lb' || key === 'weightLoss10lb') { this.$delete(this.fieldErrors, 'weightGain10lb'); this.$delete(this.fieldErrors, 'weightLoss10lb') }
      if (object === this.form.diseaseHistory && value !== 1) this.$set(this.form.diagnosisYears, key, null)
    },
    validate(forPrediction = false) {
      const p = this.form.profile, errors = {}
      if (p.age == null) errors.age = '请填写年龄'
      if (forPrediction && p.age != null && (p.age < this.ageRange.min || p.age > this.ageRange.max)) errors.age = `当前模型适用${this.ageRange.min}～${this.ageRange.max}岁，当前年龄不适用`
      if (forPrediction && p.sexMale == null) errors.sexMale = '预测前请选择性别'
      this.basicError = forPrediction && this.knownBasicCount < 2 ? '请在上述五项中至少填写两项，不清楚不算已填写' : ''
      if (p.weightGain10lb === 1 && p.weightLoss10lb === 1) errors.weightGain10lb = errors.weightLoss10lb = '增重和减重至少约4.54kg不能同时为“是”'
      this.fieldErrors = errors
      const message = Object.values(errors)[0] || this.basicError || (forPrediction && this.predictionIssues[0])
      if (message) {
        this.$nextTick(() => { const field = this.$el.querySelector('.el-form-item.is-error, .field-error'); if (field) field.scrollIntoView({ behavior: 'smooth', block: 'center' }) })
        throw new Error(message)
      }
    },
    async persist() {
      this.validate()
      if (!this.form.revision || this.dirty) {
        const data = await diseaseApi.save(this.form)
        this.applyProfile(data); this.pendingRequest = null
      }
    },
    async saveProfile() {
      this.saving = true
      try { await this.persist(); this.$message.success('健康资料与五项病史已保存') } catch (e) { this.$message.error(e.message) } finally { this.saving = false }
    },
    async predict() {
      this.assessing = true; this.assessmentError = ''
      try {
        this.validate(true)
        if (!this.modelReady) await this.refreshModels()
        if (!this.modelReady) throw new Error(this.modelError || '模型尚未就绪，请检查慢性病模型服务后重试')
        await this.persist()
        if (!this.pendingRequest) this.pendingRequest = { profileRevision: this.form.revision, requestId: requestId() }
        this.result = await diseaseApi.assess(this.pendingRequest); this.pendingRequest = null
        this.page = 1; await this.loadHistory()
        const count = this.result.results.filter(item => item.status === 'predicted').length
        this.$message.success(`已保存本次评估：${count}项生成预测，${this.result.results.length - count}项说明跳过原因`)
      } catch (e) { this.assessmentError = e.message; this.$message.error(e.message) } finally { this.assessing = false }
    },
    async refreshModels() {
      try { this.modelInfo = await diseaseApi.models(); this.modelError = !this.modelInfo.enabled ? '慢性病预测功能已关闭' : !this.modelReady ? '模型尚未全部加载，请检查慢性病模型服务' : '' }
      catch (e) { this.modelInfo = {}; this.modelError = e.message }
    },
    async reloadProfile() {
      try {
        if (this.dirty) await this.$confirm('重新读取会替换尚未保存的修改，是否继续？', '重新读取资料', { type: 'warning' })
        this.applyProfile(await diseaseApi.profile()); this.pendingRequest = null; this.assessmentError = ''; this.$message.success('已读取最新保存的资料')
      } catch (e) { if (e !== 'cancel' && e !== 'close') this.$message.error(e.message || '读取资料失败') }
    },
    async loadHistory() { try { this.history = await diseaseApi.history(this.page, 10) } catch (e) { this.$message.error(e.message) } },
    changePage(page) { this.page = page; this.loadHistory() },
    async openDetail(id) { try { this.detail = await diseaseApi.detail(id); this.detailVisible = true } catch (e) { this.$message.error(e.message) } },
    async importExisting() {
      try {
        const existing = await diseaseApi.legacyProfile()
        if (!existing) { this.$message.info('尚无可引用的基础档案'); return }
        await this.$confirm('将带入原档案中的年龄、性别、吸烟和饮酒信息。请逐项确认，体重和五项疾病史仍需单独填写。', '引用已有资料', { type: 'info' })
        ;['age', 'sexMale', 'smokingCurrent', 'drinkingAny'].forEach(key => { this.$set(this.form.profile, key, existing[key] == null ? null : existing[key]) })
      } catch (e) { if (e !== 'cancel' && e !== 'close') this.$message.error(e.message || '读取已有资料失败') }
    },
    percent(value) { return value == null ? '—' : (Number(value) * 100).toFixed(2) + '%' },
    decimal(value) { return value == null ? '—' : Number(value).toFixed(3) },
    diseaseName(code) { const d = diseases.find(item => item.code === code); return d ? d.name : code },
    statusName(status) { return { predicted: '研究结果', already_diagnosed: '已有病史', insufficient_data: '资料不足', not_applicable: '不适用', model_unavailable: '模型不可用' }[status] || status },
    quality(code, result) { const model = ((result.modelSummary || {}).diseases || []).find(d => d.code === code); return model ? model.quality_note : '研究估计，尚未在中国社区老人中验证。' },
    missingLabels(fields) { return fields.map(f => wire[f.replace(/^profile\./, '')] || (f.startsWith('history_') ? this.diseaseName(f.slice(8)) + '病史' : f.startsWith('disease_history.') ? this.diseaseName(f.slice(16)) + '病史' : f)).join('、') },
    snapshotFields(detail) {
      const p = detail.inputSnapshot.profile, h = detail.inputSnapshot.diseaseHistory
      const fields = Object.keys(labels).map(key => ({ key, label: labels[key], value: p[key] == null ? '不清楚 / 未填写' : key === 'sexMale' ? (p[key] === 1 ? '男' : '女') : key === 'selfRatedHealth' ? this.healthLabels[p[key] - 1] : key === 'age' || key === 'weightKg' ? p[key] : (p[key] === 1 ? '是' : '否') }))
      diseases.forEach(d => fields.push({ key: d.code, label: d.name + '病史', value: h[d.code] == null ? '不清楚' : h[d.code] === 1 ? '有' : '无' }))
      diseases.forEach(d => { const year = (detail.inputSnapshot.diagnosisYears || {})[d.code]; if (year != null) fields.push({ key: d.code + '-year', label: d.name + '确诊年份', value: year }) })
      return fields
    }
  }
}
</script>

<style scoped>
.disease-page { max-width: 1240px; margin: 0 auto; padding: 28px 24px 56px; color: #263c46; }
.page-heading,.section-heading,.card-top { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.page-heading { margin-bottom: 22px; }.eyebrow { color: #52847b; font-size: 13px; letter-spacing: 2px; }
h1 { font-size: 28px; margin: 8px 0; }h2 { font-size: 19px; margin: 0; }h3 { margin: 0; font-size: 18px; }
.page-heading p,.muted { color: #6b7c86; font-size: 13px; line-height: 1.8; }.service-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 12px 0; font-size: 13px; color: #627780; }
.service-dot { height: 8px; width: 8px; border-radius: 50%; background: #b3bbc1; }.service-dot.ready { background: #4e8f7d; }
.section { margin-top: 22px; border-radius: 10px; }.section-heading span { margin-left: 14px; color: #7a8a91; font-size: 12px; font-weight: normal; }
.profile-form >>> .el-input-number,.profile-form >>> .el-select { width: 100%; }
.field-group-heading { margin: 0 0 20px; font-size: 14px; color: #48646d; }.field-group-heading.has-error,.field-error,.required-mark { color: #d65555; }.field-error { font-size: 12px; margin: -10px 0 18px; }.history-hint { color: #71818b; font-size: 12px; }.prediction-readiness { margin-top: 20px; }
.history-answer { display: flex; align-items: center; gap: 24px; flex-wrap: wrap; padding: 16px 0; border-bottom: 1px solid #edf1f3; }.history-answer strong { width: 145px; font-size: 14px; }.history-answer .el-input-number { width: 175px; }
.actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; padding-top: 24px; }.actions .el-button + .el-button { margin-left: 0; }
.results-section > .section-heading { margin: 30px 0 18px; }.disease-grid { display: flex; flex-wrap: wrap; margin-top: 18px; }.disease-grid > .el-col { display: flex; }.disease-card { width: 100%; box-sizing: border-box; min-height: 265px; margin-bottom: 18px; border-radius: 10px; }
.card-top { margin-bottom: 20px; }.probability { font-size: 36px; font-weight: 650; color: #315d69; letter-spacing: -1px; }.estimate-label { color: #73858c; font-size: 12px; margin-top: 4px; }
.quality-note { font-size: 13px; line-height: 1.8; color: #6b7781; min-height: 46px; }.skip-reason { line-height: 1.9; font-size: 14px; padding-top: 12px; }
.disease-card >>> .el-collapse-item__header { font-size: 12px; color: #677d85; }.disease-card >>> .el-collapse-item__content p { margin: 5px 0; }.pagination { text-align: right; margin-top: 18px; }.detail-results { margin-top: 20px; }
.disease-page >>> .el-dialog__header { padding: 20px 24px !important; }.disease-page >>> .el-dialog__body { padding: 0 24px 24px !important; }
@media(max-width:600px) { .disease-page { padding: 20px 12px; }h1 { font-size: 23px; }.section-heading { align-items: flex-start; }.section-heading span { display: block; margin-left: 0; margin-top: 6px; }.history-answer { gap: 14px; }.history-answer strong { width: 100%; }.actions .el-button { width: 100%; }.page-heading { align-items: flex-start; } }
</style>
