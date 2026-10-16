<template>
  <div v-loading="loading" class="patient-health">
    <div class="toolbar"><span>统一健康资料 · {{ updatedAt ? '更新于 '+updatedAt : '正在读取' }}</span><div><el-select v-model="days" size="small" @change="load(false)"><el-option :value="7" label="近7天测量"/><el-option :value="30" label="近30天测量"/><el-option :value="90" label="近90天测量"/></el-select><el-button size="small" @click="load(false)">刷新</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="overview">
      <h2>{{ overview.patient.userName || overview.patient.userAccount }} 的健康档案</h2>
      <el-descriptions :column="2" border size="small"><el-descriptions-item v-for="field in fields" :key="field.key" :label="field.label">{{ field.value }}</el-descriptions-item></el-descriptions>
      <p class="note">档案版本 {{ overview.profile.revision }} · {{ overview.profile.updatedAt || '尚未保存慢性病档案' }}。两类结果按原始时间与版本展示。</p>
      <PersonalizedRiskResults readonly :trend-days="days" :result="overview.latestPersonalized" :history="overview.vitals" :assessed-at="overview.latestPersonalized ? overview.latestPersonalized.assessmentTime : ''" />
      <h2>慢性病 AI 预测</h2><el-alert v-if="overview.latestChronic && overview.latestChronic.profileRevision !== overview.profile.revision" title="当前档案已更新，以下为较早版本的已保存预测；可在历史详情核对当次输入。" type="warning" :closable="false"/><DiseasePredictionResults :assessment="overview.latestChronic" />
      <AssessmentHistory :user-id="patientId" />
      <h2>全部生理测量记录</h2>
      <el-table :data="measurements.items" stripe><el-table-column prop="measuredAt" label="测量时间" min-width="175"/><el-table-column v-for="(label,key) in measurementLabels" :key="key" :prop="key" :label="label" min-width="120"/></el-table>
      <el-pagination :current-page="measurementPage" :page-size="10" :total="measurements.total" layout="prev,pager,next,total" @current-change="p=>{measurementPage=p;loadMeasurements()}"/>
      <h2>原日常健康记录</h2><p class="note">以下是所选时间范围内最近200条日常指标，与本次生理测量分别保留。</p>
      <el-table :data="overview.healthRecords" stripe max-height="300"><el-table-column prop="recordedAt" label="记录时间" min-width="175"/><el-table-column prop="name" label="指标"/><el-table-column prop="value" label="记录值"/><el-table-column prop="unit" label="单位"/></el-table>
    </template>

  </div>
</template>
<script>
import api from '@/api/healthReview'
import healthSync from '@/utils/healthSync'
import { snapshotFields,measurementLabels } from '@/utils/healthRisk'
import PersonalizedRiskResults from './PersonalizedRiskResults.vue'
import DiseasePredictionResults from './DiseasePredictionResults.vue'
import AssessmentHistory from './AssessmentHistory.vue'
export default {
  name:'PatientHealthPanel',mixins:[healthSync],components:{PersonalizedRiskResults,DiseasePredictionResults,AssessmentHistory},props:{patientId:{type:Number,required:true}},
  data(){return {overview:null,measurements:{items:[],total:0},measurementPage:1,days:30,loading:false,error:'',updatedAt:'',measurementLabels}},
  computed:{fields(){if(!this.overview)return [];const p=this.overview.profile,b=this.overview.basicProfile || {};const profile={...p.profile,bmi:b.bmi};if(!p.revision){profile.age=b.age;profile.sexMale=b.sexMale}return snapshotFields({...p,profile})}},
  watch:{patientId:{immediate:true,handler(){this.overview=null;this.measurementPage=1;this.load(false)}}},
  methods:{healthRefreshAllowed(){return !this.loading && !!this.patientId},refreshHealthData(){return this.load(true)},
    async load(quiet){const id=this.patientId;if(!id)return;this.loading=!quiet;try{const [overview,measurements]=await Promise.all([api.overview(id,this.days),api.measurements({userId:id,page:this.measurementPage,pageSize:10})]);if(id!==this.patientId)return;this.overview=overview;this.measurements=measurements;this.error='';this.updatedAt=new Date().toLocaleTimeString()}catch(e){if(id===this.patientId){this.error=e.message;if(e.status===403)this.overview=null}}finally{if(id===this.patientId)this.loading=false}},
    async loadMeasurements(){try{this.measurements=await api.measurements({userId:this.patientId,page:this.measurementPage,pageSize:10})}catch(e){this.error=e.message}},

  }
}
</script>
<style scoped>
.patient-health {padding:0 20px 30px}.toolbar {display:flex;align-items:center;justify-content:space-between;gap:12px;color:#71838b;font-size:12px}.toolbar .el-select {width:150px;margin-right:8px}h2 {font-size:18px;margin:28px 0 18px;color:#29434c}.note {font-size:12px;color:#7b8c94;line-height:1.8}.el-pagination {margin-top:16px;text-align:right}@media(max-width:600px){.patient-health{padding:0 10px 20px}.toolbar{flex-wrap:wrap}} 
</style>
