import request from '@/utils/request'

export default async function riskRequest(method, url, data) {
  try {
    const response = await request({ method, url, data })
    if (response.data.code !== 200) throw new Error(response.data.msg || '请求未完成')
    return response.data.data
  } catch (error) {
    const message = error.response && error.response.data && error.response.data.msg
    const failure = new Error(message || (error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : error.message) || '网络连接失败，请稍后重试')
    failure.status = error.response ? error.response.status : error.status
    throw failure
  }
}
