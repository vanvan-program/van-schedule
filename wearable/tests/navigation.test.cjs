const { test } = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
function page() {
  const ux = fs.readFileSync(path.join(__dirname, '../src/pages/index/index.ux'), 'utf8')
  const script = ux.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '').replace('export default', 'module.exports =')
  const context = { module: { exports: {} }, app: { terminate() { context.exits++ } }, exits: 0 }
  vm.runInNewContext(script, context)
  const page = context.module.exports
  Object.assign(page, page.private)
  page.refresh = () => {}
  return { page, context }
}
test('root today right swipe exits instead of trapping the user', () => {
  const { page: p, context: c } = page()
  p.swipe({ direction: 'right' })
  assert.equal(c.exits, 1)
})
test('tomorrow goes to today, then exits on the next back', () => {
  const { page: p, context: c } = page()
  p.tomorrow(); p.back(); assert.equal(p.offset, 0); assert.equal(c.exits, 0)
  p.back(); assert.equal(c.exits, 1)
})
test('detail back closes details; system back at root also exits', () => {
  const { page: p, context: c } = page()
  p.detail = { name: 'test' }
  p.swipe({ direction: 'right' }); assert.equal(p.detail, null); assert.equal(c.exits, 0)
  assert.equal(p.onBackPress(), true); assert.equal(c.exits, 1)
})
