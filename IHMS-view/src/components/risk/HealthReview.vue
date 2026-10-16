<template>
  <div class="health-review" v-loading="loading">
    <header><div><h1>{{ doctor ? '患者健康管理' : '联合评估管理' }}</h1><p>{{ doctor?'仅显示与本人有咨询关联的患者':'统一查询健康档案、测量记录及两类 AI 评估' }} · 每15秒自动更新，回到页面时立即检查</p></div><div><el-button size="small" @click="refreshHealthData(false)">立即刷新</el-button><el-button v-if="doctor" size="small" @click="$router.push('/doctor-home')">返回工作台</el-button></div></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <el-form inline size="small" class="filters" @submit.native.prevent="apply">
      <el-form-item label="姓名/账号"><el-input v-model="filters.keyword" clearable placeholder="搜索用户" @keyup.enter.native="apply"/></el-form-item>
      <el-form-item label="用户ID"><el-input-number v-model="filters.userId" :min="1" :controls="false" placeholder="全部"/></el-form-item>
      <el-form-item label="记录日期"><el-date-picker v-model="range" type="daterange" value-format="yyyy-MM-dd" start-placeholder="开始日期" end-placeholder="结束日期"/></el-form-item>
      <el-form-item><el-button type="primary" @click="apply">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
    </el-form>
    <div class="stat-grid"><el-card v-for="item in statFields" :key="item.key" shadow="never"><span>{{ item.label }}</span><strong>{{ stats[item.key] == null ? '—' : stats[item.key] }}</strong></el-card></div>
    <p class="note">人数按当前用户筛选统计；测量和评估数量同时按日期筛选。病种及状态筛选用于下方评估列表。</p>
    <el-tabs v-model="tab" @tab-click="tabChanged">
      <el-tab-pane label="健康档案" name="patients">
        <el-table :data="patients.items" stripe><el-table-column prop="userId" label="用户ID" width="90"/><el-table-column prop="userName" label="姓名" min-width="120"/><el-table-column prop="userAccount" label="账号" min-width="140"/><el-table-column prop="lastMeasuredAt" label="最近测量" min-width="175"/><el-table-column prop="lastPersonalizedAt" label="个性化评估时间" min-width="175"/><el-table-column prop="lastChronicAt" label="慢性病评估时间" min-width="175"/><el-table-column label="操作" width="115"><template slot-scope="s"><el-button type="text" @click="openPatient(s.row.userId)">完整健康资料</el-button></template></el-table-column></el-table>
        <el-pagination :current-page="patientPage" :page-size="10" :total="patients.total" layout="prev,pager,next,total" @current-change="p=>{patientPage=p;safeLoad(loadPatients)}"/>
      </el-tab-pane>
      <el-tab-pane label="两类评估历史" name="assessments">
        <div class="subfilters"><el-select v-model="filters.kind" size="small" @change="assessmentPage=1;safeLoad(loadAssessments)"><el-option value="all" label="全部评估"/><el-option value="personalized" label="个性化评估"/><el-option value="chronic" label="慢性病预测"/></el-select>
          <el-select v-model="filters.diseaseCode" clearable placeholder="全部病种" size="small" @change="assessmentPage=1;safeLoad(loadAssessments)"><el-option v-for="d in diseases" :key="d.code" :label="d.name" :value="d.code"/></el-select>
          <el-select v-model="filters.status" clearable placeholder="全部状态" size="small" @change="assessmentPage=1;safeLoad(loadAssessments)"><el-option v-for="s in statuses" :key="s" :label="statusName(s)" :value="s"/></el-select>
        </div>
        <el-table :data="assessments.items" stripe><el-table-column prop="assessedAt" label="时间" min-width="175"/><el-table-column prop="userName" label="姓名" min-width="110"/><el-table-column prop="userId" label="用户ID" width="90"/><el-table-column label="类型" width="125"><template slot-scope="s">{{ s.row.kind==='chronic'?'慢性病预测':'个性化评估' }}</template></el-table-column><el-table-column prop="modelVersion" label="模型版本" min-width="225"/><el-table-column label="状态" min-width="140"><template slot-scope="s">{{ statusName(s.row.status) }}</template></el-table-column><el-table-column prop="riskLevel" label="生理风险" width="95"/><el-table-column label="操作" width="150"><template slot-scope="s"><el-button type="text" @click="openAssessment(s.row)">输入与结果</el-button><el-button type="text" @click="openPatient(s.row.userId)">用户</el-button></template></el-table-column></el-table>
        <el-pagination :current-page="assessmentPage" :page-size="10" :total="assessments.total" layout="prev,pager,next,total" @current-change="p=>{assessmentPage=p;safeLoad(loadAssessments)}"/>
      </el-tab-pane>
      <el-tab-pane label="生理测量记录" name="measurements">
        <el-table :data="measurements.items" stripe><el-table-column prop="measuredAt" label="测量时间" min-width="175"/><el-table-column prop="userName" label="姓名" width="110"/><el-table-column prop="userId" label="用户ID" width="90"/><el-table-column v-for="(label,key) in measurementLabels" :key="key" :prop="key" :label="label" min-width="120"/><el-table-column label="操作" width="100"><template slot-scope="s"><el-button type="text" @click="openPatient(s.row.userId)">健康资料</el-button></template></el-table-column></el-table>
        <el-pagination :current-page="measurementPage" :page-size="10" :total="measurements.total" layout="prev,pager,next,total" @current-change="p=>{measurementPage=p;safeLoad(loadMeasurements)}"/>
      </el-tab-pane>
      <el-tab-pane label="病种统计与模型状态" name="models">
        <el-table :data="stats.diseases || []"><el-table-column label="病种"><template slot-scope="s">{{ diseaseName(s.row.diseaseCode) }}</template></el-table-column><el-table-column prop="total" label="保存结果数"/><el-table-column prop="predicted" label="生成预测"/><el-table-column prop="thresholdReached" label="达到模型阈值"/><el-table-column prop="skipped" label="已有病史/未评估"/></el-table>
        <p class="note">达到阈值的记录数用于模型输出统计，不代表确诊人数；历史与重复评估按记录计数。</p>
        <el-alert v-if="modelError" :title="modelError" type="warning" :closable="false"/>
        <p>联合服务：{{ modelInfo.service || '未连接' }} · 个性化版本：{{ (modelInfo.personalized_model || {}).model_version || '未连接' }} · 慢性病版本：{{ modelInfo.model_version || '未连接' }}</p>
        <el-table :data="modelInfo.diseases || []"><el-table-column prop="name" label="病种"/><el-table-column label="状态"><template slot-scope="s">{{ s.row.ready?'已加载':'未就绪' }}</template></el-table-column><el-table-column label="年龄范围"><template slot-scope="s">{{ s.row.age_min }}～{{ s.row.age_max }}岁</template></el-table-column><el-table-column label="AUC"><template slot-scope="s">{{ decimal((s.row.metrics || {}).roc_auc) }}</template></el-table-column><el-table-column label="召回率"><template slot-scope="s">{{ percent((s.row.metrics || {}).sensitivity) }}</template></el-table-column></el-table>
      </el-tab-pane>
    </el-tabs>
    <p class="note">最近更新：{{ updatedAt || '尚未更新' }}。各端读取同一份保存结果，查看操作不会重新运行模型。</p>
    <el-drawer :visible.sync="patientVisible" size="94%" :title="'用户 '+selectedId+' 的完整健康资料'" destroy-on-close><PatientHealthPanel v-if="patientVisible && selectedId" :patient-id="selectedId"/></el-drawer>
    <el-dialog title="评估输入与结果" :visible.sync="assessmentVisible" width="90%"><AssessmentDetails :record="assessmentDetail"/></el-dialog>
  </div>
</template>
<script>
import api from '@/api/healthReview'
import healthSync from '@/utils/healthSync'
import { diseases,measurementLabels,statusName,diseaseName,percent } from '@/utils/healthRisk'
import PatientHealthPanel from './PatientHealthPanel.vue'
import AssessmentDetails from './AssessmentDetails.vue'
const empty=()=>({items:[],total:0})
export default {name:'HealthReview',mixins:[healthSync],components:{PatientHealthPanel,AssessmentDetails},props:{doctor:Boolean},
  data(){return {loading:false,error:'',filters:{keyword:'',userId:undefined,kind:'all',diseaseCode:'',status:''},applied:{},range:[],tab:'patients',patientPage:1,assessmentPage:1,measurementPage:1,patients:empty(),assessments:empty(),measurements:empty(),stats:{},modelInfo:{},modelError:'',updatedAt:'',selectedId:null,patientVisible:false,assessmentVisible:false,assessmentDetail:null,diseases,measurementLabels,statuses:['completed','completed_with_skips','predicted','already_diagnosed','insufficient_data','not_applicable','model_unavailable'],statFields:[{key:'patients',label:'可查看用户'},{key:'measurements',label:'生理测量记录'},{key:'personalized',label:'个性化评估'},{key:'chronic',label:'慢性病评估'}]}},
  created(){this.refreshHealthData(false);const id=Number(this.$route.query.patientId);if(this.doctor && Number.isSafeInteger(id) && id>0)this.openPatient(id)},methods:{statusName,diseaseName,percent,decimal(v){return v==null?'—':Number(v).toFixed(3)},healthRefreshAllowed(){return !this.loading},
    async safeLoad(operation){try{await operation()}catch(e){this.error=e.message}},
    params(){return {...this.applied}},
    async apply(){this.applied={keyword:this.filters.keyword,userId:this.filters.userId,from:this.range && this.range[0],to:this.range && this.range[1]};this.patientPage=this.assessmentPage=this.measurementPage=1;await this.refreshHealthData(false)},
    reset(){this.filters={keyword:'',userId:undefined,kind:'all',diseaseCode:'',status:''};this.range=[];this.apply()},
    async refreshHealthData(quiet){this.loading=!quiet;try{await Promise.all([this.loadPatients(),this.loadAssessments(),this.loadMeasurements(),api.statistics(this.params()).then(s=>{this.stats=s}),this.loadModels()]);this.error='';this.updatedAt=new Date().toLocaleTimeString()}catch(e){this.error=e.message}finally{this.loading=false}},
    async loadPatients(){this.patients=await api.patients({...this.params(),page:this.patientPage,pageSize:10})},
    async loadAssessments(){try{this.assessments=await api.assessments({...this.params(),kind:this.filters.kind,diseaseCode:this.filters.diseaseCode,status:this.filters.status,page:this.assessmentPage,pageSize:10})}catch(e){this.error=e.message;throw e}},
    async loadMeasurements(){this.measurements=await api.measurements({...this.params(),page:this.measurementPage,pageSize:10})},
    async loadModels(){try{this.modelInfo=await api.models();this.modelError=''}catch(e){this.modelError=e.message}},
    tabChanged(){this.refreshHealthData(false)},
    openPatient(id){this.selectedId=Number(id);this.patientVisible=true},
    async openAssessment(row){try{this.assessmentDetail=await api.detail(row.kind,row.id);this.assessmentVisible=true}catch(e){this.$message.error(e.message)}}
  }
}
</script>
<style scoped>
.health-review {padding:24px;background:#fff;border-radius:10px;color:#304a55}header {display:flex;align-items:center;justify-content:space-between;gap:14px;margin-bottom:22px}h1 {font-size:24px;margin:0 0 8px}header p,.note {font-size:12px;color:#73858e;line-height:1.8}.filters {margin-top:20px}.stat-grid {display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.stat-grid span {font-size:12px;color:#788b95}.stat-grid strong {display:block;font-size:28px;color:#315d69;margin-top:10px}.el-pagination {margin:18px 0;text-align:right}.subfilters {display:flex;gap:10px;margin-bottom:18px}.subfilters .el-select {max-width:180px}.health-review >>> .el-dialog__header {padding:20px !important}.health-review >>> .el-dialog__body {padding:0 20px 20px !important}@media(max-width:800px){.health-review{padding:14px}header{align-items:flex-start;flex-wrap:wrap}.stat-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.filters >>> .el-date-editor{max-width:100%}.subfilters{flex-wrap:wrap}}
</style>
