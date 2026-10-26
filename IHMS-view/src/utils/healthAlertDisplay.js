// Presentation only. Backend status values and request/response payloads stay unchanged.
export function notificationState(row) {
  if (row.status === 'MOCK_SENT') return '未实际发送'
  if (row.status === 'SENT') return row.channel === 'EMAIL' ? '已提交邮件服务器' : row.channel === 'IN_APP' ? row.read_at ? '已读' : '已发送' : '发送状态尚不明确'
  if (row.channel === 'EMAIL' && (row.error_code === 'SMTP_NOT_CONFIGURED' || (row.status === 'PENDING' && row.emailServiceEnabled === false))) return '未配置邮件服务'
  return { PENDING: '等待发送', SENDING: '发送中', FAILED: '发送失败', UNKNOWN: '发送结果尚不明确', CANCELLED: '已取消后续提醒', SKIPPED: '暂未发送' }[row.status] || '通知状态尚不明确'
}

export function notificationRecipient(type) {
  return { USER: '我的站内消息', DOCTOR: '关联医生', CONTACT: '紧急联系人' }[type] || '通知接收人'
}

export function notificationPhase(value) {
  if (value === 'SOS_INITIAL') return '首次提醒'
  if (typeof value === 'string' && value.startsWith('SOS_R_')) return '再次提醒'
  if (typeof value === 'string' && value.startsWith('SOS_ESC_')) return '后续提醒'
  return { GENERAL: '普通异常提醒', REPEATED: '连续异常提醒' }[value] || '健康提醒'
}

export function notificationNote(code) {
  return {
    CAPTURE_FAILED: '部分通知暂未完成，系统将按照规则继续尝试。',
    SOS_CAPTURE_FAILED: '部分通知暂未完成，系统将按照规则继续尝试。',
    SOS_ALREADY_CONFIRMED: '医生已响应，无需继续提醒。',
    SOS_RESOLVED: '求助已处理，无需继续提醒。',
    DOCTOR_ACCESS_REVOKED: '医生关联或预警授权已失效。',
    CONTACT_CHANGED_OR_REVOKED: '联系人资料或通知授权已变更。',
    EMAIL_NOT_VERIFIED_FOR_MODE: '邮箱尚未完成当前通知方式所需的验证。',
    SMTP_NOT_CONFIGURED: '邮件服务暂不可用。',
    SMTP_AUTH_FAILED: '邮件服务暂不可用。',
    SMTP_CONNECT_FAILED: '邮件服务暂时无法连接。',
    SMTP_RECIPIENT_NOT_ALLOWED: '收件邮箱不在本次允许的发送范围内。',
    SMTP_SECURITY_INVALID: '邮件服务安全配置暂不可用。',
    SMTP_RESULT_UNKNOWN: '发送结果尚不明确，系统不会自动重复发送。',
    WORKER_INTERRUPTED: '发送结果尚不明确，系统不会自动重复发送。',
    DELIVERY_RESULT_UNKNOWN: '发送结果尚不明确，系统不会自动重复发送。',
    INVALID_RECIPIENT_TYPE: '接收信息暂不可用。'
  }[code] || (code ? '通知暂未完成，请稍后查看。' : '')
}

export function displayError(error) {
  const message = (error && error.message) || ''
  const status = error && (error.status || (error.response && error.response.status))
  if (status === 404 || /status code 404/.test(message)) return '暂时无法获取相关记录，请稍后重试。'
  if (/Network Error|网络连接失败|Failed to fetch/i.test(message)) return '网络连接异常，请检查服务状态后重试。'
  if (/CAPTURE_FAILED/.test(message)) return notificationNote('CAPTURE_FAILED')
  if (status === 403) return '您暂时无权查看或操作此记录，请确认关联和授权状态。'
  if (/timeout|超时/i.test(message)) return '请求超时，结果暂不明确，请稍后刷新查看。'
  // Do not expose raw HTTP errors, SQL/enum names or internal identifiers.
  if (message && !/[A-Za-z]|#\d/.test(message)) return message
  return '操作暂未完成，请稍后重试。'
}

export function measurementState(value) {
  return { WITHIN_RANGE: '本次测量在参考范围内', ALERT: '本次测量触发预警', MISSING: '本次未填写该指标，无法判断是否异常。', UNCONFIGURED: '尚未设置有效判断范围，本次未参与预警检测。', DISABLED: '该指标规则尚未启用，本次未参与预警检测。', INVALID_CONFIG: '该指标判断范围暂不可用，本次未参与预警检测。' }[value] || '暂未提供详细原因'
}

export function alertLevel(value) {
  return { NONE: '未触发规则预警', GENERAL: '普通异常提醒', REPEATED: '连续测量异常', EMERGENCY: '紧急求助' }[value] || '等级暂不明确'
}

export function alertStyle(value, recovered = false) {
  const color = { GENERAL: '#D97706', REPEATED: '#EA580C', EMERGENCY: '#DC2626' }[value]
  // Large, bold labels keep these specified orange tones legible on light backgrounds.
  return color && !recovered ? { color, fontWeight: '700', fontSize: '19px' } : {}
}

export function eventRisk(row) {
  if (row.kind === 'SOS' || row.sos) return '紧急求助'
  return row.status === 'RECOVERED' ? '测量已恢复' : row.status === 'ACTIVE' ? alertLevel(row.level) : '风险状态暂不明确'
}

export function eventStyle(row) {
  const sos = row.kind === 'SOS' || Boolean(row.sos)
  return alertStyle(sos ? 'EMERGENCY' : row.level, !sos && row.status === 'RECOVERED')
}

export function handlingState(value) {
  return { OPEN: '待确认', ACKNOWLEDGED: '已确认收到', RESOLVED: '已处理' }[value] || '处理状态暂不明确'
}

export function actionState(value) {
  return { ACKNOWLEDGED: '已确认收到', RESOLVED: '已处理' }[value] || '处理状态暂不明确'
}

export function notificationLevel(phase) {
  if (phase === 'SOS_INITIAL' || (typeof phase === 'string' && phase.startsWith('SOS_ESC_'))) return 'EMERGENCY'
  if (typeof phase === 'string' && phase.startsWith('SOS_R_')) return 'EMERGENCY'
  return ['GENERAL', 'REPEATED'].includes(phase) ? phase : null
}

export function formatTime(value) {
  if (value == null || value === '') return '—'
  // Local database strings need no timezone conversion; preserve the recorded local time.
  if (typeof value === 'string' && !/(Z|[+-]\d{2}:?\d{2})$/.test(value)) {
    const match = value.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})/)
    return match ? match[1] + ' ' + match[2] : '时间暂不可用'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '时间暂不可用'
  const pad = n => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function measurementUnit(metric) {
  return { systolic_pressure: 'mmHg', diastolic_pressure: 'mmHg', heart_rate: '次/分', spo2: '%', temperature: '℃', respiratory_rate: '次/分' }[metric] || ''
}

export function measurementValue(item) {
  return item.value == null ? '未测' : String(item.value) + measurementUnit(item.metric)
}

export function measurementRange(item) {
  if (item.range == null || item.range === '') return '尚未设置'
  return String(item.range).replace(/\s*,\s*/, '～') + measurementUnit(item.metric)
}

export function measurementReasons(item) {
  // Translate only explicit reasons returned by the backend. Never re-evaluate a threshold here.
  const reasons = (item.reasons || []).map(reason => {
    if (/^本次值 .+ 超出配置范围 /.test(reason)) return `${item.name || '本次测量'}${measurementValue(item)}，超出当前设置的参考范围（${measurementRange(item)}），建议核对测量情况并持续关注。`
    const repeated = reason.match(/^最近 (\d+) 次有效测量连续超出配置范围/)
    if (repeated) return `最近${repeated[1]}次有效测量均触发异常规则，建议关注后续测量变化，必要时咨询医生。`
    if (/^较此前 .+ 次有效测量均值变化达到配置幅度 /.test(reason)) return '近期变化提醒：' + reason.replace('配置幅度', '当前设置的提醒幅度').replace('（仅为变化提示）', '。建议核对测量情况并持续关注。')
    return reason
  })
  if (!reasons.length) reasons.push(measurementState(item.state))
  if (item.historyState === 'INSUFFICIENT' || item.changeState === 'INSUFFICIENT') reasons.push('有效历史测量不足，连续异常或近期变化暂不能完整判断。')
  return reasons
}

export function measurementSummary(result) {
  const items = result.items || []
  const checked = items.some(item => ['WITHIN_RANGE', 'ALERT'].includes(item.state))
  const title = result.outcome === 'ALERT' ? '本次测量存在异常指标' : ['WITHIN_RULES', 'INCOMPLETE'].includes(result.outcome) ? checked ? '本次测量未触发预警' : '本次测量暂未完成有效预警判断' : '本次预警结果暂不明确'
  const notes = []
  if (['WITHIN_RULES', 'INCOMPLETE'].includes(result.outcome) && checked && !items.some(item => item.state === 'ALERT')) notes.push('已配置规则的测量指标均未触发异常提醒。')
  if (items.some(item => item.state === 'MISSING')) notes.push('部分指标本次未填写，无法判断是否异常。')
  if (items.some(item => item.state === 'UNCONFIGURED')) notes.push('部分指标尚未配置判断范围，暂不参与本次预警判断。')
  if (items.some(item => item.state === 'DISABLED')) notes.push('部分指标的规则尚未启用，暂不参与本次预警判断。')
  if (items.some(item => item.historyState === 'INSUFFICIENT' || item.changeState === 'INSUFFICIENT')) notes.push('有效历史测量不足，连续异常或近期变化暂不能完整判断，不能据此认定健康恶化。')
  if (result.outcome === 'INCOMPLETE' && !notes.length) notes.push('部分检测信息不完整，不能据此判断所有指标正常。')
  return { title, notes }
}
