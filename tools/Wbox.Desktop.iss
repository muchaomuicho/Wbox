[Setup]
AppId={{49D04E75-742E-4E26-B77D-412E21C1D2AC}
AppName=Wbox
AppVersion=0.6.6
AppPublisher=Wbox
DefaultDirName={localappdata}\Programs\Wbox
DefaultGroupName=Wbox
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\dist
OutputBaseFilename=Wbox-0.6.6-windows-x64-setup
SetupIconFile=..\resources\wbox.ico
UninstallDisplayIcon={app}\Wbox.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
MinVersion=10.0

[Languages]
Name: "chinesesimp"; MessagesFile: "installer\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "快捷方式："; Flags: unchecked

[Files]
Source: "..\windows\desktop-publish\win-unpacked\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "*.pdb"
Source: "..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\THIRD-PARTY-NOTICES.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\docs\RELEASE-0.6.6.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\resources\cores\licenses\*"; DestDir: "{app}\licenses"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Wbox"; Filename: "{app}\Wbox.exe"
Name: "{autodesktop}\Wbox"; Filename: "{app}\Wbox.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "Wbox"; Flags: dontcreatekey uninsdeletevalue

[UninstallRun]
Filename: "{app}\resources\backend\Wbox.Backend.exe"; Parameters: "--remove-startup ""{app}\Wbox.exe"""; Flags: runhidden waituntilterminated; RunOnceId: "RemoveWboxLoginTask"

[Run]
; ShellExecute honors RUNASADMIN compatibility settings and allows Windows to
; request elevation instead of failing CreateProcess with error 740.
Filename: "{app}\Wbox.exe"; WorkingDir: "{app}"; Description: "启动 Wbox"; Flags: shellexec nowait postinstall skipifsilent runasoriginaluser
