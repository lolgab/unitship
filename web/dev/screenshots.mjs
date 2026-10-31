// Drives the page of a running demo (dev/demo.sh) with a real browser: logs in, visits every page and takes a screenshot of
// each. Fails on anything the browser reports: a console error, a failed request or an uncaught exception.
//
//   node dev/screenshots.mjs http://panel.demo.local:18080 shots [password]
//
// CHROME is the browser (default: Google Chrome on macOS). *.demo.local resolves to this machine.
import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const [base, out = 'shots', password = 'demo-password'] = process.argv.slice(2)
if (!base) {
  console.error('usage: node dev/screenshots.mjs <panel url> [directory] [password]')
  process.exit(2)
}
mkdirSync(out, { recursive: true })

const browser = await chromium.launch({
  executablePath: process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  args: ['--host-resolver-rules=MAP *.demo.local 127.0.0.1'],
})
const problems = []
const open = async (colorScheme) => {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme })
  const page = await context.newPage()
  // The 401 of the wrong password is the point of that step.
  page.on('console', (m) => m.type() === 'error' && !m.text().includes('status of 401') && problems.push(`console: ${m.text()}`))
  page.on('pageerror', (e) => problems.push(`exception: ${e.message}`))
  page.on('requestfailed', (r) => problems.push(`request failed: ${r.url()} ${r.failure()?.errorText}`))
  page.on('response', (r) => {
    // The 401 before the login and the expected refusals are not problems.
    if (r.status() >= 500) problems.push(`${r.status()} ${r.url()}`)
  })
  return page
}
const shot = async (page, name) => {
  await page.waitForTimeout(400)
  await page.screenshot({ path: `${out}/${name}.png`, fullPage: true })
  console.log('shot', name)
}
const go = async (page, hash) => {
  await page.goto(`${base}/#${hash}`)
  await page.waitForLoadState('networkidle')
}

const page = await open('dark')
await page.goto(base)
await page.waitForSelector('input[type=password]')
await shot(page, '01-login')
await page.fill('input[type=password]', 'not the password')
await page.click('button[type=submit]')
await page.waitForSelector('.note-red')
await shot(page, '02-login-error')
await page.fill('input[type=password]', password)
await page.click('button[type=submit]')
await page.waitForSelector('.shell')
await page.waitForLoadState('networkidle')
await shot(page, '03-dashboard')

await go(page, '/apps/web')
await shot(page, '04-app-overview')
await go(page, '/apps/web/monitoring')
await page.waitForSelector('.chart svg')
await page.hover('.chart svg', { position: { x: 400, y: 80 } })
await shot(page, '05-app-monitoring')
await go(page, '/apps/web/secrets')
await shot(page, '06-app-secrets')
await page.click('text=Update >> nth=0')
await shot(page, '07-secret-modal')
await page.keyboard.press('Escape')
await go(page, '/apps/web/domains')
await shot(page, '08-app-domains')
await go(page, '/apps/web/activity')
await shot(page, '09-app-activity')
await go(page, '/apps/web/settings')
await shot(page, '10-app-settings')
await go(page, '/apps/worker')
await shot(page, '11-app-stopped')
await go(page, '/host')
await page.waitForSelector('.chart svg')
await shot(page, '12-host')
await go(page, '/')
await page.click('text=New app >> nth=0')
await page.fill('.modal input', 'my-new-app')
await shot(page, '13-new-app')
await page.keyboard.press('Escape')
await go(page, '/apps/web')
await page.click('header >> text=Deploy')
await shot(page, '14-deploy')
await page.keyboard.press('Escape')
await page.setViewportSize({ width: 420, height: 860 })
await go(page, '/')
await shot(page, '15-mobile-dashboard')
await go(page, '/apps/web/monitoring')
await shot(page, '16-mobile-monitoring')

const light = await open('light')
await light.goto(base)
await light.fill('input[type=password]', password)
await light.click('button[type=submit]')
await light.waitForSelector('.shell')
await go(light, '/apps/web/monitoring')
await light.waitForSelector('.chart svg')
await shot(light, '17-light-monitoring')
await go(light, '/')
await shot(light, '18-light-dashboard')

await browser.close()
if (problems.length) {
  console.error('\nproblems:\n' + [...new Set(problems)].map((p) => '  ' + p).join('\n'))
  process.exit(1)
}
console.log('no problem reported by the browser')
