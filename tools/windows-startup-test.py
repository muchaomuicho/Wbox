"""Backend startup and live profile edits, using local synthetic data only."""
import concurrent.futures, http.client, http.server, json, os, pathlib, queue, subprocess, threading, time

ROOT=pathlib.Path(__file__).resolve().parent.parent
data=ROOT/'windows/startup-test-runtime'
data.mkdir(exist_ok=True)
(data/'settings.json').write_text(json.dumps({'Mode':'config','SystemProxy':False,'Configs':[]}),encoding='utf-8')
requests=[]
class Server(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        requests.append(self.path)
        if self.path.startswith('/rules'):
            time.sleep(35)
            body=b'{"version":3,"rules":[{"domain_suffix":["example.test"]}]}'
        elif self.path=='/config':body=b'proxies: []\nproxy-groups: []\nrules: ["MATCH,DIRECT"]'
        else:body=b'WBOX_OK'
        try:
            self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError):pass
    def log_message(self,*args):pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Server)
threading.Thread(target=server.serve_forever,daemon=True).start()
port=server.server_port
proc=subprocess.Popen(['dotnet',str(ROOT/'windows/Wbox.Backend/bin/Release/net8.0-windows/Wbox.Backend.dll')],
    env={**os.environ,'WBOX_DATA_DIR':str(data)},stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
pending={};next_id=0
def reader():
    for line in proc.stdout:
        message=json.loads(line)
        if 'id' in message:pending[message['id']].put(message)
threading.Thread(target=reader,daemon=True).start()
def send(method,args=None):
    global next_id
    next_id+=1;ticket=queue.Queue();pending[next_id]=ticket
    proc.stdin.write(json.dumps({'id':next_id,'method':method,'args':args or {}})+'\n');proc.stdin.flush()
    return ticket
def receive(ticket,timeout=130):
    result=ticket.get(timeout=timeout)
    if result.get('error'):raise RuntimeError(result['error'])
    return result.get('result')
def call(method,args=None):return receive(send(method,args))
def until(predicate,seconds=10):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=call('service')
        if predicate(value):return value
        time.sleep(.2)
    raise AssertionError(value)
def config(suffix='slow'):
    return {'log':{'level':'info'},'outbounds':[{'type':'direct','tag':'DIRECT'}],
            'route':{'final':'DIRECT','rule_set':[{'type':'remote','tag':'slow','format':'source',
                      'url':f'http://127.0.0.1:{port}/rules-{suffix}','download_detour':'DIRECT'}],
                     'rules':[{'rule_set':'slow','outbound':'DIRECT'}]}}
def create(value,name):call('createProfile',{'name':name,'content':json.dumps(value)})
def proxy_get(proxy_port):
    c=http.client.HTTPConnection('127.0.0.1',proxy_port,timeout=5)
    try:c.request('GET',f'http://127.0.0.1:{port}/ok');return c.getresponse().read()
    finally:c.close()
checks=[];result={'checks':checks}
try:
    original=config();create(original,'slow-start')
    start=time.monotonic();receive(send('start'));elapsed=time.monotonic()-start
    assert elapsed>=35 and call('service')['running'],elapsed
    result['slowSeconds']=elapsed;checks.append('35-second remote rule startup succeeds')
    state=call('state');active=state['configPath'];started=state['startedAt'];old=state['configs'][0]
    call('createProfile',{'name':'Added while running','remoteUrl':f'http://127.0.0.1:{port}/config'})
    current=call('state');assert current['configPath']==active and current['core']=='sing-box' and len(current['configs'])==2,current
    added=current['configs'][-1]
    call('writeProfile',{'id':added['id'],'content':'proxies: []\nrules: ["MATCH,DIRECT"]'})
    call('updateProfile',{'id':added['id']})
    call('writeProfile',{'id':old['id'],'content':json.dumps({**original,'log':{'level':'debug'}})})
    assert call('state')['startedAt']==started and call('state')['configPath']==active
    import re
    lines='\n'.join(x['message'] for x in call('logs'))
    proxy_port=int(re.search(r'127\.0\.0\.1:(\d+)\s*$',lines).group(1))
    assert proxy_get(proxy_port)==b'WBOX_OK'
    checks.append('Add remote config, edit and update during connection preserve core, selection, uptime and traffic')
    call('stop');requests.clear();start=time.monotonic();call('start')
    assert time.monotonic()-start<8 and not any(x.startswith('/rules') for x in requests),requests
    checks.append('Cached rules avoid repeat download on reconnect');call('stop')
    create(config('cancel'),'cancel-start');ticket=send('start');until(lambda s:s['starting'])
    start=time.monotonic();call('stop');receive(ticket);duration=time.monotonic()-start
    assert not call('service')['running'] and not call('service')['starting'] and duration<10
    result['cancelSeconds']=duration;checks.append('Stop remains responsive during startup and joins cancellation')
    invalid=config('bad');invalid['route']['final']='missing-outbound';create(invalid,'invalid')
    start=time.monotonic()
    try:call('start');raise AssertionError('Invalid core accepted')
    except RuntimeError as error:
        assert 'missing-outbound' in str(error) and time.monotonic()-start<8,str(error)
    checks.append('Actual core failure reports diagnostic immediately')
finally:
    try:call('stop')
    finally:
        proc.stdin.close();proc.wait(timeout=15);server.shutdown();server.server_close()
        (ROOT/'docs/verification/0.6.2-windows-startup.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result))
