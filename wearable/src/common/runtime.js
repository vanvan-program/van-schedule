// SPDX-License-Identifier: GPL-3.0-only
import interconnect from '@system.interconnect'
import cache from './cache'
import { Receiver } from './protocol'
const receiver = new Receiver(cache)
let connection = null, snapshot = null, listener = null, info = '', connected = false, initialized = false
let cacheReady = Promise.resolve()
function publish() { if (listener) listener(snapshot, info, connected) }
function reason(error, code) {
  return (code === undefined ? '' : String(code) + ' ') + String(error && (error.message || error.data) || error || '').slice(0, 90)
}
function send(data, onSuccess) {
  if (!connection) return
  // The native interconnect bridge serializes Objects; JSON text is rejected with 202.
  connection.send({ data, success: onSuccess || (() => {}), fail: (error, code) => {
    info = (data.type === 'ack' ? '课程已保存，确认回传失败：' : '发送失败：') + reason(error, code)
    publish()
  } })
}
export default {
  async start(onChange) {
    listener = onChange
    if (!initialized) {
      initialized = true
      info = '正在连接手机…'
      // Install callbacks before reading cache, so native storage delays cannot hide the channel.
      try {
        connection = interconnect.instance()
        connection.onopen = () => { connected = true; info = '通信已就绪，等待手机同步'; publish() }
        connection.onclose = event => { connected = false; info = '通信已断开：' + reason(event, event && event.code); publish() }
        connection.onerror = event => { connected = false; info = '通信错误：' + reason(event, event && event.code); publish() }
        connection.onmessage = event => {
          let message
          try { message = typeof event.data === 'string' ? JSON.parse(event.data) : event.data }
          catch (_) { info = '收到消息，但格式无法解析'; publish(); return }
          if (!message || message.format !== 'vanband') { info = '收到消息，协议不匹配'; publish(); return }
          if (message.type === 'chunk') { info = '接收课程 ' + (message.index + 1) + '/' + message.count; publish() }
          // Preserve cache slot ordering if fragments arrive while startup cache is loading.
          cacheReady.then(() => receiver.accept(message, Date.now())).then(result => {
            if (!result) return
            snapshot = result.snapshot
            info = snapshot.cleared ? '手机已清除课表' : snapshot.probe ? '示例已保存，可断连重开验证' : '课程已保存'
            publish()
            send(result.ack, () => { info = '课程已保存，确认已回传'; publish() })
          }).catch(error => {
            info = String(error.message || '同步失败，保留旧缓存')
            if (message && message.requestId) send({ format: 'vanband', version: 1, type: 'error', requestId: message.requestId, hash: message.hash, error: info })
            publish()
          })
        }
        connection.getReadyState({ success: result => {
          connected = result.status === 1
          info = connected ? '通信已就绪，等待手机同步' : '通信未就绪，请打开手机手环课表'
          publish()
        }, fail: (error, code) => { info = '连接检查失败：' + reason(error, code); publish() } })
      } catch (error) { info = '通信接口不可用：' + reason(error) }
      cacheReady = cache.load().then(value => { snapshot = value }).catch(error => {
        info = String(error.message || '缓存读取失败，请重新同步')
        snapshot = null
      })
      publish()
      await cacheReady
    }
    publish()
  },
  stop() { listener = null },
  request() { info = '请打开手机 van课表 → 手环课表'; publish(); send({ format: 'vanband', version: 1, type: 'request' }) }
}
