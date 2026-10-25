const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const protocolContext = { module: { exports: {} } }
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/common/protocol.js'), 'utf8').replace(/export /g, '') + '\nmodule.exports={Receiver,hash}', protocolContext)
const { Receiver, hash } = protocolContext.module.exports
const source = fs.readFileSync(path.join(__dirname, '../src/common/runtime.js'), 'utf8').replace(/^import .*$/gm, '').replace('export default', 'module.exports =')
function fixture(cache, failAck = false) {
  const sent = [], statuses = []
  const connection = {
    getReadyState({ success }) { success({ status: 1 }) },
    send(options) {
      // Vela connect.send requires an Object; passing JSON text reproduces native 202.
      if (!options.data || typeof options.data !== 'object' || Array.isArray(options.data)) {
        options.fail('invalid args', 202)
        return
      }
      const message = JSON.parse(JSON.stringify(options.data)); sent.push(message)
      if (failAck && message.type === 'ack') options.fail('disconnected', 1006)
      else if (options.success) options.success()
    }
  }
  const context = { cache, interconnect: { instance: () => connection }, Receiver, module: { exports: {} } }
  vm.runInNewContext(source, context)
  return { runtime: context.module.exports, connection, sent, statuses,
    onChange: (snapshot, info) => statuses.push({ snapshot, info }) }
}
const flush = () => new Promise(resolve => setImmediate(resolve))
test('wrist sync request uses the native Object contract, not JSON text', async () => {
  const f = fixture({ load: async () => null })
  await f.runtime.start(f.onChange)
  f.runtime.request()
  assert.equal(f.sent.length, 1)
  assert.equal(f.sent[0].format, 'vanband')
  assert.equal(f.sent[0].type, 'request')
  assert.equal(f.statuses.some(status => status.info.includes('202')), false)
})
function clearFrame() {
  const snapshot = { format: 'vanband', version: 1, generatedAt: 1, cleared: true, days: [] }
  const text = JSON.stringify(snapshot)
  return { format: 'vanband', version: 1, type: 'chunk', requestId: 'check', index: 0, count: 1, hash: hash(text), data: text }
}
test('native receive callbacks exist before cache load; ACK follows cache save', async () => {
  let finishLoad, finishSave, saving = false
  const f = fixture({ load: () => new Promise(resolve => { finishLoad = resolve }),
    save: () => { saving = true; return new Promise(resolve => { finishSave = resolve }) } })
  const started = f.runtime.start(f.onChange)
  assert.equal(typeof f.connection.onmessage, 'function')
  f.connection.onmessage({ data: JSON.stringify(clearFrame()) })
  await flush()
  assert.equal(saving, false)
  finishLoad(null); await started; await flush()
  assert.equal(saving, true)
  assert.equal(f.sent.length, 0)
  finishSave(); await flush()
  assert.equal(f.sent[0].type, 'ack')
  assert.equal(f.statuses.at(-1).info, '课程已保存，确认已回传')
})
test('cache save failure sends an error, never an ACK', async () => {
  const f = fixture({ load: async () => null, save: async () => { throw Error('保存缓存超时') } })
  await f.runtime.start(f.onChange)
  f.connection.onmessage({ data: clearFrame() }); await flush()
  assert.equal(f.sent[0].type, 'error')
  assert.equal(f.sent[0].error, '保存缓存超时')
  assert.equal(f.sent.some(message => message.type === 'ack'), false)
})
test('ACK return failure remains distinct from persisted data loss', async () => {
  const f = fixture({ load: async () => null, save: async () => {} }, true)
  await f.runtime.start(f.onChange)
  f.connection.onmessage({ data: clearFrame() }); await flush()
  assert.match(f.statuses.at(-1).info, /课程已保存，确认回传失败.*1006/)
})
