// AIDL interface exposed by the Shizuku UserService.
// Runs in a process with shell UID (uid=2000); has access to
// restricted APIs unavailable to normal app processes.
package com.nexcore.optimizer.service;

interface IUserService {
    /** Execute a shell command as uid=2000 (shell), return stdout. */
    String executeCommand(String cmd);

    /** Read a file readable by shell but not by the app process (e.g. /proc/). */
    String readFile(String path);

    /** Write content to a file writable by shell. */
    boolean writeFile(String path, String content);

    void destroy();
}