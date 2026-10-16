<template><div><div class="summary"><el-card v-for="field in fields" :key="field.key" shadow="never"><span>{{ field.label }}</span><strong>{{ stats[field.key] == null ? '—' : stats[field.key] }}</strong></el-card></div><p><el-button type="text" @click="$router.push('/healthReviewManage')">查看联合评估、测量与模型状态</el-button><span class="error" v-if="error">{{ error }}</span></p></div></template>
<script>
import api from '@/api/healthReview'
import healthSync from '@/utils/healthSync'
export default {name:'HealthSummaryCards',mixins:[healthSync],data(){return {stats:{},error:'',fields:[{key:'patients',label:'健康档案用户'},{key:'measurements',label:'生理测量记录'},{key:'personalized',label:'个性化评估'},{key:'chronic',label:'慢性病评估'}]}},created(){this.refreshHealthData()},methods:{async refreshHealthData(){try{this.stats=await api.statistics({});this.error=''}catch(e){this.error=e.message}}}}
</script>
<style scoped>.summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.summary span,.error{font-size:12px;color:#758892}.summary strong{display:block;font-size:27px;color:#315d69;margin-top:10px}@media(max-width:800px){.summary{grid-template-columns:repeat(2,minmax(0,1fr))}}</style>
