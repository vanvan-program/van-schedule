const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const source = fs.readFileSync(path.join(__dirname, '../src/common/protocol.js'), 'utf8').replace(/export /g, '')
const context = { module: { exports: {} } }
vm.runInNewContext(source + '\nmodule.exports = {hash, validate, view, Receiver, Cache}', context)
const { hash, validate, view, Receiver, Cache } = context.module.exports
const epoch = Date.UTC(2026, 9, 23)
function sample() {
  return { format: 'vanband', version: 1, generatedAt: epoch, scheduleId: 't', scheduleName: '中文😀', from: '2026-10-23', through: '2026-11-05',
    days: Array.from({ length: 14 }, (_, i) => ({ date: new Date(epoch + i * 86400000).toISOString().slice(0, 10),
      weekday: (4 + i) % 7 + 1, startsAt: epoch + i * 86400000, endsAt: epoch + (i + 1) * 86400000, notice: '', special: [],
      lessons: [{ id: 'c' + i, name: '课程😀', color: '#3874f6', startsAt: epoch + i * 86400000 + 3600000, endsAt: epoch + i * 86400000 + 7200000 }] })) }
}
function chunks(snapshot, id = 'req') {
  const text = JSON.stringify(snapshot), data = text.match(/[\s\S]{1,220}/g)
  return data.map((part, i) => ({ format: 'vanband', version: 1, type: 'chunk', requestId: id, index: i, count: data.length, hash: hash(text), data: part }))
}
function memory() {
  const values = {}
  return { values, async get(k) { if (!(k in values)) throw Error('missing'); return values[k] }, async set(k, v) { values[k] = v } }
}
test('hash matches Kotlin; unicode survives reassembly and offline reopen', async () => {
  assert.equal(hash('hello'), '4f9f2cab')
  const io = memory(), receiver = new Receiver(new Cache(io)), snapshot = sample()
  let last
  for (const part of chunks(snapshot)) last = await receiver.accept(part, epoch)
  assert.equal(last.ack.type, 'ack')
  assert.equal(JSON.stringify(await new Cache(io).load()), JSON.stringify(snapshot))
})
test('incomplete/corrupt/out-of-range fragments preserve the previous cache', async () => {
  const io = memory(), cache = new Cache(io), old = sample(), text = JSON.stringify(old)
  await cache.save(text, hash(text))
  const pointer = io.values.pointer, receiver = new Receiver(cache), parts = chunks(sample(), 'next')
  await receiver.accept(parts[0], epoch)
  assert.equal(io.values.pointer, pointer)
  parts[1].data = parts[1].data.replace(/./, '!')
  await assert.rejects(async () => { for (const part of parts.slice(1)) await receiver.accept(part, epoch) })
  assert.equal(io.values.pointer, pointer)
  await assert.rejects(() => receiver.accept(Object.assign({}, parts[0], { count: 999999 }), epoch))
})
test('interrupted commit preserves the last complete slot', async () => {
  const io = memory(), cache = new Cache(io), first = JSON.stringify(sample())
  await cache.save(first, hash(first))
  const original = io.set
  io.set = async (k, v) => { if (k === 'pointer') throw Error('disk error'); await original(k, v) }
  const next = JSON.stringify(Object.assign(sample(), { scheduleName: 'second' }))
  await assert.rejects(() => cache.save(next, hash(next)))
  assert.equal((await new Cache(io).load()).scheduleName, '中文😀')
})
test('ended courses disappear at end; tomorrow and midnight advance offline', () => {
  const s = sample()
  assert.equal(view(s, epoch + 3600000, 0).lessons[0].ongoing, true)
  assert.equal(view(s, epoch + 7200000, 0).lessons.length, 0)
  assert.equal(view(s, epoch + 86400000, 0).lessons[0].id, 'c1')
  assert.equal(view(s, epoch, 1).lessons[0].id, 'c1')
  assert.match(view(s, epoch + 14 * 86400000, 0).message, /暂无/)
  assert.match(view(s, epoch + 13 * 86400000, 1).message, /暂无/)
})
test('holiday/no-course/cleared states are distinct', () => {
  const s = sample(); s.days[0].lessons = []; s.days[0].notice = '放假 · 国庆'
  assert.equal(view(s, epoch, 0).message, '放假 · 国庆')
  s.days[0].notice = ''; assert.equal(view(s, epoch, 0).message, '今日无课')
  assert.match(view(Object.assign(s, { cleared: true, days: [] }), epoch, 0).message, /同步/)
})
test('invalid days and unsupported protocol versions are rejected', () => {
  assert.throws(() => validate(Object.assign(sample(), { version: 2 })))
  const s = sample(); s.days[2].startsAt++; assert.throws(() => validate(s))
})
