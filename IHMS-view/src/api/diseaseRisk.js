import request from '@/utils/request'

async function call(method, path, data) {
  try {
    const response = await request({ method, url: `/disease-risk${path}`, data })
    if (response.data.code !== 200) throw new Error(response.data.msg || '请求未完成')
    return response.data.data
  } catch (error) {
    const message = error.response && error.response.data && error.response.data.msg
    throw new Error(message || error.message || '网络连接失败，请稍后重试')
  }
}

export default {
  profile: () => call('get', '/profile'),
  save: data => call('post', '/profile', data),
  models: () => call('get', '/models'),
  assess: data => call('post', '/assess', data),
  latest: () => call('get', '/latest'),
  history: (page, size) => call('get', `/history?page=${page}&pageSize=${size}`),
  detail: id => call('get', `/assessments/${encodeURIComponent(id)}`),
  legacyProfile: async () => {
    const response = await request.get('/personalized-risk/profile')
    if (response.data.code !== 200) throw new Error(response.data.msg || '读取已有资料失败')
    return response.data.data
  }
}
