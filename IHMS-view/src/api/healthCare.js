import request from './riskRequest'
const query = data => Object.keys(data).filter(k => data[k] != null && data[k] !== '').map(k => encodeURIComponent(k) + '=' + encodeURIComponent(data[k])).join('&')
export default {
  contacts: () => request('get', '/emergency-contacts'),
  saveContact: row => request(row.id ? 'put' : 'post', '/emergency-contacts' + (row.id ? '/' + row.id : ''), row),
  deleteContact: id => request('delete', '/emergency-contacts/' + id),
  challenge: id => request('post', '/emergency-contacts/' + id + '/verification'),
  verify: (id, token) => request('post', '/emergency-contacts/' + id + '/verification/confirm', { token }),
  doctors: () => request('get', '/health-doctor-authorizations'),
  authorize: (id, authorized) => request('put', '/health-doctor-authorizations/' + id, { authorized }),
  notifications: page => request('get', '/health-notifications?page=' + page + '&size=10'),
  retry: id => request('post', '/health-notifications/' + id + '/retry'),
  read: id => request('put', '/health-notifications/' + id + '/read'),
  alerts: filters => request('get', '/doctor-health-alerts?' + query(filters)),
  detail: id => request('get', '/doctor-health-alerts/' + id),
  action: (id, action, note) => request('post', '/doctor-health-alerts/' + id + '/' + action, { note })
}
