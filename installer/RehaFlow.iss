[Setup]
AppId={{65B64984-5079-44FB-A2C8-A15648715486}
AppName=RehaFlow
AppVersion=2.1
AppPublisher=QureMed Industries
DefaultDirName={autopf}\QureMed\RehaFlow
DefaultGroupName=RehaFlow
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\artifacts\installer
OutputBaseFilename=RehaFlow-Setup-x64
SetupIconFile=..\desktop\QureMed.Desktop\Assets\RehaFlow.ico
UninstallDisplayIcon={app}\Assets\RehaFlow.ico
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Languages]
Name: "ukrainian"; MessagesFile: "compiler:Languages\Ukrainian.isl"

[Types]
Name: "client"; Description: "Робоче місце — підключення до наявного сервера"
Name: "server"; Description: "Сервер центру — база даних на цьому ПК та робоче місце"

[Components]
Name: "desktop"; Description: "Застосунок RehaFlow"; Types: client server; Flags: fixed
Name: "server"; Description: "Локальний сервер і PostgreSQL"; Types: server

[Files]
Source: "..\artifacts\desktop\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\artifacts\server-source\*"; DestDir: "{commonappdata}\QureMed\Server"; Components: server; Flags: ignoreversion recursesubdirs createallsubdirs uninsneveruninstall

[Icons]
Name: "{group}\RehaFlow"; Filename: "{app}\RehaFlow.exe"
Name: "{autodesktop}\RehaFlow"; Filename: "{app}\RehaFlow.exe"
Name: "{group}\Запустити сервер RehaFlow"; Filename: "{commonappdata}\QureMed\Server\Start-QureMed-Without-Docker.cmd"; WorkingDir: "{commonappdata}\QureMed\Server"; Components: server; IconFilename: "{app}\Assets\RehaFlow.ico"

[Run]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\Configure-Client.ps1"""; Description: "Налаштувати адресу сервера та сертифікат цього робочого місця"; Flags: postinstall skipifsilent runasoriginaluser; Components: desktop; Check: IsClient
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{commonappdata}\QureMed\Server\scripts\Install-Server.ps1"""; Description: "Встановити залежності та запустити сервер (потрібен інтернет)"; WorkingDir: "{commonappdata}\QureMed\Server"; Flags: postinstall skipifsilent nowait; Components: server
Filename: "{app}\RehaFlow.exe"; Description: "Відкрити RehaFlow"; Flags: postinstall skipifsilent nowait unchecked runasoriginaluser

[Code]
function IsClient: Boolean;
begin
  Result := not WizardIsComponentSelected('server');
end;
