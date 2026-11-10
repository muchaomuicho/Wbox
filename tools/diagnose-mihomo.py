"""Read a subscription URL on stdin and diagnose an isolated, proxy-free core."""
import json, os, pathlib, queue, subprocess, sys, threading, time
sys.stdout.reconfigure(encoding='utf-8')
root=pathlib.Path(__file__).resolve().parent.parent
data=root/'windows/test-runtime/mihomo-private-diagnostic'
data.mkdir(parents=True,exist_ok=True)
(data/'settings.json').write_text(json.dumps({'Mode':'config','SystemProxy':False,'Configs':[]}),encoding='utf-8')
address=sys.stdin.readline().strip()
proc=subprocess.Popen(['dotnet',str(root/'windows/Wbox.Backend/bin/Release/net8.0-windows/Wbox.Backend.dll')],env={**os.environ,'WBOX_DATA_DIR':str(data)},stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
messages=queue.Queue();seq=0
def read():
    for line in proc.stdout:
        try:messages.put(json.loads(line))
        except ValueError:pass
threading.Thread(target=read,daemon=True).start()
def call(method,args=None):
    global seq
    seq+=1;proc.stdin.write(json.dumps({'id':seq,'method':method,'args':args or {}})+'\n');proc.stdin.flush()
    while True:
        message=messages.get(timeout=150)
        if message.get('id')==seq:
            if 'error' in message:raise RuntimeError(message['error'])
            return message.get('result')
def redact(text):
    import re
    text=text.replace(address,'[subscription]')
    return re.sub(r'https?://[^\s\"<>]+',lambda m:m.group().split('?')[0]+'?[redacted]' if '?' in m.group() else m.group(),text)
try:
    call('createProfile',{'remoteUrl':address,'name':'diagnostic'})
    state=call('state');print('Imported core:',state['core'],flush=True)
    began=time.monotonic()
    try:call('start');print('Started in',round(time.monotonic()-began,2),'seconds',flush=True)
    except Exception as error:print('START ERROR:',redact(str(error)),flush=True)
    for entry in call('logs')[-22:]:print(redact(entry['message']),flush=True)
finally:
    try:call('stop')
    finally:proc.stdin.close();proc.wait(timeout=15)
