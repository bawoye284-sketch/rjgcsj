<template>
  <el-card shadow="never" class="assessment-history">
    <div slot="header" class="heading"><b>{{ title }}</b><div><el-select v-model="kind" size="small" @change="page=1;refreshHealthData()"><el-option value="all" label="全部评估"/><el-option value="personalized" label="个性化评估"/><el-option value="chronic" label="慢性病预测"/></el-select><el-button type="text" @click="refreshHealthData()">刷新</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <el-table :data="history.items" stripe><el-table-column prop="assessedAt" label="评估时间" min-width="175"/><el-table-column label="类型" min-width="125"><template slot-scope="s">{{ s.row.kind==='chronic'?'慢性病预测':'个性化评估' }}</template></el-table-column><el-table-column prop="modelVersion" label="模型版本" min-width="220"/><el-table-column label="状态" min-width="140"><template slot-scope="s">{{ statusName(s.row.status) }}</template></el-table-column><el-table-column label="详情" width="115"><template slot-scope="s"><el-button type="text" @click="open(s.row)">输入与原始结果</el-button></template></el-table-column></el-table>
    <el-pagination :current-page="page" :page-size="10" :total="history.total" layout="prev,pager,next,total" @current-change="p=>{page=p;refreshHealthData()}"/>
    <el-dialog title="评估输入与原始结果" :visible.sync="visible" append-to-body width="90%"><AssessmentDetails :record="detail"/></el-dialog>
  </el-card>
</template>
<script>
import api from '@/api/healthReview'
import healthSync from '@/utils/healthSync'
import { statusName } from '@/utils/healthRisk'
import AssessmentDetails from './AssessmentDetails.vue'
export default {name:'AssessmentHistory',mixins:[healthSync],components:{AssessmentDetails},props:{userId:Number,title:{type:String,default:'两类评估历史'}},data(){return {kind:'all',page:1,history:{items:[],total:0},error:'',detail:null,visible:false}},watch:{userId(){this.page=1;this.refreshHealthData()}},created(){this.refreshHealthData()},methods:{statusName,async refreshHealthData(){try{this.history=await api.assessments({userId:this.userId,kind:this.kind,page:this.page,pageSize:10});this.error=''}catch(e){this.error=e.message}},async open(row){try{this.detail=await api.detail(row.kind,row.id);this.visible=true}catch(e){this.$message.error(e.message)}}}}
</script>
<style scoped>.assessment-history{margin-top:24px;border-radius:10px}.heading{display:flex;align-items:center;justify-content:space-between;gap:10px}.heading .el-select{width:135px;margin-right:12px}.el-pagination{text-align:right;margin-top:18px}@media(max-width:600px){.heading{flex-wrap:wrap}}</style>
