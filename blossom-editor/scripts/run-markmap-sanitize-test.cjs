const { buildSync } = require('esbuild')
const { mkdtempSync, rmSync } = require('node:fs')
const { tmpdir } = require('node:os')
const { join } = require('node:path')
const { spawnSync } = require('node:child_process')

const output = join(__dirname, `.markmap-sanitize-${process.pid}.cjs`)
let status = 1
try {
  buildSync({
    entryPoints: [join(__dirname, 'markmap-sanitize-test.ts')],
    bundle: true,
    platform: 'node',
    target: 'node18',
    format: 'cjs',
    external: ['jsdom'],
    outfile: output,
    logLevel: 'warning'
  })
  const result = spawnSync(process.execPath, [output], { stdio: 'inherit' })
  status = result.status == null ? 1 : result.status
} finally {
  rmSync(output, { force: true })
}
process.exit(status)
