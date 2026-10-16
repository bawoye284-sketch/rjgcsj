import riskRequest from './riskRequest'
const call = (method, path, data) => riskRequest(method, `/personalized-risk${path}`, data)

export default {
  profile: () => call('get', '/profile'),
  saveBasic: data => call('post', '/basic-profile', data),
  assessCurrent: data => call('post', '/assess-current', data),
  latest: () => call('get', '/latest'),
  history: () => call('get', '/history?days=30')
}
