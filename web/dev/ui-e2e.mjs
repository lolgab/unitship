// Clicks through the main flows of the page in a real browser, against a running demo (dev/demo.sh), and checks on the server
// what each one did: create an app, deploy an uploaded executable, set a secret, add a domain, roll back, stop and start,
// delete, log out.
//
//   node dev/ui-e2e.mjs http://panel.demo.local:18080 [password]
import { chromium } from 'playwright-core'
import { request } from 'node:http'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const [base, password = 'demo-password'] = process.argv.slice(2)
if (!base) {
  console.error('usage: node dev/ui-e2e.mjs <panel url> [password]')
  process.exit(2)
}
const port = Number(new URL(base).port)

let failures = 0
const check = (condition, message) => {
  if (condition) console.log(`ok: ${message}`)
  else {
    failures++
    console.error(`FAIL: ${message}`)
  }
}

/** What an app of the host answers to a request for its domain. */
const answer = (host) =>
  new Promise((resolve) => {
    const req = request({ host: '127.0.0.1', port, path: '/', headers: { Host: host } }, (res) => {
      let body = ''
      res.on('data', (c) => (body += c))
      res.on('end', () => resolve({ status: res.statusCode, body }))
    })
    req.on('error', () => resolve({ status: 0, body: '' }))
    req.end()
  })
const until = async (what, probe, ms = 20000) => {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const value = await probe()
    if (value) return value
    await new Promise((r) => setTimeout(r, 300))
  }
  throw new Error(`timeout: ${what}`)
}

const dir = mkdtempSync(join(tmpdir(), 'ui-e2e-'))
const executable = join(dir, 'app.py')
writeFileSync(
  executable,
  `#!/usr/bin/env python3
import os, http.server
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = ("ui-e2e " + os.environ.get("GREETING", "") + " r" + os.environ["UNITSHIP_RELEASE"]).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_request(self, *a): pass
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer((os.environ["BIND_ADDRESS"], int(os.environ["PORT"])), H).serve_forever()
`,
  { mode: 0o755 },
)

const browser = await chromium.launch({
  executablePath: process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  args: ['--host-resolver-rules=MAP *.demo.local 127.0.0.1'],
})
const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage()
const problems = []
page.on('pageerror', (e) => problems.push(`exception: ${e.message}`))
page.on('console', (m) => m.type() === 'error' && !/status of (401|404|422)/.test(m.text()) && problems.push(`console: ${m.text()}`))
const toast = (text) => page.waitForSelector(`.toast:has-text("${text}")`, { timeout: 30000 })

try {
  await page.goto(base)
  await page.fill('input[type=password]', password)
  await page.click('button[type=submit]')
  await page.waitForSelector('.shell')
  check(true, 'logged in')

  // Create
  await page.click('text=New app >> nth=0')
  await page.fill('.modal input', 'Bad Name')
  check(await page.locator('.modal button.btn-primary').isDisabled(), 'an invalid name cannot be created')
  await page.fill('.modal input', 'ui-test')
  await page.click('.modal button.btn-primary')
  await page.waitForSelector('h1:has-text("ui-test")')
  await page.waitForSelector('text=Nothing deployed yet')
  check(page.url().endsWith('#/apps/ui-test'), 'the new app page opens')
  await page.waitForSelector('.sidebar a:has-text("ui-test")')
  check(true, 'the app is in the sidebar')

  // Deploy an uploaded executable
  await page.click('header >> text=Deploy')
  check(await page.locator('.modal button.btn-primary').isDisabled(), 'deploy needs a file')
  await page.setInputFiles('.modal input[type=file]', executable)
  await page.click('.modal button.btn-primary')
  await page.waitForSelector('.modal .note-green', { timeout: 60000 })
  await page.click('.modal footer button')
  await page.waitForSelector('.table td:has-text("#1")')
  const first = await until('the app answers', async () => {
    const a = await answer('ui-test.demo.local')
    return a.status === 200 && a
  })
  check(first.body === 'ui-e2e  r1', `the uploaded app answers through the edge (${first.body})`)
  check(await page.locator('.stats .pill:has-text("running")').count() > 0, 'the page says it is running')

  // A secret
  await page.click('.tab:has-text("Secrets")')
  await page.click('text=Add >> nth=0')
  await page.fill('.modal input >> nth=0', 'GREETING')
  await page.fill('.modal input[type=password]', 'from the page')
  await page.click('.modal button.btn-primary')
  await toast('GREETING saved')
  await page.waitForSelector('code:has-text("GREETING")')
  const second = await until('the new release answers', async () => {
    const a = await answer('ui-test.demo.local')
    return a.body.includes('r2') && a
  })
  check(second.body === 'ui-e2e from the page r2', `the secret reached a new release (${second.body})`)
  check((await page.locator('body').innerText()).includes('from the page') === false, 'the value of the secret is not shown')

  // A domain
  await page.click('.tab:has-text("Domains")')
  await page.fill('input[placeholder="app.example.com"]', 'ui.example.com')
  await page.click('button:has-text("Add") >> nth=-1')
  await toast('ui.example.com added')
  await page.waitForSelector('td:has-text("ui.example.com")')
  const viaDomain = await answer('ui.example.com')
  check(viaDomain.status === 200 && viaDomain.body.includes('ui-e2e'), 'the new domain reaches the app')

  // Roll back
  await page.click('.tab:has-text("Overview")')
  await page.click('tr:has-text("#1") >> text=Roll back')
  await page.click('.modal button.btn-primary')
  await toast('Rolled back to #1')
  const back = await until('release 1 answers', async () => {
    const a = await answer('ui-test.demo.local')
    return a.body.includes('r1') && a
  })
  // The environment belongs to the app: the first release runs with the secret that was set after it.
  check(back.body === 'ui-e2e from the page r1', `rolled back to the first release (${back.body})`)

  // Activity
  await page.click('.tab:has-text("Activity")')
  // The Overview, which shows the newest five, is gone once this card is there.
  await page.waitForSelector('.card-header h2:text-is("Activity")')
  const activity = await page.locator('.timeline').innerText()
  for (const word of ['App created', 'Deployed', 'Configuration', 'Domains', 'Rolled back'])
    check(activity.includes(word), `the activity has "${word}"${activity.includes(word) ? '' : `: ${JSON.stringify(activity)} api: ${await page.evaluate(() => fetch('/api/apps/ui-test/events').then((r) => r.text()))}`}`)

  // Stop and start
  await page.click('.tab:has-text("Settings")')
  await page.click('.setting:has(b:text-is("Stop")) >> button')
  await toast('ui-test stopped')
  await until('it is stopped', async () => (await answer('ui-test.demo.local')).status === 502)
  check(true, 'a stopped app answers 502')
  await page.click('.setting:has(b:text-is("Start")) >> button')
  await toast('ui-test started')
  await until('it answers again', async () => (await answer('ui-test.demo.local')).status === 200)
  check(true, 'a started app answers again')

  // Delete
  await page.click('button:has-text("Delete app")')
  check(await page.locator('.modal button.btn-danger').isDisabled(), 'delete needs the name')
  await page.fill('.modal input', 'ui-test')
  await page.click('.modal button.btn-danger')
  await toast('ui-test deleted')
  await page.waitForSelector('h1:has-text("Dashboard")')
  await page.waitForFunction(() => !document.querySelector('.table-click')?.textContent?.includes('ui-test'), null, { timeout: 15000 })
  check((await answer('ui-test.demo.local')).status === 404, 'the deleted app is gone from the edge')

  // Log out
  await page.click('text=Log out')
  await page.waitForSelector('input[type=password]')
  await page.goto(`${base}/#/apps/web`)
  await page.waitForSelector('input[type=password]')
  check(true, 'after logging out the page asks for the password again')
} catch (e) {
  failures++
  console.error(`FAIL: ${e.message}`)
  await page.screenshot({ path: join(dir, 'failure.png'), fullPage: true }).catch(() => {})
  console.error(`screenshot of the failure: ${join(dir, 'failure.png')}`)
}

await browser.close()
if (problems.length) {
  failures++
  console.error('\nthe browser reported:\n' + [...new Set(problems)].map((p) => '  ' + p).join('\n'))
}
console.log(failures === 0 ? 'ALL OK' : `${failures} failure(s)`)
process.exit(failures === 0 ? 0 : 1)
