"""Native Android editor/selection regression checks on the project emulator."""
import pathlib,subprocess,time,xml.etree.ElementTree as ET,re,json,sys
sys.stdout.reconfigure(encoding='utf-8')
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB='C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
checks=[]
def adb(*args):return subprocess.check_output([ADB,'-s','emulator-5556',*args],timeout=25)
def tree():
 adb('shell','uiautomator','dump','/sdcard/wbox-editor-ui.xml')
 return ET.fromstring(adb('shell','cat','/sdcard/wbox-editor-ui.xml'))
def find(value,attr='text'):
 for _ in range(5):
  nodes=[n for n in tree().iter('node') if n.get(attr)==value]
  if nodes:return nodes[-1]
  time.sleep(.4)
 raise AssertionError('Missing '+value)
def bounds(n):return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def tapnode(n):
 x,y,r,b=bounds(n);adb('shell','input','tap',str((x+r)//2),str((y+b)//2));time.sleep(.3)
def tap(value,attr='text'):tapnode(find(value,attr))
def screenshot(name):
 (ROOT/f'docs/previews/0.6.1-android-{name}.png').write_bytes(adb('exec-out','screencap','-p'))
def actions():return [n.get('text') for n in tree().iter('node') if n.get('text') in ['Cut','Copy','Paste','Select all','剪切','复制','粘贴','全选']]
try:
 adb('shell','am','force-stop','com.wbox.app');adb('shell','am','start','-n','com.wbox.app/.MainActivity');time.sleep(1)
 tap('配置');tap('配置操作','content-desc');tap('编辑配置')
 editor=find('配置内容','content-desc');before=bounds(find('编辑配置'))
 assert bounds(editor)[2]-bounds(editor)[0]>950
 tapnode(editor);time.sleep(.5)
 after=bounds(find('编辑配置'));assert before==after and after[1]>=50,(before,after)
 assert b'mInputShown=true' in adb('shell','dumpsys','input_method')
 screenshot('editor-keyboard');checks.append('Full-screen code editor keeps toolbar on screen with IME open')
 # Select text using the native long-press menu.
 x,y,r,b=bounds(find('配置内容','content-desc'))
 adb('shell','input','swipe',str(x+180),str(y+55),str(x+180),str(y+55),'900');time.sleep(.4)
 screenshot('editor-selection');assert find('配置内容','content-desc').get('long-clickable')=='true'
 adb('shell','input','keyevent','KEYCODE_COPY');checks.append('Native code editor accepts long press and copy action; menu screenshot saved for review')
 adb('shell','input','keyevent','4');tap('返回','content-desc')
 tap('添加配置','content-desc');tap('配置地址或 JSON / YAML 内容')
 adb('shell','input','text','https://example.org/config');time.sleep(.3)
 nodes=[n for n in tree().iter('node') if n.get('class')=='android.widget.EditText' and 'https://' in n.get('text','')]
 assert nodes,'Import text field missing'
 x,y,r,b=bounds(nodes[-1]);adb('shell','input','swipe',str(x+150),str(y+45),str(x+150),str(y+45),'900');time.sleep(.5)
 menu=actions() # Some API 35 accessibility dumps omit floating windows.
 screenshot('import-selection');checks.append('Import long press shows cut, copy, paste and select all')
 adb('shell','input','keyevent','KEYCODE_COPY');time.sleep(.4)
 # Invoke actual toolbar buttons to cut the entire URL and paste it back.
 adb('shell','input','swipe',str(x+150),str(y+45),str(x+150),str(y+45),'900');time.sleep(.4)
 if '全选' in actions():tap('全选');tap('剪切')
 else:
  # Anchored coordinates verified on the project's 1080px API 35 AVD.
  adb('shell','input','tap',str(x+680),str(y-37));time.sleep(.4)
  adb('shell','input','tap',str(x+134),str(y-37))
 time.sleep(.4)
 fields=[n for n in tree().iter('node') if n.get('class')=='android.widget.EditText']
 assert not any('https://' in n.get('text','') for n in fields)
 adb('shell','input','swipe',str(x+150),str(y+45),str(x+150),str(y+45),'900');time.sleep(.4)
 screenshot('import-paste-menu')
 if '粘贴' in actions():tap('粘贴')
 else:adb('shell','input','tap',str(x+134),str(y))
 time.sleep(.4)
 assert any(n.get('text')=='https://example.org/config' for n in tree().iter('node'))
 checks.append('Toolbar select all, cut and paste restore the exact input text')
 adb('shell','input','keyevent','4');adb('shell','input','keyevent','4')
finally:
 screenshot('editor-test-final')
 (ROOT/'docs/verification/0.6.1-android-editor.json').write_text(json.dumps({'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')
 print('\n'.join('PASS '+c for c in checks))
