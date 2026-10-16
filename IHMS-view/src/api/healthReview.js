import call from './riskRequest'
const query = params => Object.entries(params || {}).filter(([,v]) => v !== '' && v != null).map(([k,v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&')
export default {
  patients: params => call('get', '/health-review/patients?' + query(params)),
  statistics: params => call('get', '/health-review/statistics?' + query(params)),
  assessments: params => call('get', '/health-review/assessments?' + query(params)),
  measurements: params => call('get', '/health-review/measurements?' + query(params)),
  overview: (id, days = 30) => call('get', `/health-review/patients/${id}?days=${days}`),
  detail: (kind,id) => call('get', `/health-review/assessments/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`),
  models: () => call('get', '/health-review/models')
}
