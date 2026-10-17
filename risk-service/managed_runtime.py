"""Run the model server for one Java backend and exit if that backend disappears."""

import argparse
import ctypes
from ctypes import wintypes
import os
import threading

import uvicorn


class ParentMonitor:
    def __init__(self, pid):
        self.pid = pid
        self.handle = None
        self.closed = threading.Event()
        if os.name == "nt":
            self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            self.kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            self.kernel.OpenProcess.restype = wintypes.HANDLE
            self.kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
            self.kernel.WaitForSingleObject.restype = wintypes.DWORD
            self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
            self.kernel.CloseHandle.restype = wintypes.BOOL
            # The retained handle identifies this exact parent even if its PID is later reused.
            self.handle = self.kernel.OpenProcess(0x00100000, False, pid)
            if not self.handle:
                raise RuntimeError("Cannot monitor the Java backend process")

    def alive(self):
        if self.handle:
            return self.kernel.WaitForSingleObject(self.handle, 0) == 0x00000102
        try:
            os.kill(self.pid, 0)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            return True

    def watch(self, server):
        while not self.closed.wait(1):
            if not self.alive():
                server.should_exit = True
                # Also covers a backend killed while model imports or shutdown are still in progress.
                if not self.closed.wait(10):
                    os._exit(0)
                return

    def close(self):
        self.closed.set()
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--parent-pid", type=int, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8001)
    args = parser.parse_args()
    if args.parent_pid <= 0 or not os.environ.get("IHMS_MODEL_OWNER"):
        raise RuntimeError("This entry point must be launched by the Java backend")
    parent = ParentMonitor(args.parent_pid)
    try:
        if not parent.alive():
            return
        server = uvicorn.Server(uvicorn.Config("unified_app:app", host=args.host, port=args.port, access_log=False))
        threading.Thread(target=parent.watch, args=(server,), daemon=True, name="java-parent-monitor").start()
        server.run()
    finally:
        parent.close()


if __name__ == "__main__":
    main()
