// Run the production preview first: node scripts/desktop-preview.mjs
// Requires Playwright + Chromium; PLAYWRIGHT_MODULE may point to its index.mjs.
import assert from 'node:assert/strict';
const {chromium} = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const browser = await chromium.launch({headless:true});
const origin = 'http://127.0.0.1:8787';
try {
  const context = await browser.newContext({viewport:{width:1380,height:900}});
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin);
  const effects = page.getByRole('combobox',{name:'Режим візуальних ефектів'});
  await effects.selectOption('full');
  assert.equal(await page.locator('html').getAttribute('data-effects'),'full');
  await effects.selectOption('eco');
  assert.equal(await page.locator('.ambient').evaluate(el=>getComputedStyle(el).display),'none');
  await page.reload();
  assert.equal(await effects.inputValue(),'eco');
  await effects.selectOption('full');
  await page.emulateMedia({reducedMotion:'reduce'});
  await page.waitForFunction(()=>document.documentElement.dataset.effects==='eco');
  await page.emulateMedia({reducedMotion:'no-preference'});
  await page.waitForFunction(()=>document.documentElement.dataset.effects==='full');
  await page.evaluate(()=>window.dispatchEvent(new Event('blur')));
  assert.equal(await page.locator('.ambient i').first().evaluate(el=>getComputedStyle(el).animationPlayState),'paused');

  // Mock data is isolated to this test, not shipped in the application.
  await page.route('**/api/**', async route => {
    const pathname = new URL(route.request().url()).pathname;
    const user = {id:'test',sid:'test',name:'Тестовий адміністратор',role:'ADMIN',specialty:'',permissions:['dashboard','patients.read','patients.manage','tasks.read','rooms.manage','cabinets.manage','users.manage','sessions.manage','audit.read','archive.read','appointments.read']};
    const value = pathname.endsWith('/auth/login') ? {token:'test-only',user} : pathname.endsWith('/auth/me') ? user : pathname.endsWith('/dashboard') ? {active:24,occupied:24,beds:40,open:12,completed:38} : [];
    await route.fulfill({json:value});
  });
  await page.locator('input[name="login"]').fill('test-admin');
  await page.locator('input[name="password"]').fill('not-a-real-password');
  await page.getByRole('button',{name:'Увійти в систему'}).click();
  await page.getByRole('heading',{name:'Вітаємо, Тестовий'}).waitFor();
  await page.screenshot({path:process.env.APPEARANCE_SCREENSHOT || '/tmp/quremed-appearance.png',fullPage:true});
  await page.locator('nav').getByRole('link',{name:'Пацієнти',exact:true}).click();
  await page.getByRole('button',{name:'Новий пацієнт'}).click();
  assert.equal(await page.getByRole('dialog').count(),1);
  await page.keyboard.press('Escape');
  assert.equal(await page.getByRole('dialog').count(),0);
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await page.getByRole('button',{name:'Меню',exact:true}).click();
  await page.locator('nav').getByRole('link',{name:'Огляд центру'}).click();
  await page.getByRole('heading',{name:'Вітаємо, Тестовий'}).waitFor();
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  assert.deepEqual(errors,[]);
  await context.close();

  const low = await browser.newContext();
  await low.addInitScript(()=>Object.defineProperty(navigator,'hardwareConcurrency',{get:()=>2}));
  const lowPage = await low.newPage();
  await lowPage.goto(origin);
  await lowPage.waitForFunction(()=>document.documentElement.dataset.effects==='eco');
  await low.close();
  const restricted = await browser.newContext();
  await restricted.addInitScript(()=>Object.defineProperty(window,'localStorage',{get:()=>{throw new Error('Storage disabled')}}));
  const restrictedPage = await restricted.newPage();
  await restrictedPage.goto(origin);
  await restrictedPage.getByRole('combobox',{name:'Режим візуальних ефектів'}).selectOption('eco');
  await restrictedPage.waitForFunction(()=>document.documentElement.dataset.effects==='eco');
  await restricted.close();
  console.log('PASS: login, persistence, eco, reduced motion, pause, dashboard, patient dialog, mobile navigation, low-core auto, restricted local storage, no runtime errors.');
} finally { await browser.close(); }
