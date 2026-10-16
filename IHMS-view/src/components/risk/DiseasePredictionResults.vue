<template>
  <div>
    <el-row v-if="assessment" :gutter="16" class="cards"><el-col v-for="item in assessment.results" :key="item.disease_code" :xs="24" :sm="12" :lg="8">
      <el-card shadow="never" class="card"><div class="heading"><b>{{ diseaseName(item.disease_code) }}</b><el-tag size="small" type="info">{{ statusName(item.status) }}</el-tag></div>
        <template v-if="item.status==='predicted'"><div class="probability">{{ percent(item.probability) }}</div><p class="note">AI 模型预测</p>
          <el-collapse><el-collapse-item title="查看模型阈值" :name="item.disease_code"><p>阈值 {{ percent(item.threshold) }}；{{ item.alert ? '达到模型阈值' : '未达到模型阈值' }}。</p><p class="note">阈值用于划分模型输出，不能据此确诊或排除疾病。</p></el-collapse-item></el-collapse>
        </template><p v-else class="reason">{{ item.reason }}</p>
      </el-card>
    </el-col></el-row>
    <el-empty v-else description="暂无慢性病评估记录" />
    <p v-if="assessment" class="note">{{ assessment.assessedAt }} · {{ assessment.modelVersion }} · 资料版本 {{ assessment.profileRevision }}。五项结果独立，概率不相加。</p>
  </div>
</template>
<script>
import { diseaseName,statusName,percent } from '@/utils/healthRisk'
export default { name:'DiseasePredictionResults',props:{assessment:{default:null}},methods:{diseaseName,statusName,percent} }
</script>
<style scoped>
.cards {display:flex;flex-wrap:wrap;margin-top:16px}.cards>.el-col {display:flex}.card {width:100%;box-sizing:border-box;border-radius:10px;margin-bottom:16px;min-height:235px}.heading {display:flex;align-items:center;justify-content:space-between;gap:8px}.probability {font-size:34px;color:#315d69;font-weight:600;margin-top:22px}.note {font-size:12px;line-height:1.8;color:#74858e}.reason {font-size:14px;line-height:1.9;margin-top:25px}
</style>
