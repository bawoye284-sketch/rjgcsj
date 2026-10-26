<template>
  <div class="disease-page" v-loading="loading">
    <div class="page-heading">
      <div><div class="eyebrow">一次填写 · 联合评估</div><h1>健康风险评估</h1>
        <p>填写同一张健康汇总表，一次提交完成个性化生理风险评估与五种慢性病 AI 预测。</p></div>
      <el-tag effect="plain" type="info">AI 辅助预测</el-tag>
    </div>
    <el-alert :closable="false" type="info" show-icon
      title="AI 模型根据健康资料与生理测量生成个性化评估和慢性病预测。结果供健康管理参考，不能作为确诊或安全证明；适用范围与性能见“模型说明”。" />
    <div class="service-line">
      <span :class="['service-dot', { ready: modelReady }]" />
      <span>{{ modelReady ? (modelInfo.personalized_model && modelInfo.personalized_model.model_ready ? '联合模型服务已就绪：个性化评估 + 五病种预测' : '慢性病模型已就绪，个性化模型状态将在提交时确认') : (modelError || '联合模型服务尚未就绪，仍可保存资料和查看历史') }}</span>
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
        </div></div>
      <el-form label-position="top" :disabled="busy" class="profile-form">
        <el-row :gutter="24">
          <el-col :xs="24" :sm="8"><el-form-item label="年龄（岁，必填）" required :error="fieldErrors.age">
            <el-input-number :value="numberValue(form.profile.age)" :min="0" :max="110" :precision="0" placeholder="请填写实际年龄" controls-position="right" @change="v => setNumber('age', v)" />
          </el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="BMI（个性化预测必填）" required :error="fieldErrors.bmi">
            <el-input-number :value="numberValue(bmi)" :min="10" :max="80" :precision="2" controls-position="right" @change="v => setBmi(v)" />
          </el-form-item></el-col>
          <el-col :xs="24" :sm="8"><el-form-item label="性别（预测必填）" required :error="fieldErrors.sexMale">
            <el-select :value="triValue(form.profile.sexMale)" @change="v => setTri(form.profile, 'sexMale', v)">
              <el-option label="请选择性别" :value="-1" /><el-option label="男" :value="1" /><el-option label="女" :value="0" />
            </el-select>
          </el-form-item></el-col>
        </el-row>
        <div class="field-group-heading" :class="{ 'has-error': basicError }">慢性病健康与生活习惯（以下五项至少填写两项，已填 {{ knownBasicCount }}/5 项）</div>
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
      <p class="muted">年龄和性别只填一次，两类评估共用。BMI与体重是不同指标，请分别填写；BMI供个性化模型使用，体重供慢性病模型使用。慢性病模型适用 {{ ageRange.min }}～{{ ageRange.max }} 岁，健康生活五项至少填写两项。未知选填项保持“不清楚”。</p>
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
    </el-card>

    <el-card shadow="never" class="section">
      <div slot="header" class="section-heading"><div><b>三、本次生理测量</b><span>个性化评估至少填写一项实测值，未测项留空</span></div></div>
      <el-form label-position="top" :disabled="busy" class="profile-form">
        <el-row :gutter="24"><el-col v-for="field in measurementFields" :key="field.key" :xs="24" :sm="8">
          <el-form-item :label="field.label + '（六项任选）'" :error="fieldErrors[field.key]">
            <el-input-number :value="numberValue(measurement[field.key])" :min="field.min" :max="field.max" :precision="field.precision" controls-position="right" @change="v => setMeasurement(field.key, v)" />
          </el-form-item>
        </el-col></el-row>
      </el-form>
      <p v-if="measurementError" class="field-error">{{ measurementError }}</p>
      <p class="muted">本次测量在个性化评估成功后保存并加入趋势，不会把上次测量自动当作本次实测。仅保存健康资料时不添加测量记录。</p>
      <el-alert class="prediction-readiness" :closable="false" show-icon :type="personalIssues.length ? 'warning' : 'info'" :title="personalIssues.length ? '个性化评估还需：' + personalIssues.join('；') : '个性化评估资料已齐备'" />
      <el-alert class="prediction-readiness" :closable="false" show-icon :type="predictionIssues.length ? 'warning' : 'info'" :title="predictionIssues.length ? '慢性病预测还需：' + predictionIssues.join('；') : '慢性病本次可预测 ' + eligibleDiseases.length + ' 种，其余项目会说明跳过原因'" />
      <div class="actions">
        <el-button :loading="saving" :disabled="busy" @click="saveProfile">保存健康资料</el-button>
        <el-button type="primary" :loading="assessing" :disabled="busy" @click="predict">保存并联合预测</el-button>
        <span class="muted">{{ dirty ? '有未保存的修改' : (form.revision ? '资料已保存' : '尚未保存资料') }}</span>
      </div>
    </el-card>

    <PersonalizedRiskResults :result="personalResult" :history="measurementHistory" :state="personalState" :message="personalMessage" :stale="personalStale" :assessed-at="personalAssessedAt" />

    <section class="section results-section">
      <div class="section-heading"><div><h2>五、慢性病预测结果</h2><span v-if="result">{{ result.assessedAt }} · 资料版本 {{ result.profileRevision }}</span></div><el-tag :type="diseaseState === 'error' ? 'danger' : diseaseState === 'success' ? 'success' : 'info'">{{ branchLabel(diseaseState) }}</el-tag></div>
      <el-alert v-if="result && (diseaseDirty || result.profileRevision !== form.revision)" :closable="false" type="warning" title="以下结果对应较早的资料状态，请重新预测以更新结果。" />
      <el-alert v-if="assessmentError" :closable="false" type="error" :title="assessmentError + '。下方如有结果，为上次成功保存的记录。'" />
      <DiseasePredictionResults :assessment="result" />
    </section>

    <MeasurementAlerts ref="measurementAlerts" />
    <AssessmentHistory ref="assessmentHistory" title="六、两类评估历史" />
  </div>
</template>

<script>
import diseaseApi from '@/api/diseaseRisk'
import personalApi from '@/api/personalizedRisk'
import PersonalizedRiskResults from '@/components/risk/PersonalizedRiskResults.vue'
import DiseasePredictionResults from '@/components/risk/DiseasePredictionResults.vue'
import AssessmentHistory from '@/components/risk/AssessmentHistory.vue'
import MeasurementAlerts from '@/components/risk/MeasurementAlerts.vue'
import healthSync, { notifyHealthChange } from '@/utils/healthSync'
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
const measurementFields = [
  { key: 'systolicPressure', label: '收缩压（mm Hg, 正常范围:90～119mmHg）', min: 40, max: 300, precision: 0 },
  { key: 'diastolicPressure', label: '舒张压（mmHg, 正常范围:60～79mmHg）', min: 20, max: 200, precision: 0 },
  { key: 'heartRate', label: '心率（次/分, 正常范围:60～100次/分）', min: 30, max: 220, precision: 0 },
  { key: 'spo2', label: '血氧（%）, 正常范围:95～100%', min: 60, max: 100, precision: 1 },
  { key: 'temperature', label: '体温（℃）, 正常范围:36.5～37.3℃', min: 32, max: 43, precision: 1 },
  { key: 'respiratoryRate', label: '呼吸频率（次/分）, 正常范围:12～18次/分', min: 1, max: 80, precision: 0 }
]
function emptyForm() {
  const profile = {}, diseaseHistory = {}, diagnosisYears = {}
  Object.keys(labels).forEach(key => { profile[key] = null })
  diseases.forEach(d => { diseaseHistory[d.code] = null; diagnosisYears[d.code] = null })
  return { profile, diseaseHistory, diagnosisYears, revision: 0 }
}
function snapshot(form) { return JSON.stringify({ profile: form.profile, diseaseHistory: form.diseaseHistory, diagnosisYears: form.diagnosisYears }) }
function requestId() { return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => { const n = window.crypto.getRandomValues(new Uint8Array(1))[0] % 16; return (c === 'x' ? n : (n & 3) | 8).toString(16) }) }

export default {
  name: 'HealthAssessment',
  mixins: [healthSync],
  components: { PersonalizedRiskResults, DiseasePredictionResults, AssessmentHistory, MeasurementAlerts },
  data() {
    return { diseases, basicChoices, extraChoices, healthLabels: ['极好', '很好', '好', '一般', '差'], currentYear: new Date().getFullYear(),
      form: emptyForm(), savedSnapshot: '', loading: false, saving: false, assessing: false, modelInfo: {}, modelError: '',
      showModels: false, result: null, assessmentError: '', fieldErrors: {}, basicError: '', pendingRequest: null,
      bmi: null, savedBmi: null, measurementFields,
      measurement: Object.fromEntries(measurementFields.map(f => [f.key, null])), measurementError: '', measurementHistory: [],
      personalResult: null, personalState: 'idle', personalMessage: '', personalAssessedAt: '', personalResultSignature: null,
      savedMeasurement: '', diseaseState: 'idle', pendingBatch: null }
  },
  computed: {
    busy() { return this.saving || this.assessing },
    diseaseDirty() { return snapshot(this.form) !== this.savedSnapshot },
    dirty() { return this.diseaseDirty || this.bmi !== this.savedBmi || (this.savedMeasurement && JSON.stringify(this.measurement) !== this.savedMeasurement) },
    personalStale() { return !this.personalResultSignature || this.personalSignature() !== this.personalResultSignature },
    modelReady() { return this.modelInfo.enabled && this.modelInfo.ok && (this.modelInfo.diseases || []).length === 5 },
    knownBasicCount() { return ['weightKg', 'selfRatedHealth', 'smokingCurrent', 'drinkingAny', 'exerciseRegular'].filter(k => this.form.profile[k] != null).length },
    eligibleDiseases() { return this.diseases.filter(d => this.form.diseaseHistory[d.code] === 0) },
    ageRange() {
      const models = (this.modelInfo.diseases || []).filter(d => Number.isFinite(d.age_min) && Number.isFinite(d.age_max))
      return models.length ? { min: Math.min(...models.map(d => d.age_min)), max: Math.max(...models.map(d => d.age_max)) } : { min: 65, max: 90 }
    },
    personalIssues() {
      const issues = [], p = this.form.profile
      if (p.age == null) issues.push('填写年龄')
      if (p.sexMale == null) issues.push('选择性别')
      if (this.bmi == null) issues.push('填写BMI')
      if (!measurementFields.some(f => this.measurement[f.key] != null)) issues.push('填写至少一项本次实测值')
      return issues
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
    healthRefreshAllowed() { return !this.busy && !this.loading },
    async refreshHealthData() {
      if (this.busy || this.loading) return
      if (this.$refs.measurementAlerts) this.$refs.measurementAlerts.load()
      const previousResultId = this.result && this.result.id
      const operations = [this.loadPersonalLatest(), this.loadMeasurementHistory(), this.refreshModels(), diseaseApi.latest().then(data=>{if (!this.busy && (this.result && this.result.id) === previousResultId) this.result=data})]
      if (!this.dirty) operations.push(this.loadProfiles(true))
      await Promise.all(operations)
    },
    async initialise() {
      this.loading = true
      await Promise.all([
        this.loadProfiles(),
        diseaseApi.latest().then(data => { this.result = data }).catch(e => this.$message.error(e.message)),
        this.loadPersonalLatest(), this.loadMeasurementHistory(), this.refreshModels()
      ])
      this.savedMeasurement = JSON.stringify(this.measurement)
      this.loading = false
    },
    async loadProfiles(preserveDraft = false) {
      const draft = JSON.stringify(this.personalInput()) + snapshot(this.form)
      const results = await Promise.allSettled([diseaseApi.profile(), personalApi.profile()])
      if (preserveDraft && (this.busy || draft !== JSON.stringify(this.personalInput()) + snapshot(this.form))) return
      if (results[0].status === 'fulfilled') this.applyProfile(results[0].value)
      else this.$message.error('慢性病资料读取失败：' + results[0].reason.message)
      if (results[1].status === 'fulfilled') {
        const p = results[1].value
        this.bmi = p ? p.bmi : null; this.savedBmi = this.bmi
        if (p && this.form.revision === 0) ['age', 'sexMale'].forEach(k => { this.$set(this.form.profile, k, p[k] == null ? null : p[k]) })
      } else this.$message.error('个性化资料读取失败：' + results[1].reason.message)
    },
    async loadPersonalLatest() {
      try {
        const row = await personalApi.latest()
        if (this.busy || (row && this.personalResult && Number(row.assessmentId) < Number(this.personalResult.assessmentId))) return
        if (row && row.assessmentBasis === 'NUMERIC5_AGGREGATE_HISTORY_V1') {
          if (!this.personalResult || Number(this.personalResult.assessmentId) !== Number(row.assessmentId)) this.personalResultSignature = null
          this.personalResult = { ...row, baselineAvailable: Boolean(Number(row.baselineAvailable)), news2Complete: Boolean(Number(row.news2Complete)), reasons: row.reasonsJson ? JSON.parse(row.reasonsJson) : [] }
          this.personalAssessedAt = row.assessmentTime || ''
        }
      } catch (e) { this.personalMessage = '上次个性化结果读取失败：' + e.message }
    },
    async loadMeasurementHistory() { try { this.measurementHistory = await personalApi.history() } catch (e) { this.personalMessage = '测量历史读取失败：' + e.message } },
    applyProfile(data, clearErrors = true) { this.form = { profile: data.profile, diseaseHistory: data.diseaseHistory, diagnosisYears: data.diagnosisYears, revision: data.revision }; this.savedSnapshot = snapshot(this.form); if (clearErrors) { this.fieldErrors = {}; this.basicError = '' } },
    personalInput() { return { profile: { age: this.form.profile.age, sexMale: this.form.profile.sexMale, bmi: this.bmi }, measurement: { ...this.measurement } } },
    personalSignature() { return JSON.stringify(this.personalInput()) },
    setBmi(value) { this.bmi = value == null || Number.isNaN(value) ? null : value; this.$delete(this.fieldErrors, 'bmi') },
    setMeasurement(key, value) { this.$set(this.measurement, key, value == null || Number.isNaN(value) ? null : value); this.measurementError = ''; this.$delete(this.fieldErrors, key) },
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
    validateDraft() {
      const p = this.form.profile, errors = {}
      if (p.age == null) errors.age = '请填写年龄'
      this.basicError = ''
      if (p.weightGain10lb === 1 && p.weightLoss10lb === 1) errors.weightGain10lb = errors.weightLoss10lb = '增重和减重至少约4.54kg不能同时为“是”'
      this.fieldErrors = errors
      const message = Object.values(errors)[0]
      if (message) {
        this.$nextTick(() => { const field = this.$el.querySelector('.el-form-item.is-error, .field-error'); if (field) field.scrollIntoView({ behavior: 'smooth', block: 'center' }) })
        throw new Error(message)
      }
    },
    async persist(check = true) {
      if (check) this.validateDraft()
      if (!this.form.revision || this.diseaseDirty) {
        const data = await diseaseApi.save(this.form)
        this.applyProfile(data, check); this.pendingRequest = null
      }
    },
    async saveProfile() {
      this.saving = true
      try {
        this.validateDraft()
        const saves = [this.persist()]
        const canSavePersonal = this.bmi != null && this.form.profile.sexMale != null
        if (canSavePersonal) saves.push(personalApi.saveBasic(this.personalInput().profile).then(() => { this.savedBmi = this.bmi }))
        const states = await Promise.allSettled(saves), failed = states.filter(s => s.status === 'rejected')
        if (failed.length) throw new Error('部分健康资料未保存：' + failed.map(s => s.reason.message).join('；'))
        this.$message.success(canSavePersonal ? '两类健康档案已保存；本次测量会在联合预测成功后记录' : '健康资料已暂存；补齐性别和BMI后可同时保存个性化档案')
        notifyHealthChange()
      } catch (e) { this.$message.error(e.message) } finally { this.saving = false }
    },
    async predict() {
      this.assessing = true; this.assessmentError = ''
      try {
        const errors = {}
        if (this.form.profile.age == null) errors.age = '请填写共用年龄'
        if (this.form.profile.sexMale == null) errors.sexMale = '请填写共用性别'
        this.fieldErrors = errors
        if (Object.keys(errors).length) throw new Error(Object.values(errors).join('；'))
        if (this.bmi == null) this.$set(this.fieldErrors, 'bmi', '个性化预测需要填写BMI')
        this.basicError = this.knownBasicCount < 2 ? '慢性病预测需要在上述五项中至少填写两项' : ''
        this.measurementError = measurementFields.some(f => this.measurement[f.key] != null) ? '' : '个性化评估需要至少一项本次实测值'
        const diseaseSignature = snapshot(this.form), personalSignature = this.personalSignature()
        const signature = JSON.stringify({ diseaseSignature, personalSignature })
        if (!this.pendingBatch || this.pendingBatch.signature !== signature) {
          const previous = this.pendingBatch
          this.pendingBatch = { signature, diseaseSignature, personalSignature,
            personalRequestId: previous && previous.personalSignature === personalSignature ? previous.personalRequestId : requestId(),
            personalDone: Boolean(previous && previous.personalDone && previous.personalSignature === personalSignature),
            diseaseDone: Boolean(previous && previous.diseaseDone && previous.diseaseSignature === diseaseSignature) }
        }
        await Promise.all([this.runPersonal(), this.runDisease()])
        const succeeded = [this.personalState, this.diseaseState].filter(s => s === 'success').length
        const message = succeeded === 2 ? '联合评估完成：个性化结果与五病种结果均已保存' : succeeded === 1 ? '一类评估已完成，另一类的缺项或失败原因已显示，可补齐后重试' : '本次两类评估均未完成，请查看各自的缺项或服务提示'
        this.$message({ type: succeeded === 2 ? 'success' : 'warning', message })
        if (succeeded === 2) this.pendingBatch = null
      } catch (e) { this.assessmentError = e.message; this.$message.error(e.message) } finally { this.assessing = false }
    },
    async runPersonal() {
      if (this.pendingBatch.personalDone) { this.personalState = 'success'; this.personalMessage = '相同汇总表的个性化评估已完成，本次重试未重复添加测量'; return }
      if (this.personalIssues.length) { this.personalState = 'skipped'; this.personalMessage = '本次未运行：' + this.personalIssues.join('；'); return }
      this.personalState = 'running'; this.personalMessage = ''
      const input = this.personalInput(), signature = this.personalSignature()
      input.requestId = this.pendingBatch.personalRequestId
      try {
        this.personalResult = await personalApi.assessCurrent(input)
        this.personalResultSignature = signature; this.savedBmi = input.profile.bmi; this.savedMeasurement = JSON.stringify(input.measurement)
        this.personalAssessedAt = this.personalResult.assessmentTime || ''
        this.personalState = 'success'; this.pendingBatch.personalDone = true
        this.personalMessage = '本次汇总表的生理测量与个性化结果已保存'
        if (this.$refs.measurementAlerts) await this.$refs.measurementAlerts.load(this.personalResult.measurementId)
        notifyHealthChange()
        await this.loadMeasurementHistory()
      } catch (e) { this.personalState = 'error'; this.personalMessage = e.message + '。如有下方结果，为上次已保存记录。' }
    },
    async runDisease() {
      if (this.pendingBatch.diseaseDone) { this.diseaseState = 'success'; return }
      if (this.predictionIssues.length) { this.diseaseState = 'skipped'; this.assessmentError = '慢性病本次未运行：' + this.predictionIssues.join('；'); return }
      this.diseaseState = 'running'
      try {
        if (!this.modelReady) await this.refreshModels()
        if (!this.modelReady) throw new Error(this.modelError || '慢性病模型尚未就绪')
        await this.persist(false)
        if (!this.pendingRequest) this.pendingRequest = { profileRevision: this.form.revision, requestId: requestId() }
        this.result = await diseaseApi.assess(this.pendingRequest); this.pendingRequest = null
        this.diseaseState = 'success'; this.pendingBatch.diseaseDone = true; notifyHealthChange()
      } catch (e) { this.diseaseState = 'error'; this.assessmentError = e.message }
    },
    branchLabel(state) { return { idle: '待联合评估', running: '评估中', success: '本次已完成', error: '本次未完成', skipped: '本次未运行' }[state] },
    async refreshModels() {
      try { this.modelInfo = await diseaseApi.models(); this.modelError = !this.modelInfo.enabled ? '慢性病预测功能已关闭' : !this.modelReady ? '模型尚未全部加载，请检查慢性病模型服务' : '' }
      catch (e) { this.modelInfo = {}; this.modelError = e.message }
    },
    async reloadProfile() {
      try {
        if (this.dirty) await this.$confirm('重新读取会替换尚未保存的修改，是否继续？', '重新读取资料', { type: 'warning' })
        await this.loadProfiles(); this.pendingRequest = null; this.pendingBatch = null; this.assessmentError = ''; this.$message.success('已读取最新保存的两类健康资料，本次测量保留')
      } catch (e) { if (e !== 'cancel' && e !== 'close') this.$message.error(e.message || '读取资料失败') }
    },
    percent(value) { return value == null ? '—' : (Number(value) * 100).toFixed(2) + '%' },
    decimal(value) { return value == null ? '—' : Number(value).toFixed(3) },
    diseaseName(code) { const d = diseases.find(item => item.code === code); return d ? d.name : code },
    statusName(status) { return { predicted: '预测完成', already_diagnosed: '已有病史', insufficient_data: '资料不足', not_applicable: '不适用', model_unavailable: '模型不可用' }[status] || status },

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
.bracelet-note { margin-bottom: 20px; }
.field-group-heading { margin: 0 0 20px; font-size: 14px; color: #48646d; }.field-group-heading.has-error,.field-error,.required-mark { color: #d65555; }.field-error { font-size: 12px; margin: -10px 0 18px; }.history-hint { color: #71818b; font-size: 12px; }.prediction-readiness { margin-top: 20px; }
.history-answer { display: flex; align-items: center; gap: 24px; flex-wrap: wrap; padding: 16px 0; border-bottom: 1px solid #edf1f3; }.history-answer strong { width: 145px; font-size: 14px; }.history-answer .el-input-number { width: 175px; }
.actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; padding-top: 24px; }.actions .el-button + .el-button { margin-left: 0; }
.results-section > .section-heading { margin: 30px 0 18px; }.disease-grid { display: flex; flex-wrap: wrap; margin-top: 18px; }.disease-grid > .el-col { display: flex; }.disease-card { width: 100%; box-sizing: border-box; min-height: 265px; margin-bottom: 18px; border-radius: 10px; }
.card-top { margin-bottom: 20px; }.probability { font-size: 36px; font-weight: 650; color: #315d69; letter-spacing: -1px; }.estimate-label { color: #73858c; font-size: 12px; margin: 4px 0 20px; }
.skip-reason { line-height: 1.9; font-size: 14px; padding-top: 12px; }
.disease-card >>> .el-collapse-item__header { font-size: 12px; color: #677d85; }.disease-card >>> .el-collapse-item__content p { margin: 5px 0; }.pagination { text-align: right; margin-top: 18px; }.detail-results { margin-top: 20px; }
.disease-page >>> .el-dialog__header { padding: 20px 24px !important; }.disease-page >>> .el-dialog__body { padding: 0 24px 24px !important; }
@media(max-width:600px) { .disease-page { padding: 20px 12px; }h1 { font-size: 23px; }.section-heading { align-items: flex-start; }.section-heading span { display: block; margin-left: 0; margin-top: 6px; }.history-answer { gap: 14px; }.history-answer strong { width: 100%; }.actions .el-button { width: 100%; }.page-heading { align-items: flex-start; } }
</style>
