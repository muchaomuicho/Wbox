using Microsoft.Win32;
using System.Diagnostics;
using System.Security.Principal;
using System.Xml.Linq;

namespace Wbox;

public static class StartupRegistration
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public static string TaskName(string sid) => "Wbox-" + sid;
    public static string TaskXml(string executable, string sid, bool silent, bool elevated)
    {
        XNamespace ns = "http://schemas.microsoft.com/windows/2004/02/mit/task";
        XElement Element(string name, object? content) => new(ns + name, content);
        return new XDocument(new XElement(ns + "Task", new XAttribute("version", "1.2"),
            Element("RegistrationInfo", Element("Description", "Wbox 登录启动")),
            Element("Triggers", Element("LogonTrigger", new[] { Element("Enabled", "true"), Element("UserId", sid), Element("Delay", "PT5S") })),
            Element("Principals", new XElement(ns + "Principal", new XAttribute("id", "WboxUser"),
                Element("UserId", sid), Element("LogonType", "InteractiveToken"), Element("RunLevel", elevated ? "HighestAvailable" : "LeastPrivilege"))),
            Element("Settings", new[] { Element("MultipleInstancesPolicy", "IgnoreNew"), Element("DisallowStartIfOnBatteries", "false"),
                Element("StopIfGoingOnBatteries", "false"), Element("StartWhenAvailable", "true"), Element("ExecutionTimeLimit", "PT0S"), Element("Enabled", "true") }),
            new XElement(ns + "Actions", new XAttribute("Context", "WboxUser"), Element("Exec", new[] {
                Element("Command", executable), Element("Arguments", silent ? "--autostart --silent" : "--autostart"),
                Element("WorkingDirectory", Path.GetDirectoryName(executable)) })))).ToString();
    }

    public static string Configure(string executable, bool enabled, bool silent)
    {
        executable = Path.GetFullPath(executable);
        if (!File.Exists(executable) || !Path.GetFileName(executable).Equals("Wbox.exe", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("启动路径不是已安装的 Wbox.exe。");
        // Test instances must never touch the user's real login registration.
        if (Environment.GetEnvironmentVariable("WBOX_DATA_DIR") != null) return "isolated";
        string sid = WindowsIdentity.GetCurrent().User!.Value;
        string name = TaskName(sid);
        var previous = RunScheduler("/Query", "/TN", name, "/XML");
        bool existing = previous.ExitCode == 0;
        try {
            using var run = Registry.CurrentUser.CreateSubKey(RunKey);
            if (!enabled) {
                if (existing) RequireSuccess(RunScheduler("/Delete", "/TN", name, "/F"));
                run.DeleteValue("Wbox", false); run.DeleteValue("electron.app.Wbox", false);
                return "disabled";
            }
            if (StartupPolicy.Elevated || existing) {
                bool elevated = StartupPolicy.Elevated || previous.Output.Contains("HighestAvailable");
                string xml = Path.GetTempFileName();
                try { File.WriteAllText(xml, TaskXml(executable, sid, silent, elevated), Encoding.Unicode); RequireSuccess(RunScheduler("/Create", "/TN", name, "/XML", xml, "/F")); }
                finally { File.Delete(xml); }
                run.DeleteValue("Wbox", false); run.DeleteValue("electron.app.Wbox", false);
                return "task";
            }
            run.SetValue("Wbox", $"\"{executable}\" --autostart" + (silent ? " --silent" : ""));
            run.DeleteValue("electron.app.Wbox", false);
            return "login";
        } catch (Exception error) when(error is IOException or System.ComponentModel.Win32Exception) {
            throw new InvalidOperationException("无法更新 Wbox 登录任务。若此前以管理员身份启用自启，请以管理员身份运行 Wbox 后重新设置。", error);
        }
    }
    static (int ExitCode,string Output) RunScheduler(params string[] arguments)
    {
        var start = new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "schtasks.exe")) { CreateNoWindow=true, UseShellExecute=false, RedirectStandardOutput=true, RedirectStandardError=true };
        foreach(string argument in arguments) start.ArgumentList.Add(argument);
        using var process=Process.Start(start)!;var output=process.StandardOutput.ReadToEndAsync();var error=process.StandardError.ReadToEndAsync();
        if(!process.WaitForExit(15000)){process.Kill();throw new IOException("登录任务更新超时。");}
        return (process.ExitCode,output.GetAwaiter().GetResult()+error.GetAwaiter().GetResult());
    }
    static void RequireSuccess((int ExitCode,string Output) result) {if(result.ExitCode!=0)throw new IOException("登录任务更新失败，系统返回代码 "+result.ExitCode);}
}
