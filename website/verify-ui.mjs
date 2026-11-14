import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {resolve,join,extname,sep} from 'node:path';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';

// Reuse the repository's pinned browser-test dependency; never open the user's profile.
const require=createRequire(new URL('../tools/desktop-tests/package.json',import.meta.url));
const {chromium}=require('playwright');
const root=resolve(import.meta.dirname),output=join(root,'verification');
const version='0.7.8',releaseBase='https://github.com/muchaomuicho/Wbox/releases/';
const release={tag_name:'v'+version,published_at:'2026-11-14T04:00:00Z',html_url:releaseBase+'tag/v'+version,body:'Synthetic release for isolated website verification.',assets:[
  {name:`Wbox-${version}-windows-x64-setup.exe`,size:159127374},
  {name:`Wbox-${version}-android-arm64-v8a.apk`,size:56280000},
  {name:`SHA256SUMS-${version}.txt`,size:286},
].map(asset=>({...asset,browser_download_url:releaseBase+'download/v'+version+'/'+asset.name}))};
let responseMode='latest',browser;
const checks=[],failures=[],pageErrors=[],consoleErrors=[],blockedRequests=[],layouts=[],readability=[],screenshots=[],motionResults=[];
const sourceHashes=Object.fromEntries(await Promise.all(['index.html','styles.css','app.js'].map(async file=>[file,createHash('sha256').update(await readFile(join(root,file))).digest('hex')])));
const check=(condition,label)=>{assert.ok(condition,label);checks.push(label);console.log('PASS',label);};
const record=(condition,label,details)=>{if(condition){checks.push(label);console.log('PASS',label);}else{failures.push({name:label,details});console.error('FAIL',label);}};
const scenario=async(name,run)=>{try{await run();}catch(error){failures.push({name,error:String(error.stack??error)});console.error('FAIL',name,error.message);}};
const server=createServer(async(request,response)=>{
  const url=new URL(request.url,'http://127.0.0.1');
  if(url.pathname==='/api/release'){
    if(responseMode==='unavailable'){response.writeHead(503,{'Content-Type':'application/json','Cache-Control':'no-store'});response.end('{"error":"isolated failure"}');return;}
    response.writeHead(200,{'Content-Type':'application/json','Cache-Control':'no-store',...(responseMode==='fallback'?{'X-Wbox-Release':'fallback'}:{})});
    response.end(JSON.stringify(release));return;
  }
  const target=resolve(root,url.pathname.startsWith('/assets/')?'public'+url.pathname:url.pathname==='/'?'index.html':'.'+url.pathname);
  if(!target.startsWith(root+sep)){response.writeHead(404);response.end();return;}
  try{
    const bytes=await readFile(target);response.writeHead(200,{'Content-Type':({'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.svg':'image/svg+xml','.webp':'image/webp','.png':'image/png'})[extname(target)]??'application/octet-stream','Cache-Control':'no-store'});response.end(bytes);
  }catch{response.writeHead(404);response.end();}
});
await mkdir(output,{recursive:true});
await new Promise(done=>server.listen(0,'127.0.0.1',done));
const origin='http://127.0.0.1:'+server.address().port;
assert.notEqual(server.address().port,8787);
const publicURL=value=>{try{const url=new URL(value);return url.origin===origin?value:url.origin+'/[external-script]';}catch{return value;}};
async function context(options={}){
  const ctx=await browser.newContext({viewport:{width:1440,height:1000},colorScheme:'light',...options});
  await ctx.route('**/*',route=>{
    if(new URL(route.request().url()).origin===origin)return route.continue();
    blockedRequests.push(publicURL(route.request().url()));return route.abort();
  });
  ctx.on('page',page=>{
    page.on('pageerror',error=>pageErrors.push(error.message));
    page.on('console',message=>{if(message.type()==='error')consoleErrors.push({text:message.text().replace(/https?:\/\/[^\s"'<>]+/g,publicURL),location:{...message.location(),url:publicURL(message.location().url)}});});
  });
  return ctx;
}
async function loaded(page){await page.goto(origin,{waitUntil:'domcontentloaded'});await page.waitForFunction(()=>document.querySelector('#theme-toggle').getAttribute('aria-label').startsWith('页面外观'));await page.waitForFunction(()=>document.querySelector('#release-status').textContent!=='从 GitHub 获取最新稳定版本');}
async function theme(page,value){await page.locator('#theme-toggle').click();await page.locator(`[data-theme-choice="${value}"]`).click();}
async function layout(page,label){
  const result=await page.evaluate(()=>{
    const width=document.documentElement.clientWidth;
    return {width,scrollWidth:document.documentElement.scrollWidth,bodyScrollWidth:document.body.scrollWidth,
      outside:[...document.querySelectorAll('a:not(.skip-link),button,summary,h1,h2,p')].flatMap(element=>{const rect=element.getBoundingClientRect();if(!rect.width||!rect.height)return [];return rect.left < -1 || rect.right > width+1?[{tag:element.tagName,id:element.id,text:element.textContent.trim().slice(0,70),left:rect.left,right:rect.right}]:[]}),
      overflowElements:[...document.querySelectorAll('main *')].flatMap(element=>{const rect=element.getBoundingClientRect();if(!rect.width||!rect.height||element.closest('.icon-definitions'))return [];return rect.left < -1 || rect.right > width+1?[{tag:element.tagName,id:element.id,class:element.className?.baseVal??element.className,left:rect.left,right:rect.right}]:[]}).slice(0,25)};
  });
  layouts.push({label,...result});
  record(result.scrollWidth<=result.width+1&&result.bodyScrollWidth<=result.width+1&&result.outside.length===0,label+' has no horizontal overflow or clipped controls',result);
}
async function readable(page,width){
  const result=await page.evaluate(()=>{
    const groups=[
      {kind:'body',minimum:14,selector:'.hero-intro p,.workspace-copy p,.detail-row p,.color-copy p,.platform-description,.start-steps p,.faq-list summary,.faq-answer p'},
      {kind:'button',minimum:14,selector:'.hero-cta .button,.download-button,.platform-picker button,.workspace-tabs button,.color-options button'},
      {kind:'secondary',minimum:12,selector:'.feature-note,.asset-meta,.download-footnote,.color-hint,.release-meta,.platform-requirement,.footer-bottom'},
    ];
    return groups.flatMap(group=>[...document.querySelectorAll(group.selector)].map(element=>({kind:group.kind,minimum:group.minimum,text:element.textContent.trim().slice(0,60),fontSize:parseFloat(getComputedStyle(element).fontSize)})));
  });
  readability.push({width,elements:result});
  const small=result.filter(element=>element.fontSize<element.minimum);
  record(small.length===0,'Mobile '+width+'px has readable body, action and supporting text',small);
}
async function revealAll(page){for(const section of await page.locator('.reveal').all()){await section.scrollIntoViewIfNeeded();await page.waitForTimeout(80);}await page.evaluate(()=>window.scrollTo({top:0,behavior:'instant'}));await page.waitForTimeout(800);}
async function screenshot(page,name){await revealAll(page);await page.screenshot({path:join(output,name),fullPage:true,animations:'disabled'});screenshots.push('website/verification/'+name);}
try{
  browser=await chromium.launch({channel:'chrome',headless:true});
  const ctx=await context(),page=await ctx.newPage();await loaded(page);
  await scenario('release-downloads',async()=>{
    await page.waitForFunction(()=>document.querySelector('[data-download="windows"]').href.endsWith('.exe'));
    check((await page.locator('[data-download="windows"]').getAttribute('href'))===release.assets[0].browser_download_url,'Windows card points to the current x64 EXE');
    check((await page.locator('[data-download="android"]').getAttribute('href'))===release.assets[1].browser_download_url,'Android card points to the current ARM64 APK');
    check((await page.locator('[data-checksum-link]').first().getAttribute('href'))===release.assets[2].browser_download_url,'Checksum links point to the matching release');
    await page.locator('button[data-platform="android"]').click();
    check((await page.locator('#hero-download').getAttribute('href'))===release.assets[1].browser_download_url&&(await page.locator('button[data-platform="android"]').getAttribute('aria-pressed'))==='true','Platform switching updates the hero Android download');
    await page.locator('button[data-platform="windows"]').click();
    check((await page.locator('#hero-download').getAttribute('href'))===release.assets[0].browser_download_url,'Switching back updates the hero Windows download');
  });
  await scenario('desktop-themes-and-accents',async()=>{
    const paper=()=>page.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue('--paper').trim());
    const original=await paper();await theme(page,'dark');
    check(await paper()!==original&&(await page.locator('[data-theme-choice="dark"]').getAttribute('aria-checked'))==='true','Dark appearance changes page colors and selected menu state');
    await page.reload({waitUntil:'domcontentloaded'});check((await page.locator('[data-theme-choice="dark"]').getAttribute('aria-checked'))==='true','Explicit dark appearance persists after reload');
    await theme(page,'light');check(await paper()===original,'Light appearance restores its palette');
    const colors=[];
    for(const accent of ['mono','blue','pink']){
      await page.locator(`button[data-accent="${accent}"]`).click();
      check((await page.locator(`button[data-accent="${accent}"]`).getAttribute('aria-pressed'))==='true','Accent choice '+accent+' updates its selected state');
      colors.push(await page.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()));
    }
    check(new Set(colors).size===3,'All three accents have distinct page colors');
    await page.reload({waitUntil:'domcontentloaded'});check((await page.locator('button[data-accent="pink"]').getAttribute('aria-pressed'))==='true','Accent choice persists after reload');
    await theme(page,'system');await page.emulateMedia({colorScheme:'dark'});check(await paper()!==original,'System appearance follows a dark system change');
    await page.emulateMedia({colorScheme:'light'});check(await paper()===original,'System appearance follows a light system change');
    await theme(page,'light');await page.locator('button[data-accent="blue"]').click();await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));await layout(page,'Desktop 1440px');
    await screenshot(page,'desktop-1440.png');
  });
  await scenario('workspace-and-faq',async()=>{
    await page.locator('#workspace-nodes-tab').click();check(await page.locator('#workspace-nodes').isVisible()&&!await page.locator('#workspace-config').isVisible(),'Workspace tabs switch the matching copy');
    check((await page.locator('#workspace-image').getAttribute('src')).includes('windows-nodes'),'Workspace tabs switch the product image');
    await page.locator('#workspace-nodes-tab').press('ArrowLeft');check((await page.locator('#workspace-config-tab').getAttribute('aria-selected'))==='true','Workspace tabs support keyboard arrows');
    const details=page.locator('.faq-list details');await details.nth(0).locator('summary').click();check(await details.nth(0).getAttribute('open')!==null,'FAQ opens its answer');
    await details.nth(1).locator('summary').click();check(await details.nth(0).getAttribute('open')===null&&await details.nth(1).getAttribute('open')!==null,'Opening another FAQ closes the previous answer');
    await details.nth(1).locator('summary').press('Enter');check(await details.nth(1).getAttribute('open')===null,'FAQ supports keyboard dismissal');
  });
  for(const width of [390,320])await scenario('mobile-'+width,async()=>{
    await page.setViewportSize({width,height:844});await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));
    await layout(page,'Mobile '+width+'px light');
    const toggle=page.locator('#mobile-menu-toggle'),nav=page.locator('#mobile-nav');check(await toggle.isVisible(),'Mobile '+width+'px exposes its navigation button');
    await toggle.click();check(await nav.isVisible()&&(await toggle.getAttribute('aria-expanded'))==='true','Mobile '+width+'px navigation opens');
    await layout(page,'Mobile '+width+'px open navigation');
    await nav.locator('a[href="#download"]').click();check(!await nav.isVisible()&&page.url().endsWith('#download'),'Mobile '+width+'px navigation follows the link and closes');
    await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));await toggle.click();await page.keyboard.press('Escape');check(!await nav.isVisible(),'Mobile '+width+'px Escape closes navigation');
    await theme(page,'dark');await page.locator('button[data-accent="pink"]').click();await layout(page,'Mobile '+width+'px dark pink');
    await theme(page,'light');await page.locator('button[data-accent="blue"]').click();
    await page.locator('.faq-list details').first().locator('summary').click();
    await layout(page,'Mobile '+width+'px open FAQ');await screenshot(page,'mobile-'+width+'.png');
    for(const [region,selector] of [['hero','.hero'],['download','#download'],['faq','#faq']]){
      const name='mobile-'+width+'-'+region+'.png';
      const clip=await page.locator(selector).evaluate(element=>{const r=element.getBoundingClientRect();return {x:r.x+scrollX,y:r.y+scrollY,width:r.width,height:r.height};});
      await page.screenshot({path:join(output,name),fullPage:true,clip,animations:'disabled'});screenshots.push('website/verification/'+name);
    }
    await readable(page,width);
    await page.locator('.faq-list details').first().locator('summary').click();
  });
  await scenario('reduced-motion',async()=>{
    await page.emulateMedia({reducedMotion:'reduce'});await page.locator('button[data-accent="pink"]').click();
    await page.waitForFunction(()=>document.getAnimations().every(animation=>animation.playState!=='running'||Number(animation.effect.getTiming().duration)<=0.01),null,{timeout:2000}).catch(()=>{});
    const motion=await page.evaluate(()=>({matches:matchMedia('(prefers-reduced-motion: reduce)').matches,scroll:getComputedStyle(document.documentElement).scrollBehavior,animations:document.getAnimations().filter(animation=>animation.playState==='running').map(animation=>({type:animation.constructor.name,duration:animation.effect.getTiming().duration,target:animation.effect.target?.className}))}));
    motionResults.push(motion);
    record(motion.matches&&motion.scroll==='auto'&&motion.animations.every(animation=>Number(animation.duration)<=0.01),'Reduced-motion preference disables smooth scrolling and active animations',motion);
    await revealAll(page);check(await page.locator('.reveal').evaluateAll(elements=>elements.every(element=>Number(getComputedStyle(element).opacity)===1)),'Reduced-motion content remains fully visible');
  });
  await ctx.close();
  await scenario('cached-release-fallback',async()=>{
    responseMode='fallback';const fallbackContext=await context({viewport:{width:390,height:844}}),fallbackPage=await fallbackContext.newPage();
    try{await loaded(fallbackPage);await fallbackPage.waitForFunction(()=>document.querySelector('[data-download="android"]').href.endsWith('.apk'));check((await fallbackPage.locator('[data-download="android"]').getAttribute('href'))===release.assets[1].browser_download_url,'Worker fallback release keeps a valid platform-specific download');}finally{await fallbackContext.close();}
  });
  await scenario('unavailable-release-fallback',async()=>{
    responseMode='unavailable';const failureContext=await context(),failurePage=await failureContext.newPage();
    try{await loaded(failurePage);await failurePage.locator('#release-fallback').waitFor({state:'visible'});check((await failurePage.locator('[data-download="windows"]').getAttribute('href'))===releaseBase+'latest'&&(await failurePage.locator('[data-download="android"]').getAttribute('href'))===releaseBase+'latest','Unavailable release data leaves safe GitHub latest download links');}finally{await failureContext.close();}
  });
  check(pageErrors.length===0,'No JavaScript page errors');
  check(blockedRequests.filter(url=>new URL(url).hostname!=='gc.kis.v2.scr.kaspersky-labs.com').length===0,'Site rendering and interactions make no third-party network requests (host antivirus injection is separately recorded)');
}catch(error){failures.push({name:'setup-or-final-check',error:String(error.stack??error)});console.error(error);}
finally{
  await browser?.close();await new Promise(done=>server.close(done));
  await writeFile(join(output,'website-ui.json'),JSON.stringify({passed:failures.length===0,checkedAt:new Date().toISOString(),sourceHashes,engine:'installed Chrome headless via Playwright',localPort:server.address()?.port??Number(origin.split(':').at(-1)),network:'All assets and latest/fallback release responses are local; third-party requests are blocked. Host antivirus may inject Kaspersky URLs into HTTP responses; those blocked requests are retained separately from site dependencies.',checks,failures,pageErrors,consoleErrors,blockedRequests,layouts,readability,motionResults,screenshots},null,2)+'\n');
}
if(failures.length)process.exitCode=1;
