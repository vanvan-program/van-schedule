// Use the toolkit's supported external signer hook. Only public certs and signatures cross processes.
const path = require('node:path')
const fs = require('node:fs')
const crypto = require('node:crypto')
const { spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '../..')
const search = [path.join(root, 'wearable'), path.join(root, '.tools/vanband/toolchain')]
const toolkit = path.dirname(require.resolve('@aiot-toolkit/aiotpack/package.json', { paths: search }))
const signaturePath = path.join(toolkit, 'lib/compiler/javascript/vela/utils/signature')
const java = process.env.VAN_BAND_JAVA
if (!java || !fs.existsSync(java)) throw Error('Set VAN_BAND_JAVA to the Java 21 executable')
function run(mode, data) {
  const result = spawnSync(java, [path.join(__dirname, 'KeystoreSign.java'), root, mode], { input: data, maxBuffer: 1024 * 1024, windowsHide: true })
  if (result.status !== 0) throw Error('Keystore signing failed: ' + String(result.stderr || result.error || 'unknown error'))
  return result.stdout
}
const certificate = run('certificate')
require(path.join(signaturePath, 'SignUtil.js')).default.getProjectSignConfig = async () => ({ privatekey: null, certificate })
require(path.join(signaturePath, 'Signer.js')).default.setRemoteCryptoSignFunction(data => {
  const signature = run('sign', data)
  if (!crypto.verify('RSA-SHA256', data, certificate, signature)) throw Error('Signature verification failed')
  return signature
})
