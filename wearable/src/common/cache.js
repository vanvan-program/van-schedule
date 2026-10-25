// SPDX-License-Identifier: GPL-3.0-only
import storage from '@system.storage'
import file from '@system.file'
import { Cache } from './protocol'

function callNative(label, start) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(label + '超时，请重试同步')), 5000)
    const done = value => { clearTimeout(timer); resolve(value) }
    const fail = (data, code) => { clearTimeout(timer); reject(new Error(label + '失败 ' + String(code || '') + ' ' + String(data && data.message || data || ''))) }
    try { start(done, fail) } catch (error) { clearTimeout(timer); reject(error) }
  })
}
const io = {
  get(key) {
    return callNative('读取缓存', (resolve, reject) => {
      if (key === 'pointer') storage.get({ key: 'vanband-commit-v1', default: '', success: resolve, fail: reject })
      else file.readText({ uri: 'internal://files/vanband-' + key + '.json', success: data => resolve(data.text), fail: reject })
    })
  },
  set(key, value) {
    return callNative('保存缓存', (resolve, reject) => {
      if (key === 'pointer') storage.set({ key: 'vanband-commit-v1', value, success: resolve, fail: reject })
      else file.writeText({ uri: 'internal://files/vanband-' + key + '.json', text: value, append: false, success: resolve, fail: reject })
    })
  }
}
export default new Cache(io)
