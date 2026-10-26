import request from './riskRequest'
export default {
  create: requestId => request('post', '/sos', { requestId }),
  remind: (id, requestId) => request('post', '/sos/' + id + '/reminders', { requestId }),
  current: () => request('get', '/sos/current'),
  detail: id => request('get', '/sos/' + id),
  policy: () => request('get', '/sos/policy'),
  history: page => request('get', '/sos?page=' + page + '&size=10')
}
