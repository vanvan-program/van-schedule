// SPDX-License-Identifier: GPL-3.0-only
// Pure logic shared unchanged by Node checks and the Vela receiver.
export function hash(text) {
  let value = 2166136261
  for (let i = 0; i < text.length; i++) {
    value ^= text.charCodeAt(i)
    value += (value << 1) + (value << 4) + (value << 7) + (value << 8) + (value << 24)
    value >>>= 0
  }
  return ('00000000' + value.toString(16)).slice(-8)
}
export function validate(snapshot) {
  if (!snapshot || snapshot.format !== 'vanband' || snapshot.version !== 1 ||
      !Number.isFinite(snapshot.generatedAt) || !Array.isArray(snapshot.days)) throw new Error('课表格式不支持')
  if (snapshot.cleared) {
    if (snapshot.days.length) throw new Error('清除包无效')
    return snapshot
  }
  if (snapshot.days.length !== 14 || typeof snapshot.scheduleName !== 'string' ||
      snapshot.from !== snapshot.days[0].date || snapshot.through !== snapshot.days[13].date) throw new Error('课程日期不完整')
  let previousEnd = 0
  snapshot.days.forEach(day => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day.date) || !Number.isFinite(day.startsAt) ||
        !Number.isFinite(day.endsAt) || day.endsAt <= day.startsAt ||
        (previousEnd && day.startsAt !== previousEnd) || !Array.isArray(day.lessons) ||
        !Array.isArray(day.special) || typeof day.notice !== 'string') throw new Error('日期数据错误')
    previousEnd = day.endsAt
    day.lessons.forEach(lesson => {
      if (typeof lesson.id !== 'string' || typeof lesson.name !== 'string' ||
          !Number.isFinite(lesson.startsAt) || !Number.isFinite(lesson.endsAt) ||
          lesson.startsAt >= lesson.endsAt || lesson.startsAt < day.startsAt ||
          lesson.endsAt > day.endsAt || !/^#[0-9a-f]{6}$/i.test(lesson.color)) throw new Error('课程数据错误')
    })
  })
  return snapshot
}
export function view(snapshot, now, offset) {
  if (!snapshot || snapshot.cleared) return { title: '', message: '请先连接手机同步课表', lessons: [], special: [] }
  const today = snapshot.days.findIndex(day => now >= day.startsAt && now < day.endsAt)
  if (today < 0 || !snapshot.days[today + offset]) return { title: '', message: '暂无该日数据，请同步手机', lessons: [], special: [] }
  const day = snapshot.days[today + offset]
  const lessons = day.lessons.filter(item => item.endsAt > now).map(item => Object.assign({}, item, {
    ongoing: item.startsAt <= now && now < item.endsAt
  }))
  const message = day.notice || (lessons.length ? '' : day.lessons.length ? '今日课程已结束' : offset ? '明日无课' : '今日无课')
  return { title: day.date.slice(5) + ' 周' + '一二三四五六日'.charAt(day.weekday - 1), message, lessons, special: day.special }
}
export class Receiver {
  constructor(store) { this.store = store; this.transfer = null; this.saving = false }
  async accept(input, now) {
    const message = typeof input === 'string' ? JSON.parse(input) : input
    if (!message || message.format !== 'vanband' || message.version !== 1 || message.type !== 'chunk') return null
    if (this.saving) throw new Error('正在保存，请稍后重试')
    if (typeof message.requestId !== 'string' || message.requestId.length > 80 || !message.requestId ||
        !Number.isInteger(message.count) || message.count < 1 || message.count > 2048 ||
        !Number.isInteger(message.index) || message.index < 0 || message.index >= message.count ||
        typeof message.data !== 'string' || message.data.length > 240 || !/^[0-9a-f]{8}$/.test(message.hash)) throw new Error('分片格式错误')
    let t = this.transfer
    if (!t || t.id !== message.requestId || now - t.started > 120000) {
      if (message.index !== 0) throw new Error('请重新开始同步')
      t = { id: message.requestId, hash: message.hash, count: message.count, parts: [], received: 0, size: 0, started: now }
      this.transfer = t
    }
    if (t.count !== message.count || t.hash !== message.hash) { this.transfer = null; throw new Error('同步内容不一致') }
    if (t.parts[message.index] === undefined) {
      t.parts[message.index] = message.data; t.received++; t.size += message.data.length
    } else if (t.parts[message.index] !== message.data) { this.transfer = null; throw new Error('重复分片不一致') }
    if (t.size > 480000) { this.transfer = null; throw new Error('课程数据过大') }
    if (t.received !== t.count) return null
    this.transfer = null
    const text = t.parts.join('')
    if (hash(text) !== t.hash) throw new Error('课程校验失败')
    const snapshot = validate(JSON.parse(text))
    this.saving = true
    try { await this.store.save(text, t.hash) } finally { this.saving = false }
    return { snapshot, ack: { format: 'vanband', version: 1, type: 'ack', requestId: t.id, hash: t.hash } }
  }
}
// Two slots plus a commit pointer: failed writes never replace the active cache.
export class Cache {
  constructor(io) { this.io = io; this.slot = 'a' }
  async load() {
    const pointer = await this.io.get('pointer').catch(() => '')
    if (!pointer) return null
    const metadata = JSON.parse(pointer)
    if ((metadata.slot !== 'a' && metadata.slot !== 'b') || !/^[0-9a-f]{8}$/.test(metadata.hash)) throw new Error('缓存索引损坏，请重新同步')
    this.slot = metadata.slot
    const text = await this.io.get(this.slot)
    if (hash(text) !== metadata.hash) throw new Error('缓存校验失败，请重新同步')
    return validate(JSON.parse(text))
  }
  async save(text, digest) {
    const next = this.slot === 'a' ? 'b' : 'a'
    await this.io.set(next, text)
    if (await this.io.get(next) !== text) throw new Error('手环写入校验失败')
    await this.io.set('pointer', JSON.stringify({ slot: next, hash: digest }))
    this.slot = next
  }
}
