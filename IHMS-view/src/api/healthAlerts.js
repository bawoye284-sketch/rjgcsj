import request from './riskRequest'
const call = (method, path, data) => request(method, '/health-alerts' + path, data)
export default {
  status: id => call('get', '/status' + (id ? '?measurementId=' + id : '')),
  history: page => call('get', '?page=' + page + '&size=10'),
  retry: id => call('post', '/' + id + '/retry'),
  rules: () => call('get', '/rules'),
  saveRule: rule => call('put', '/rules/' + rule.metricKey, rule)
}
