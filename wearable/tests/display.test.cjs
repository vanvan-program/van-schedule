const { test } = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const ux = fs.readFileSync(path.join(__dirname, '../src/pages/index/index.ux'), 'utf8')
const template = ux.match(/<template>([\s\S]*?)<\/template>/)[1]
const css = ux.match(/<style>([\s\S]*?)<\/style>/)[1]

test('native lists and reusable cells all have explicit positive height', () => {
  for (const match of template.matchAll(/<(list|list-item)\b([^>]*)>/g)) {
    const classes = match[2].match(/class="([^"]+)"/)[1].split(/\s+/)
    const declarations = classes.map(name => (css.match(new RegExp('\\.' + name + '\\s*\\{([^}]+)\\}')) || [])[1] || '').join(';')
    assert.match(declarations, /(?:^|[;{])\s*height\s*:\s*[1-9]\d*(?:\.\d+)?px\s*[;}]/,
      `${match[1]} ${classes.join(' ')} needs explicit native height`)
  }
  const lesson = template.match(/<list-item\b[^>]*type="lesson"[^>]*>([\s\S]*?)<\/list-item>/)[1]
  assert.doesNotMatch(lesson, /\s(?:if|for)="/, 'same-type lesson cells must keep a consistent child structure')
})

const protocol = { module: { exports: {} } }
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/common/protocol.js'), 'utf8').replace(/export /g, '') + '\nmodule.exports={view}', protocol)
function page() {
  const now = Date.UTC(2026, 9, 23, 6)
  let clock = now, receive, tick
  const context = { module: { exports: {} }, view: protocol.module.exports.view,
    Date: { now: () => clock }, app: { terminate() {} },
    runtime: { start(callback) { receive = callback }, stop() {}, request() {} },
    setInterval(callback) { tick = callback; return 1 }, clearInterval() {} }
  vm.runInNewContext(ux.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'module.exports ='), context)
  const p = context.module.exports
  Object.assign(p, p.private); p.onInit(); p.onShow()
  const start = Date.UTC(2026, 9, 22, 16)
  const snapshot = { generatedAt: now, from: '2026-10-23', through: '2026-11-05', syncedLabel: '10-23 14:00',
    days: Array.from({ length: 14 }, (_, i) => ({ date: new Date(start + i * 86400000 + 8 * 3600000).toISOString().slice(0, 10), weekday: (4 + i) % 7 + 1,
      startsAt: start + i * 86400000, endsAt: start + (i + 1) * 86400000, notice: '', special: [], lessons: [] })) }
  return { p, snapshot, now, start, publish: s => receive(s, '课程已保存，确认已回传', true), advance: time => { clock = time; tick() } }
}
test('saved snapshot reaches actual today/tomorrow page and refreshes across course end', () => {
  const f = page()
  f.snapshot.days[0].lessons = [{ id: 'ended', name: '上午课', startsAt: f.now - 3600000, endsAt: f.now - 1000 },
    { id: 'current', name: '下午课', start: '14:00', end: '15:00', startsAt: f.now, endsAt: f.now + 3600000 }]
  f.snapshot.days[1].lessons = [{ id: 'next', name: '明日课', startsAt: f.start + 86400000 + 3600000, endsAt: f.start + 86400000 + 7200000 }]
  f.publish(f.snapshot)
  assert.equal(f.p.lessons.length, 1)
  assert.equal(f.p.lessons[0].name, '下午课')
  assert.equal(f.p.lessons[0].ongoing, true)
  f.p.open('current'); assert.equal(f.p.detail.name, '下午课'); f.p.close()
  f.p.tomorrow(); assert.equal(f.p.lessons[0].name, '明日课')
  f.p.today(); f.advance(f.now + 3600000)
  assert.equal(f.p.lessons.length, 0)
  assert.equal(f.p.message, '今日课程已结束')
})
test('empty-day and out-of-cache messages reach the page instead of stale rows', () => {
  const f = page(); f.publish(f.snapshot)
  assert.equal(f.p.message, '今日无课')
  f.p.tomorrow(); assert.equal(f.p.message, '明日无课')
  f.advance(f.start + 15 * 86400000)
  assert.equal(f.p.message, '暂无该日数据，请同步手机')
})
