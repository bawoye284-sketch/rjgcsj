<template>
  <div v-if="record">
    <p>{{ record.kind==='chronic'?'慢性病 AI 预测':'个性化生理风险评估' }} · {{ assessment.assessedAt || assessment.assessmentTime }} · {{ assessment.modelVersion }}</p>
    <el-descriptions v-if="assessment.inputSnapshot" title="当次输入快照" :column="2" border size="small"><el-descriptions-item v-for="field in fields" :key="field.key" :label="field.label">{{ field.value }}</el-descriptions-item></el-descriptions>
    <el-alert v-else title="这条历史记录未保存输入快照，无法还原当时资料；显示的结果为原始保存值。" type="info" :closable="false" />
    <p v-if="record.kind==='personalized' && assessment.inputSnapshot && assessment.inputSnapshot.history">当次计算参考了 {{ assessment.inputSnapshot.history.length }} 条既往测量，已随快照保存。</p>
    <DiseasePredictionResults v-if="record.kind==='chronic'" :assessment="assessment" />
    <PersonalizedRiskResults v-else readonly :result="assessment" state="success" :assessed-at="assessment.assessmentTime" :show-trend="false" />
  </div>
</template>
<script>
import { snapshotFields } from '@/utils/healthRisk'
import DiseasePredictionResults from './DiseasePredictionResults.vue'
import PersonalizedRiskResults from './PersonalizedRiskResults.vue'
export default { name:'AssessmentDetails',components:{DiseasePredictionResults,PersonalizedRiskResults},props:{record:{default:null}},computed:{assessment(){return this.record.assessment},fields(){return snapshotFields(this.assessment.inputSnapshot)}} }
</script>
