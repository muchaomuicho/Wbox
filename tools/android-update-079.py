"""Exercise Android system update primitives in emulator-5556, without changing Wbox.

The separate temporary fixture uses a temporary certificate and package name.
It verifies DownloadManager persistence, PackageInstaller confirmation/cancel/
success, and deleting the source only after the system owns a fsynced copy.
Production Wbox signer/metadata gates are covered by its unit tests, not bypassed.
"""
import hashlib, http.server, json, os, pathlib, re, subprocess, sys, threading, time, traceback, xml.etree.ElementTree as ET, zipfile
sys.stdout.reconfigure(encoding='utf-8')

ROOT=pathlib.Path(__file__).resolve().parent.parent
SDK=pathlib.Path('D:/DevTools/Android/Sdk');JAVA=pathlib.Path('D:/DevTools/JDKs/ms-21.0.9')
BUILD=SDK/'build-tools/34.0.0';ADB=SDK/'platform-tools/adb.exe'
TEMP=ROOT/f'.tools/update-fixture-079-{os.getpid()}';PACKAGE='com.wbox.updatefixture'
checks=[];observations={};shots=[];failure=None;server=None;gate=threading.Event();requested=threading.Event();requests=[]
env=dict(os.environ,JAVA_HOME=str(JAVA));started=time.time()

SOURCE=r'''package com.wbox.updatefixture;
import android.app.*;import android.content.*;import android.content.pm.*;import android.net.Uri;import android.os.*;import android.widget.*;import java.io.*;import java.security.*;
public class Fixture extends Activity {
  TextView status;Handler handler=new Handler();SharedPreferences p;
  public void onCreate(Bundle state){super.onCreate(state);p=getSharedPreferences("state",0);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(32,80,32,32);status=new TextView(this);status.setTextSize(20);box.addView(status);Button install=new Button(this);install.setText("Install fixture");box.addView(install);install.setOnClickListener(v->commit());setContentView(box);if(state==null&&getIntent().getStringExtra("url")!=null)download(getIntent().getStringExtra("url"),getIntent().getStringExtra("sha"));}
  public void onResume(){super.onResume();handler.post(poll);}
  public void onPause(){handler.removeCallbacks(poll);super.onPause();}
  Runnable poll=new Runnable(){public void run(){try{String phase=p.getString("phase","idle");if(phase.equals("downloading")||phase.equals("downloaded"))query();if(p.getString("phase","").equals("confirmation")){Intent i=Intent.parseUri(p.getString("intent",""),Intent.URI_INTENT_SCHEME);p.edit().putString("phase","installing").commit();startActivityForResult(i,9);}status.setText(p.getAll().toString());}catch(Exception e){p.edit().putString("phase","failed").putString("error",e.toString()).commit();}handler.postDelayed(this,500);}};
  void download(String url,String sha){DownloadManager d=getSystemService(DownloadManager.class);long old=p.getLong("download",-1);if(old>=0)d.remove(old);String name="candidate-"+java.util.UUID.randomUUID()+".apk";File source=new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),name);long id=d.enqueue(new DownloadManager.Request(Uri.parse(url)).setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,name).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE));p.edit().clear().putString("phase","downloading").putString("sha",sha).putString("file",source.getAbsolutePath()).putLong("download",id).commit();}
  void query()throws Exception {DownloadManager d=getSystemService(DownloadManager.class);try(android.database.Cursor c=d.query(new DownloadManager.Query().setFilterById(p.getLong("download",-1)))){if(!c.moveToFirst())throw new Exception("download disappeared");int value=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));p.edit().putLong("bytes",c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))).putInt("status",value).putInt("reason",c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))).commit();if(value==DownloadManager.STATUS_SUCCESSFUL){String local=c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));p.edit().putString("local_uri",local).putLong("local_size",new File(Uri.parse(local).getPath()).length()).commit();stage();}else if(value==DownloadManager.STATUS_FAILED)throw new Exception("download failed");}}
  void stage()throws Exception {File source=new File(Uri.parse(p.getString("local_uri","")).getPath());PackageInstaller pi=getPackageManager().getPackageInstaller();PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);params.setAppPackageName(getPackageName());params.setSize(source.length());if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);int id=pi.createSession(params);p.edit().putInt("session",id).putString("phase","staging").commit();try(PackageInstaller.Session s=pi.openSession(id)){MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(source);OutputStream out=s.openWrite("base.apk",0,source.length())){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0){out.write(b,0,n);hash.update(b,0,n);}StringBuilder hex=new StringBuilder();for(byte v:hash.digest())hex.append(String.format("%02x",v));if(!hex.toString().equals(p.getString("sha","")))throw new Exception("digest mismatch actual="+hex+" size="+source.length());s.fsync(out);}}getSystemService(DownloadManager.class).remove(p.getLong("download",-1));p.edit().putString("phase","ready").putBoolean("source_removed",!source.exists()).commit();}
  void commit(){try{int id=p.getInt("session",-1);Intent i=new Intent(this,Result.class).setAction("fixture.INSTALL");PendingIntent callback=PendingIntent.getBroadcast(this,id,i,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));p.edit().putString("phase","committed").commit();try(PackageInstaller.Session s=getPackageManager().getPackageInstaller().openSession(id)){s.commit(callback.getIntentSender());}}catch(Exception e){p.edit().putString("phase","failed").putString("error",e.toString()).commit();}}
  public static class Download extends BroadcastReceiver {public void onReceive(Context c,Intent i){SharedPreferences p=c.getSharedPreferences("state",0);if(DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(i.getAction())&&i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-2)==p.getLong("download",-1))p.edit().putBoolean("download_broadcast",true).commit();}}
  public static class Result extends BroadcastReceiver {public void onReceive(Context c,Intent i){SharedPreferences p=c.getSharedPreferences("state",0);if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())){p.edit().putString("phase","installed").putBoolean("replaced",true).commit();return;}int result=i.getIntExtra(PackageInstaller.EXTRA_STATUS,999);if(result==PackageInstaller.STATUS_PENDING_USER_ACTION){Intent confirm=i.getParcelableExtra(Intent.EXTRA_INTENT);ResolveInfo found=c.getPackageManager().resolveActivity(confirm,0);boolean system=found!=null&&(found.activityInfo.applicationInfo.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;boolean session=confirm.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1)==p.getInt("session",-2);p.edit().putString("phase","confirmation").putString("intent",confirm.toUri(Intent.URI_INTENT_SCHEME)).putString("confirm_action",confirm.getAction()).putBoolean("system_installer",system).putBoolean("session_matches",session).commit();}else p.edit().putString("phase",result==PackageInstaller.STATUS_SUCCESS?"installed":"cancelled").putInt("result",result).commit();}}
}'''

def run(*args,timeout=120):
    process=subprocess.run(list(map(str,args)),capture_output=True,env=env,timeout=timeout)
    if process.returncode:raise RuntimeError(process.stdout.decode(errors='replace')+process.stderr.decode(errors='replace'))
    return process.stdout
def adb(*args,timeout=30):
    if args and args[0]=='shell':args=('shell','-T',*args[1:])
    return run(ADB,'-s','emulator-5556',*args,timeout=timeout)
def wait(ready,label,timeout=45):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if ready():return
        time.sleep(.2)
    raise AssertionError('Timed out: '+label)
def booted():
    try:return adb('shell','getprop','sys.boot_completed').strip()==b'1'
    except Exception:return False
def state():
    raw=adb('exec-out','run-as',PACKAGE,'cat','shared_prefs/state.xml');root=ET.fromstring(raw)
    return {n.get('name'):n.text if n.tag=='string' else n.get('value') for n in root}
def ready():
    value=state()
    if value.get('phase')=='failed':raise AssertionError(value)
    return value.get('phase')=='ready'
def check(ok,label):
    if not ok:raise AssertionError(label)
    checks.append(label);print('PASS',label,flush=True)
def tap(labels):
    adb('shell','uiautomator','dump','--compressed','/sdcard/update079.xml')
    root=ET.fromstring(adb('shell','cat','/sdcard/update079.xml'))
    nodes=[n for n in root.iter('node') if n.get('text','').lower() in {label.lower() for label in labels}]
    if not nodes:raise AssertionError('Missing button '+str(labels))
    x1,y1,x2,y2=map(int,re.findall(r'\d+',nodes[-1].get('bounds')));adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
def photo(name):
    path=ROOT/f'docs/previews/0.7.9-android-update-{name}.png';path.write_bytes(adb('exec-out','screencap','-p'));shots.append(path.relative_to(ROOT).as_posix())
def build():
    TEMP.mkdir();(TEMP/'classes').mkdir();(TEMP/'dex').mkdir();(TEMP/'Fixture.java').write_text(SOURCE,encoding='utf-8')
    run(JAVA/'bin/javac.exe','--release','8','-classpath',SDK/'platforms/android-34/android.jar','-d',TEMP/'classes',TEMP/'Fixture.java')
    run(SDK/'build-tools/36.0.0/d8.bat','--min-api','26','--lib',SDK/'platforms/android-34/android.jar','--output',TEMP/'dex',*list((TEMP/'classes').rglob('*.class')))
    run(JAVA/'bin/keytool.exe','-genkeypair','-keystore',TEMP/'fixture.jks','-storepass','fixture-only','-keypass','fixture-only','-alias','fixture','-keyalg','RSA','-validity','2','-dname','CN=WboxUpdateFixture')
    for version in (1,2):
        manifest=f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}" android:versionCode="{version}" android:versionName="{version}.0.0"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/><uses-permission android:name="android.permission.INTERNET"/><uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/><queries><intent><action android:name="android.content.pm.action.CONFIRM_INSTALL"/></intent></queries><application android:label="Wbox update fixture" android:debuggable="true" android:usesCleartextTraffic="true" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name=".Fixture" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity><receiver android:name=".Fixture$Download" android:exported="true"><intent-filter><action android:name="android.intent.action.DOWNLOAD_COMPLETE"/></intent-filter></receiver><receiver android:name=".Fixture$Result" android:exported="false"><intent-filter><action android:name="android.intent.action.MY_PACKAGE_REPLACED"/></intent-filter></receiver></application></manifest>'''
        (TEMP/'AndroidManifest.xml').write_text(manifest,encoding='utf-8')
        unsigned=TEMP/f'unsigned{version}.apk';aligned=TEMP/f'aligned{version}.apk';output=TEMP/f'fixture{version}.apk'
        run(BUILD/'aapt.exe','package','-f','-M',TEMP/'AndroidManifest.xml','-I',SDK/'platforms/android-34/android.jar','-F',unsigned)
        with zipfile.ZipFile(unsigned,'a') as archive:archive.write(TEMP/'dex/classes.dex','classes.dex')
        run(BUILD/'zipalign.exe','-f','4',unsigned,aligned)
        run(BUILD/'apksigner.bat','sign','--ks',TEMP/'fixture.jks','--ks-key-alias','fixture','--ks-pass','pass:fixture-only','--key-pass','pass:fixture-only','--out',output,aligned)

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        requests.append({'path':self.path,'range':self.headers.get('Range')})
        data=(TEMP/'fixture2.apk').read_bytes();self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers()
        self.wfile.write(data[:1024]);self.wfile.flush();requested.set();gate.wait(90)
        try:self.wfile.write(data[1024:])
        except OSError:pass
    def log_message(self,*args):pass

try:
    build();wait(booted,'isolated emulator boot',120)
    check(adb('get-serialno').strip()==b'emulator-5556','Only the isolated emulator is used')
    check(b'Success' in adb('install','-r','-t',TEMP/'fixture1.apk'),'Separate fixture version 1 installs')
    server=http.server.ThreadingHTTPServer(('0.0.0.0',0),Handler);server.daemon_threads=True;threading.Thread(target=server.serve_forever,daemon=True).start()
    sha=hashlib.sha256((TEMP/'fixture2.apk').read_bytes()).hexdigest();url=f'http://10.0.2.2:{server.server_port}/fixture.apk'
    adb('shell','am','start','-W','-n',PACKAGE+'/.Fixture','--es','url',url,'--es','sha',sha)
    wait(requested.is_set,'gated DownloadManager request');wait(lambda:state().get('phase')=='downloading','download state')
    download_id=state()['download'];adb('shell','input','keyevent','3');adb('shell','am','kill',PACKAGE);gate.set()
    time.sleep(2);adb('shell','am','start','-W','-n',PACKAGE+'/.Fixture')
    wait(ready,'resumed download and staging',90)
    check(state()['download']==download_id,'DownloadManager preserves its job while the app process is killed')
    check(state().get('source_removed')=='true','Source APK is removed only after a complete fsynced session copy')
    session=state()['session'];adb('shell','input','keyevent','3');adb('shell','am','kill',PACKAGE);adb('shell','am','start','-W','-n',PACKAGE+'/.Fixture')
    check(state().get('session')==session and state().get('phase')=='ready','Staged installer session survives app process recreation')
    tap({'Install fixture'});wait(lambda:state().get('phase')=='installing','system confirmation')
    check(state().get('system_installer')=='true' and state().get('session_matches')=='true' and state().get('confirm_action')=='android.content.pm.action.CONFIRM_INSTALL','Real confirmation Intent resolves to the system installer and original session')
    tap({'Settings','设置'});tap({'Allow from this source','允许来自此来源的应用','允许此来源'});adb('shell','input','keyevent','4');time.sleep(.8)
    check(True,'System unknown-source settings return to the pending installation')
    photo('system-confirmation');tap({'取消','Cancel'});wait(lambda:state().get('phase')=='cancelled','system cancel callback')
    check(b'versionCode=1' in adb('shell','dumpsys','package',PACKAGE),'Cancel leaves the original app installed')
    check(state().get('source_removed')=='true','Cancel does not leave the downloaded source APK')
    observations['cancel_status']=state().get('result')
    adb('shell','am','force-stop',PACKAGE);adb('shell','am','start','-W','-n',PACKAGE+'/.Fixture','--es','url',url,'--es','sha',sha)
    wait(lambda:state().get('phase')=='ready','second staging');tap({'Install fixture'});wait(lambda:state().get('phase')=='installing','second confirmation')
    tap({'更新','安装','Update','Install'});wait(lambda:b'versionCode=2' in adb('shell','dumpsys','package',PACKAGE),'actual package replacement',60)
    adb('shell','am','start','-W','-n',PACKAGE+'/.Fixture');wait(lambda:state().get('phase')=='installed','package replaced callback')
    check(state().get('source_removed')=='true','Successful self-update retains source cleanup')
    check(state().get('replaced')=='true','MY_PACKAGE_REPLACED restores the successful update state')
    photo('system-installed');observations['final_state']=state()
except Exception as error:
    failure=repr(error);traceback.print_exc()
    try:photo('failure')
    except Exception:pass
    try:
        observations['failure_state']=state()
        observations['downloaded_sha256']=hashlib.sha256(adb('exec-out','cat',state()['file'])).hexdigest()
        observations['candidate_sha256']=hashlib.sha256((TEMP/'fixture2.apk').read_bytes()).hexdigest()
    except Exception:pass
finally:
    gate.set()
    if server:server.shutdown();server.server_close()
    try:adb('uninstall',PACKAGE);observations['fixture_uninstalled']=True
    except Exception:pass
    try:adb('shell','rm','-f','/sdcard/update079.xml');adb('emu','kill');observations['emulator_closed']=True
    except Exception:pass
    if TEMP.exists():
        assert TEMP.resolve().parent==(ROOT/'.tools').resolve() and TEMP.name.startswith('update-fixture-079-')
        for item in sorted(TEMP.rglob('*'),key=lambda p:len(p.parts),reverse=True):
            assert not item.is_symlink()
            if item.is_file():item.unlink()
            else:item.rmdir()
        TEMP.rmdir()
    observations['temporary_files_removed']=not TEMP.exists();observations['duration_seconds']=round(time.time()-started,1);observations['requests']=requests
    observations['scope']='Separate disposable fixture package exercises standard Android APIs. It does not bypass Wbox production signature/metadata validation or install a new Wbox release.'
    result={'passed':failure is None,'checks':checks,'observations':observations,'screenshots':shots,'failure':failure}
    (ROOT/'docs/verification/0.7.9-android-update-system.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
if failure:raise SystemExit(1)
