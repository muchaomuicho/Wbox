"""Exercise the Android UID/VPN boundary on a project-owned emulator.
Release APKs do not contain RuntimeProbeReceiver.
"""
import subprocess, pathlib, json, time, threading, http.server, socket, sys
sys.stdout.reconfigure(encoding="utf-8")
ROOT=pathlib.Path(__file__).resolve().parent.parent
ADB=r'C:/Users/hw/AppData/Local/Android/Sdk/platform-tools/adb.exe'
SERIAL='emulator-5556'
TEMPLATES='--templates' in sys.argv
DESTINATION='8.8.8.8' if TEMPLATES else '10.0.2.2'
upstream=None
def adb(*args,input=None,timeout=30):
    p=subprocess.run([ADB,'-s',SERIAL,*args],input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
    if p.returncode:raise RuntimeError(p.stderr.decode(errors='replace')+p.stdout.decode(errors='replace'))
    return p.stdout
def probe(operation,engine='sing-box'):
    adb('shell','am','broadcast','-n','com.wbox.app/.RuntimeProbeReceiver','--es','operation',operation,'--es','engine',engine)
def state():
    probe('status');time.sleep(.25)
    return json.loads(adb('shell','run-as','com.wbox.app','cat','files/probe-state.json'))
def until(predicate,seconds=20):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        s=state()
        if predicate(s):return s
        if s['error']:raise RuntimeError(json.dumps(s,ensure_ascii=False))
        time.sleep(.5)
    raise RuntimeError('State timed out: '+json.dumps(s,ensure_ascii=False))
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body=b'WBOX_TCP_OK\n'+b'X'*4096
        self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass
server=http.server.ThreadingHTTPServer(('0.0.0.0',19281),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
udp=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);udp.bind(('0.0.0.0',19282));udp.settimeout(1)
def echo():
    while True:
        try:data,peer=udp.recvfrom(2048);udp.sendto(data,peer)
        except socket.timeout:continue
        except OSError:return
threading.Thread(target=echo,daemon=True).start()
def tcp(target='10.0.2.2'):return adb('shell','toybox','timeout','8','toybox','nc','-w','5',target,'19281',input=b'GET /probe HTTP/1.0\r\nHost: 8.8.8.8\r\n\r\n')
results={'android':adb('shell','getprop','ro.build.version.release').decode().strip(),'abi':adb('shell','getprop','ro.product.cpu.abi').decode().strip(),'shellUid':adb('shell','id').decode().strip(),'engines':[],'minifiedDebugBuild':'--minified' in sys.argv,'templateMode':TEMPLATES}
try:
    if TEMPLATES:
        upstream_config=ROOT/'.tools/android-template-upstream.json'
        upstream_config.write_text(json.dumps({'log':{'level':'warn'},'inbounds':[{'type':'mixed','listen':'0.0.0.0','listen_port':19283}],'outbounds':[{'type':'direct','tag':'direct'}],'route':{'rules':[{'action':'route-options','override_address':'127.0.0.1'}]}}))
        upstream=subprocess.Popen([str(ROOT/'resources/cores/windows/sing-box.exe'),'run','-c',str(upstream_config)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        time.sleep(1)
        assert upstream.poll() is None,'Synthetic SOCKS upstream failed'
    adb('install','-r',str(ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'),timeout=120)
    adb('shell','pm','grant','com.wbox.app','android.permission.POST_NOTIFICATIONS')
    adb('shell','appops','set','com.wbox.app','ACTIVATE_VPN','allow')
    adb('shell','am','start','-n','com.wbox.app/.MainActivity');time.sleep(1)
    probe('original');time.sleep(2)
    original=adb('shell','run-as','com.wbox.app','cat','files/probe-original.log').decode()
    assert 'netlink socket in Android is banned' in original,original
    results['originalFailureReproduced']=True
    print('PASS original CLI netlink failure reproduced under application UID',flush=True)
    for engine in ['sing-box','mihomo']:
        probe('seed-template' if TEMPLATES else 'seed',engine);time.sleep(.5)
        if TEMPLATES:
            seeded=json.loads(adb('shell','run-as','com.wbox.app','cat','files/profiles.json'))
            assert seeded.get('mode')=='nodes' and seeded.get('engine')==engine and len(seeded.get('nodes',[]))==1,'Template fixture did not seed correctly'
        adb('shell','am','force-stop','com.wbox.app')
        adb('shell','am','start','-n','com.wbox.app/.MainActivity');time.sleep(1)
        probe('connect',engine);connected=until(lambda s:s['running'])
        assert b'WBOX_TCP_OK' in tcp(DESTINATION), 'TCP tunnel forwarding failed'
        # Exercise the template's private-address DIRECT branch for UDP.
        # The synthetic TCP upstream rewrites destination addresses and cannot
        # preserve UDP reply source addresses for a fabricated public endpoint.
        payload=b'WBOX_UDP_OK'
        packet=subprocess.run([ADB,'-s',SERIAL,'shell','toybox','timeout','4','toybox','nc','-u','10.0.2.2','19282'],input=payload,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=10)
        response=packet.stdout
        assert payload in response,repr(response)
        sampled=until(lambda s:s['upload']>0 and s['download']>0)
        assert any('wbox-in' in l or f'{DESTINATION}:19281' in l for l in sampled['logs']),sampled
        (ROOT/f'docs/previews/0.6.5-android-{engine}.png').write_bytes(adb('exec-out','screencap','-p'))
        if TEMPLATES:
            assert any('Template upstream' in l for l in sampled['logs']), 'Traffic did not use the selected template node'
            assert not any('start download' in l or 'updated rule-set' in l for l in sampled['logs']), 'Template downloaded rules at startup'
        mem=adb('shell','dumpsys','meminfo','com.wbox.app').decode()
        probe('stop');stopped=until(lambda s:not s['running'] and s['status']=='未连接')
        assert b'WBOX_TCP_OK' in tcp(),'Network not restored after stop'
        probe('connect',engine);until(lambda s:s['running'])
        assert b'WBOX_TCP_OK' in tcp(DESTINATION),'Restart forwarding failed'
        probe('stop');until(lambda s:not s['running'] and s['status']=='未连接')
        result={'engine':engine,'tcp':True,'udp':True,'tcpViaSelectedNode':TEMPLATES,'udpViaPrivateDirectRule':TEMPLATES,'restart':True,'networkAfterStop':True,'uploaded':sampled['upload'],'downloaded':sampled['download'],'memoryDebugBuild':mem,'logs':sampled['logs']}
        results['engines'].append(result)
        print('PASS',engine,'VPN TCP/UDP forwarding, stop recovery, restart',flush=True)
finally:
    try:probe('stop')
    except Exception:pass
    server.shutdown();server.server_close();udp.close()
    if upstream:upstream.terminate();upstream.wait(timeout=5)
    (ROOT/('docs/verification/0.6.5-android-templates.json' if TEMPLATES else 'docs/verification/0.6.5-android-runtime.json')).write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
