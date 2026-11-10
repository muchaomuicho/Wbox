using System.Diagnostics;
using System.Security.Principal;
using System.Text;
using System.Xml.Linq;
using Wbox;

static class NativeStartupChecks
{
    public static async Task Run()
    {
        string name = "Wbox-Verification-" + Guid.NewGuid().ToString("N");
        string directory = Path.Combine(Store.DirectoryPath, name);
        Directory.CreateDirectory(directory);
        string marker = Path.Combine(directory, "started.txt"), xmlPath = Path.Combine(directory, "task.xml");
        string sid = WindowsIdentity.GetCurrent().User!.Value;
        bool elevated = StartupPolicy.Elevated;
        string executable = Path.Combine(AppContext.BaseDirectory, "Wbox.Tests.exe");
        XNamespace ns = "http://schemas.microsoft.com/windows/2004/02/mit/task";
        var xml = XDocument.Parse(StartupRegistration.TaskXml(executable, sid, true, elevated));
        xml.Descendants(ns + "Arguments").Single().Value = "--startup-child \"" + marker + "\"";
        File.WriteAllText(xmlPath, xml.ToString(), Encoding.Unicode);
        bool created = false;
        try {
            RunScheduler("/Create", "/TN", name, "/XML", xmlPath, "/F"); created = true;
            RunScheduler("/Run", "/TN", name);
            var deadline = Stopwatch.StartNew();
            while (!File.Exists(marker) && deadline.Elapsed < TimeSpan.FromSeconds(20)) await Task.Delay(200);
            if (!File.Exists(marker) || File.ReadAllText(marker).Trim() != "started") throw new Exception("Scheduled action did not run: " + RunScheduler("/Query", "/TN", name, "/V", "/FO", "LIST"));
            Console.WriteLine("PASS Windows Task Scheduler accepts and executes generated login-task XML; elevated=" + elevated);
        } finally {
            if (created) { RunScheduler("/End", "/TN", name); RunScheduler("/Delete", "/TN", name, "/F"); }
            Directory.Delete(directory, true);
        }
    }
    static string RunScheduler(params string[] args)
    {
        var start = new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "schtasks.exe")) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (string arg in args) start.ArgumentList.Add(arg);
        using var process = Process.Start(start)!;
        var output = process.StandardOutput.ReadToEndAsync(); var error = process.StandardError.ReadToEndAsync();
        if (!process.WaitForExit(15000)) { process.Kill(); throw new Exception("Task Scheduler timed out"); }
        if (process.ExitCode != 0) throw new Exception("Task Scheduler: " + output.GetAwaiter().GetResult() + error.GetAwaiter().GetResult());
        return output.GetAwaiter().GetResult();
    }
}
