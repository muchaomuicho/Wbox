using System.Diagnostics;
using System.Runtime.InteropServices;
namespace Wbox;

public sealed class ProcessJob : IDisposable
{
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attributes, string? name);
    [DllImport("kernel32.dll")] static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint length);
    [DllImport("kernel32.dll")] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [StructLayout(LayoutKind.Sequential)] struct BasicLimit { public long PerProcessTime, PerJobTime; public uint Flags; public UIntPtr MinWork, MaxWork; public uint Active; public UIntPtr Affinity; public uint Priority, Scheduling; }
    [StructLayout(LayoutKind.Sequential)] struct IoCounters { public ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes; }
    [StructLayout(LayoutKind.Sequential)] struct ExtendedLimit { public BasicLimit Basic; public IoCounters Io; public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory; }
    readonly IntPtr handle;
    public ProcessJob()
    {
        handle = CreateJobObject(IntPtr.Zero, null); if (handle == IntPtr.Zero) throw new IOException("无法创建内核进程 Job。");
        var info = new ExtendedLimit { Basic = new BasicLimit { Flags = 0x2000 } };
        var pointer = Marshal.AllocHGlobal(Marshal.SizeOf<ExtendedLimit>());
        try { Marshal.StructureToPtr(info, pointer, false); if (!SetInformationJobObject(handle, 9, pointer, (uint)Marshal.SizeOf<ExtendedLimit>())) throw new IOException("无法设置内核退出保护。"); }
        finally { Marshal.FreeHGlobal(pointer); }
    }
    public void Add(Process process) { if (!AssignProcessToJobObject(handle, process.Handle)) throw new IOException("无法为内核设置退出保护。"); }
    public void Dispose() => CloseHandle(handle);
}
