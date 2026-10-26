<template>
  <el-collapse style="margin:12px 20px" @change="load"><el-collapse-item title="测量预警规则" name="rules">
    <el-alert title="正常范围复用全局指标。严重阈值未审核，本阶段不可启用。变化幅度为空表示关闭。修改仅对后续检测生效。" type="info" :closable="false" />
    <p>默认全局符号：SYS / DIA / Bpm / SpO2 / TEMP / RR；配置ID留空时按符号匹配。缺少配置不会判为正常。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div style="overflow-x:auto"><el-table :data="rules" size="small" style="min-width:1050px">
      <el-table-column prop="name" label="实测字段" width="100" />
      <el-table-column label="启用" width="70"><template slot-scope="s"><el-switch v-model="s.row.enabled" /></template></el-table-column>
      <el-table-column label="全局配置ID" width="150"><template slot-scope="s"><el-input-number v-model="s.row.configId" :min="1" :controls="false" placeholder="自动匹配" style="width:125px" /></template></el-table-column>
      <el-table-column prop="valueRange" label="当前范围" width="105" />
      <el-table-column label="连续次数" width="115"><template slot-scope="s"><el-input-number v-model="s.row.consecutiveCount" :min="2" :max="20" controls-position="right" style="width:95px" /></template></el-table-column>
      <el-table-column label="窗口/天" width="115"><template slot-scope="s"><el-input-number v-model="s.row.windowDays" :min="1" :max="90" controls-position="right" style="width:95px" /></template></el-table-column>
      <el-table-column label="变化幅度（绝对值）" width="150"><template slot-scope="s"><el-input-number v-model="s.row.changeDelta" :min="0.01" :precision="2" :controls="false" placeholder="关闭" style="width:120px" /></template></el-table-column>
      <el-table-column label="基线次数" width="115"><template slot-scope="s"><el-input-number v-model="s.row.baselineCount" :min="2" :max="20" controls-position="right" style="width:95px" /></template></el-table-column>
      <el-table-column label="操作" width="80"><template slot-scope="s"><el-button type="text" :disabled="saving" @click="save(s.row)">保存</el-button></template></el-table-column>
    </el-table></div>
  </el-collapse-item></el-collapse>
</template>
<script>
import api from '@/api/healthAlerts'
export default {
  data: () => ({ rules: [], error: '', saving: false }),
  methods: {
    async load() { try { this.rules = (await api.rules()).map(r => ({ ...r, configId: r.configId == null ? undefined : r.configId, changeDelta: r.changeDelta == null ? undefined : Number(r.changeDelta) })); this.error = '' } catch (e) { this.error = e.message } },
    async save(rule) {
      this.saving = true
      try { await api.saveRule({ ...rule, configId: rule.configId == null ? null : rule.configId, changeDelta: rule.changeDelta == null ? null : rule.changeDelta }); this.$message.success('规则已保存'); await this.load() }
      catch (e) { this.error = e.message } finally { this.saving = false }
    }
  }
}
</script>
