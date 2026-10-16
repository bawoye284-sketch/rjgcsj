import riskRequest from './riskRequest'
const call = (method, path, data) => riskRequest(method, `/disease-risk${path}`, data)

export default {
  profile: () => call('get', '/profile'),
  save: data => call('post', '/profile', data),
  models: () => call('get', '/models'),
  assess: data => call('post', '/assess', data),
  latest: () => call('get', '/latest'),
  history: (page, size) => call('get', `/history?page=${page}&pageSize=${size}`),
  detail: id => call('get', `/assessments/${encodeURIComponent(id)}`)
}
