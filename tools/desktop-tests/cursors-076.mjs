import {chromium, _electron as electron} from 'playwright';
import {readFile, writeFile, mkdir, rm} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createServer} from 'node:http';
import {resolve, join, sep} from 'node:path';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'../..');
const names=['pointer','grab','grabbing'],hotspots={pointer:'9 3',grab:'12 11',grabbing:'12 13'};
const checks=[],errors=[],measurements=[];
const check=(ok,label)=>{assert.ok(ok,label);checks.push(label);console.log('PASS',label);};
const nativeProbe=String.raw`
$source = @'
using System;
using System.Runtime.InteropServices;
public static class NativeCursorProbe {
  [StructLayout(LayoutKind.Sequential)] struct Header {public uint size;public int width,height;public ushort planes,bpp;public uint compression,imageSize;public int x,y;public uint used,important;}
  [DllImport("user32.dll")] static extern IntPtr LoadCursor(IntPtr instance,IntPtr name);
  [DllImport("user32.dll")] static extern bool DrawIconEx(IntPtr dc,int x,int y,IntPtr icon,int width,int height,int step,IntPtr brush,int flags);
  [DllImport("user32.dll")] static extern int GetSystemMetrics(int index);
  [DllImport("user32.dll")] static extern uint GetDpiForSystem();
  [DllImport("gdi32.dll")] static extern IntPtr CreateCompatibleDC(IntPtr dc);
  [DllImport("gdi32.dll")] static extern IntPtr CreateDIBSection(IntPtr dc,ref Header header,int usage,out IntPtr bits,IntPtr section,uint offset);
  [DllImport("gdi32.dll")] static extern IntPtr SelectObject(IntPtr dc,IntPtr obj);
  [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr obj);
  [DllImport("gdi32.dll")] static extern bool DeleteDC(IntPtr dc);
  [DllImport("gdi32.dll")] static extern bool GdiFlush();
  public static string Measure() {
    var cursor=LoadCursor(IntPtr.Zero,(IntPtr)32649); int w=GetSystemMetrics(13),h=GetSystemMetrics(14);
    var header=new Header{size=40,width=w,height=-h,planes=1,bpp=32}; var dc=CreateCompatibleDC(IntPtr.Zero); IntPtr bits;
    var bitmap=CreateDIBSection(dc,ref header,0,out bits,IntPtr.Zero,0); var old=SelectObject(dc,bitmap);
    try {
      var black=new int[w*h]; var white=new int[w*h]; Array.Fill(white,0xFFFFFF);
      Marshal.Copy(black,0,bits,black.Length); DrawIconEx(dc,0,0,cursor,w,h,0,IntPtr.Zero,3); GdiFlush(); Marshal.Copy(bits,black,0,black.Length);
      Marshal.Copy(white,0,bits,white.Length); DrawIconEx(dc,0,0,cursor,w,h,0,IntPtr.Zero,3); GdiFlush(); Marshal.Copy(bits,white,0,white.Length);
      int minX=w,minY=h,maxX=-1,maxY=-1;
      for(int y=0;y<h;y++) for(int x=0;x<w;x++) if((black[y*w+x]&0xFFFFFF)!=0 || (white[y*w+x]&0xFFFFFF)!=0xFFFFFF) {
        minX=Math.Min(minX,x);minY=Math.Min(minY,y);maxX=Math.Max(maxX,x);maxY=Math.Max(maxY,y);
      }
      return "{\"canvasWidth\":"+w+",\"canvasHeight\":"+h+",\"visibleWidth\":"+(maxX-minX+1)+",\"visibleHeight\":"+(maxY-minY+1)+",\"dpi\":"+GetDpiForSystem()+"}";
    } finally {SelectObject(dc,old);DeleteObject(bitmap);DeleteDC(dc);}
  }
}
'@
Add-Type -TypeDefinition $source
[NativeCursorProbe]::Measure()
`;
const native=process.platform==='win32'?JSON.parse(execFileSync('pwsh',['-NoProfile','-Command',nativeProbe],{encoding:'utf8'}).trim()):null;
const css=await readFile(join(root,'desktop/dashboard/src/WboxCursors.css'),'utf8');
const assets=Object.fromEntries(await Promise.all(names.map(async name=>[name,await readFile(join(root,`desktop/dashboard/src/assets/cursors/${name}.svg`),'utf8')])));
const html=`<!doctype html><html data-theme="light"><meta charset="utf-8"><link rel="stylesheet" href="/WboxCursors.css"><style>
body{font:16px system-ui;margin:40px;background:#fafafa;color:#20242b}
button,.wbox-library-card{padding:18px;margin:10px;border:1px solid #a0a0a0;border-radius:12px;background:transparent;color:inherit}
textarea{cursor:text}.resize{cursor:ew-resize}.sample{display:flex;align-items:center;gap:30px;margin:30px 10px}img{width:23px;height:23px}
</style><h2>Wbox · 默认大小光标</h2><button id="pointer">点击</button><button id="disabled" disabled>禁用</button><textarea id="text">文字</textarea><span class="resize">窗口边界</span>
<div id="grab" class="wbox-library-card" draggable="true">拖动配置</div><div id="grabbing" class="wbox-library-card" draggable="true" data-dragging>正在拖动</div>
<div class="sample">${names.map(name=>`<img src="/assets/cursors/${name}.svg" alt="${name}">`).join('')}</div></html>`;
const server=createServer((request,response)=>{
  if(request.url==='/'){response.setHeader('Content-Type','text/html');response.end(html);return;}
  if(request.url==='/WboxCursors.css'){response.setHeader('Content-Type','text/css');response.end(css);return;}
  const name=request.url?.match(/^\/assets\/cursors\/(pointer|grab|grabbing)\.svg$/)?.[1];
  if(name){response.setHeader('Content-Type','image/svg+xml');response.end(assets[name]);return;}
  response.writeHead(404);response.end();
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const data=join(root,`.tools/cursors-076-${process.pid}`);
let browser,app,failure=null;
async function decode(page,value){
  return page.evaluate(async value=>{
    const url=value.match(/url\(["']?(.*?)["']?\)/)?.[1];
    const img=new Image();img.src=url||'';await img.decode();
    const dpr=devicePixelRatio,canvas=document.createElement('canvas');canvas.width=img.naturalWidth*dpr;canvas.height=img.naturalHeight*dpr;
    const context=canvas.getContext('2d');context.drawImage(img,0,0,canvas.width,canvas.height);
    const pixels=context.getImageData(0,0,canvas.width,canvas.height).data;
    let left=canvas.width,top=canvas.height,right=-1,bottom=-1;
    for(let y=0;y<canvas.height;y++)for(let x=0;x<canvas.width;x++)if(pixels[(y*canvas.width+x)*4+3]>=32){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
    return {width:img.naturalWidth,height:img.naturalHeight,dpr,visibleWidth:(right-left+1)/dpr,visibleHeight:(bottom-top+1)/dpr};
  },value);
}
async function checkCursor(page,locator,name,label){
  const value=await locator.evaluate(element=>getComputedStyle(element).cursor);
  check(value.includes('svg')&&value.includes(hotspots[name]),`${label}: ${name} SVG and scaled hotspot`);
  const measured=await decode(page,value);measurements.push({label,name,...measured});
  check(measured.width===23&&measured.height===23,`${label}: ${name} resource decodes at 23 CSS pixels`);
  return measured;
}
async function waitWindow(suffix){
  const end=Date.now()+15000;
  while(Date.now()<end){const page=app.windows().find(page=>page.url().includes(suffix));if(page)return page;await new Promise(resolve=>setTimeout(resolve,100));}
  throw Error('Window did not open: '+suffix);
}
try{
  browser=await chromium.launch({headless:true,...(existsSync(chromium.executablePath())?{executablePath:chromium.executablePath()}:{channel:'chrome'})});
  for(const dpr of [1,2])for(const theme of ['light','dark']){
    const context=await browser.newContext({deviceScaleFactor:dpr,colorScheme:theme});
    const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));page.on('requestfailed',request=>errors.push(request.url()));
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;document.body.style.background=theme==='dark'?'#17191c':'#fafafa';document.body.style.color=theme==='dark'?'white':'#20242b';},theme);
    for(const name of names){const measured=await checkCursor(page,page.locator('#'+name),name,`${theme} DPR ${dpr}`);
      if(name==='pointer'&&native&&native.dpi===96)check(Math.abs(measured.visibleWidth-native.visibleWidth)<=2&&Math.abs(measured.visibleHeight-native.visibleHeight)<=2,`${theme} DPR ${dpr}: visible hand size matches default Windows hand within 2 px`);
    }
    check(await page.locator('#disabled').evaluate(element=>getComputedStyle(element).cursor)==='default',`${theme} DPR ${dpr}: disabled controls keep default cursor`);
    check(await page.locator('#text').evaluate(element=>getComputedStyle(element).cursor)==='text'&&await page.locator('.resize').evaluate(element=>getComputedStyle(element).cursor)==='ew-resize',`${theme} DPR ${dpr}: text and window resize cursors are preserved`);
    if(dpr===1&&theme==='light')await page.screenshot({path:join(root,'docs/previews/0.7.6-windows-cursors.png')});
    await context.close();
  }
  if(process.env.WBOX_EXE){
    await mkdir(data,{recursive:true});
    const config=join(data,'cursor-test.yaml'),now=new Date().toISOString();
    await writeFile(config,'proxies: []\nproxy-groups: []\nrules: ["MATCH,DIRECT"]\n');
    await writeFile(join(data,'settings.json'),JSON.stringify({Mode:'config',SystemProxy:false,Silent:false,AutoConnect:false,ConfigPath:config,Configs:[{Id:'cursor-test',Name:'Cursor fixture',Path:config,Core:'mihomo',Url:'https://example.invalid/cursor',Updated:now,Subscription:{Name:'Cursor fixture',Usage:{Upload:0,Download:0,Total:100,Expire:Math.floor(Date.now()/1000)+86400},LastSuccess:now}}]}));
    app=await electron.launch({executablePath:process.env.WBOX_EXE,args:[],env:{...process.env,WBOX_DATA_DIR:data}});
    const page=await waitWindow('index.html');page.on('pageerror',error=>errors.push(error.message));await page.waitForFunction(()=>!!window.wbox);
    await page.getByRole('button',{name:'配置',exact:true}).click();
    const card=page.locator('.wbox-library-card').filter({has:page.getByText('Cursor fixture',{exact:true})});await card.waitFor();
    for(const theme of ['light','dark']){
      await page.evaluate(theme=>{document.documentElement.dataset.theme=theme;},theme);
      await checkCursor(page,card,'grab',`packaged main ${theme}`);
      await checkCursor(page,card.locator('[data-time-kind="expiry"]'),'pointer',`packaged main ${theme}`);
      await card.evaluate(element=>element.dataset.dragging='true');await checkCursor(page,card,'grabbing',`packaged main ${theme}`);await card.evaluate(element=>delete element.dataset.dragging);
    }
    await page.evaluate(()=>window.wbox.application('showTray'));
    const tray=await waitWindow('tray.html');tray.on('pageerror',error=>errors.push(error.message));
    const button=tray.locator('button:not([disabled])').first();await button.waitFor();
    for(const theme of ['light','dark']){await tray.evaluate(theme=>{document.documentElement.dataset.theme=theme;},theme);await checkCursor(tray,button,'pointer',`packaged tray ${theme}`);}
  }
  check(errors.length===0,'Cursor assets and renderers load without errors');
}catch(error){failure=error.stack;console.error(error);}finally{
  if(app)await app.close();if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));
  assert.ok(data.startsWith(join(root,'.tools')+sep));
  await rm(data,{recursive:true,force:true});
  await writeFile(join(root,`docs/verification/0.7.6-windows-cursor-${process.env.WBOX_EXE?'packaged':'source'}.json`),JSON.stringify({passed:!failure,checks,errors,failure,nativeWindowsHand:native,measurements,sourceDpr:[1,2],packaged:Boolean(process.env.WBOX_EXE),preview:'docs/previews/0.7.6-windows-cursors.png',isolatedDataRemoved:true},null,2)+'\n');
}
if(failure)process.exitCode=1;
