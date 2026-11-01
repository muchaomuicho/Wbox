#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

// A private, hidden console lets the unmodified Go cores receive os.Interrupt.
// The helper owns only its child; it never signals another proxy's console.
static DWORD WINAPI read_stop(void *event) {
    char buffer[16]; DWORD count;
    ReadFile(GetStdHandle(STD_INPUT_HANDLE), buffer, sizeof(buffer), &count, NULL);
    SetEvent((HANDLE)event); // EOF also closes the core when its parent exits.
    return 0;
}
static BOOL WINAPI ignore_signal(DWORD event) { return event == CTRL_C_EVENT || event == CTRL_BREAK_EVENT; }
int wmain(void) {
    wchar_t *command = GetCommandLineW();
    if (*command == L'"') { command++; while (*command && *command != L'"') command++; if (*command) command++; }
    else while (*command && *command != L' ') command++;
    while (*command == L' ') command++;
    if (!*command) return 2;
    HANDLE job = CreateJobObjectW(NULL, NULL);
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {0};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!job || !SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) return 3;
    STARTUPINFOW startup = {0}; startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    startup.wShowWindow = SW_HIDE;
    SECURITY_ATTRIBUTES security = {sizeof(security), NULL, TRUE};
    startup.hStdInput = CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, &security, OPEN_EXISTING, 0, NULL);
    startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    PROCESS_INFORMATION child = {0};
    if (!CreateProcessW(NULL, command, NULL, NULL, TRUE, CREATE_NEW_CONSOLE | CREATE_SUSPENDED, NULL, NULL, &startup, &child)) return 4;
    CloseHandle(startup.hStdInput);
    if (!AssignProcessToJobObject(job, child.hProcess)) { TerminateProcess(child.hProcess, 5); return 5; }
    printf("__WBOX_CORE_PID__%lu\n", child.dwProcessId); fflush(stdout);
    ResumeThread(child.hThread); CloseHandle(child.hThread);
    HANDLE stop = CreateEventW(NULL, TRUE, FALSE, NULL);
    HANDLE reader = CreateThread(NULL, 0, read_stop, stop, 0, NULL);
    HANDLE waits[] = {child.hProcess, stop};
    if (WaitForMultipleObjects(2, waits, FALSE, INFINITE) == WAIT_OBJECT_0 + 1) {
        FreeConsole();
        if (AttachConsole(child.dwProcessId)) {
            SetConsoleCtrlHandler(ignore_signal, TRUE);
            if (!GenerateConsoleCtrlEvent(CTRL_BREAK_EVENT, 0)) fprintf(stderr, "WARN Wbox: signal failed (%lu)\n", GetLastError());
        } else fprintf(stderr, "WARN Wbox: console attach failed (%lu)\n", GetLastError());
        if (WaitForSingleObject(child.hProcess, 5000) == WAIT_TIMEOUT) {
            fprintf(stderr, "WARN Wbox: core shutdown timed out; forcing exit\n");
            TerminateJobObject(job, 6); WaitForSingleObject(child.hProcess, 1000);
        }
        FreeConsole();
    }
    DWORD code = 0; GetExitCodeProcess(child.hProcess, &code);
    CloseHandle(child.hProcess); CloseHandle(job); CloseHandle(reader);
    return (int)code;
}
