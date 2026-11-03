#!/usr/bin/env python3
"""Starts Warp with its console on a pipe or a pseudo-terminal, types commands, checks the replies,
checks that the log file holds no escape sequence, then stops Warp with /shutdown and checks that
it stopped cleanly.

    console-smoke.py --jar proxy/build/libs/warp-0.1.0.jar --mode pipe
    console-smoke.py --jar proxy/build/libs/warp-0.1.0.jar --mode pty

`pipe` gives Warp a pipe for standard input and output: the plain console (what systemd, Docker
without a TTY and panels without one get). `pty` gives it a pseudo-terminal (a ConPTY on Windows,
through pywinpty): the interactive console, with JLine, its prompt and its status line.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

ESC = "\x1b"
# CSI, OSC (ended by BEL or ST) and two-character escape sequences.
ANSI = re.compile(r"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\)|[@-Z\\-_])")
READY = re.compile(r"Ready in [0-9.]+ s")
STOPPED = re.compile(r"Stopped in [0-9.]+ s")

# What is typed, and what the reply must contain once escape sequences are removed.
COMMANDS = [
    ("glist", re.compile(r"0 players online")),
    ("log tail 3", re.compile(r"last 3 lines")),
    ("warp startup", re.compile(r"Started in [0-9.]+ m?s:\s+- jvm ")),
]

class Process:
    """Warp, its output collected by a thread, whatever carries it."""

    def __init__(self) -> None:
        self.output = ""
        self.lock = threading.Lock()

    def append(self, text: str) -> None:
        with self.lock:
            self.output += text

    def text(self) -> str:
        with self.lock:
            return self.output

    def plain(self) -> str:
        return ANSI.sub("", self.text()).replace("\r", "")

    def send(self, line: str) -> None:
        raise NotImplementedError

    def wait(self, timeout: float) -> int | None:
        raise NotImplementedError

    def kill(self) -> None:
        raise NotImplementedError


class PipeProcess(Process):
    def __init__(self, command: list[str], cwd: Path, env: dict[str, str]) -> None:
        super().__init__()
        self.process = subprocess.Popen(
            command,
            cwd=cwd,
            env=env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        while chunk := self.process.stdout.read1(65536):
            self.append(chunk.decode("utf-8", "replace"))

    def send(self, line: str) -> None:
        assert self.process.stdin is not None
        self.process.stdin.write((line + "\n").encode())
        self.process.stdin.flush()

    def wait(self, timeout: float) -> int | None:
        try:
            return self.process.wait(timeout)
        except subprocess.TimeoutExpired:
            return None

    def kill(self) -> None:
        self.process.kill()


class PosixPtyProcess(Process):
    def __init__(self, command: list[str], cwd: Path, env: dict[str, str]) -> None:
        import fcntl
        import pty
        import struct
        import termios

        super().__init__()
        pid, fd = pty.fork()
        if pid == 0:
            os.chdir(cwd)
            os.execvpe(command[0], command, env)
        self.pid = pid
        self.fd = fd
        self.status: int | None = None
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 140, 0, 0))
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        while True:
            try:
                chunk = os.read(self.fd, 65536)
            except OSError:
                return
            if not chunk:
                return
            self.append(chunk.decode("utf-8", "replace"))

    def send(self, line: str) -> None:
        os.write(self.fd, (line + "\r").encode())

    def wait(self, timeout: float) -> int | None:
        deadline = time.monotonic() + timeout
        while self.status is None:
            pid, status = os.waitpid(self.pid, os.WNOHANG)
            if pid != 0:
                self.status = os.waitstatus_to_exitcode(status)
            elif time.monotonic() >= deadline:
                break
            else:
                time.sleep(0.1)
        return self.status

    def kill(self) -> None:
        import signal

        if self.status is None:
            os.kill(self.pid, signal.SIGKILL)


class WindowsPtyProcess(Process):
    def __init__(self, command: list[str], cwd: Path, env: dict[str, str]) -> None:
        from winpty import PtyProcess  # pip install pywinpty

        super().__init__()
        self.process = PtyProcess.spawn(
            subprocess.list2cmdline(command), cwd=str(cwd), env=env, dimensions=(40, 140)
        )
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        while True:
            try:
                chunk = self.process.read(65536)
            except EOFError:
                return
            except Exception:  # the ConPTY closed under the reader
                return
            if chunk:
                self.append(chunk)
            elif not self.process.isalive():
                return

    def send(self, line: str) -> None:
        self.process.write(line + "\r")

    def wait(self, timeout: float) -> int | None:
        deadline = time.monotonic() + timeout
        while True:
            if not self.process.isalive():
                return self.process.exitstatus
            if time.monotonic() >= deadline:
                break
            time.sleep(0.1)
        return None

    def kill(self) -> None:
        self.process.terminate(force=True)


def wait_for(process: Process, pattern: re.Pattern[str], start: int, timeout: float) -> int:
    """Waits until the output after `start` (escape sequences removed) matches; returns its end."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = process.plain()
        match = pattern.search(text, start)
        if match:
            return match.end()
        status = process.wait(0.1)
        if status is not None:
            raise AssertionError(f"Warp exited with code {status} before /{pattern.pattern}/")
    raise AssertionError(f"timed out after {timeout:.0f} s waiting for /{pattern.pattern}/")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--mode", choices=["pipe", "pty"], required=True)
    parser.add_argument("--keep", action="store_true", help="keep the working directory")
    args = parser.parse_args()

    # Warp writes its default warp.conf there on first start, as on a new install.
    directory = Path(tempfile.mkdtemp(prefix="warp-console-smoke-"))
    java = shutil.which("java")
    if java is None:
        print("::error::java is not on the PATH")
        return 1
    command = [java, "-Xmx256m", "-jar", str(args.jar.resolve())]
    env = dict(os.environ)
    for variable in ("WARP_COLOR", "WARP_CONSOLE", "WARP_THEME", "NO_COLOR", "COLORFGBG"):
        env.pop(variable, None)
    if args.mode == "pty" and os.name != "nt":
        env["TERM"] = "xterm-256color"

    if args.mode == "pipe":
        process: Process = PipeProcess(command, directory, env)
    elif os.name == "nt":
        process = WindowsPtyProcess(command, directory, env)
    else:
        process = PosixPtyProcess(command, directory, env)

    failures: list[str] = []
    try:
        position = wait_for(process, READY, 0, 180)
        print(f"ready after {position} characters of output")
        for typed, expected in COMMANDS:
            process.send(typed)
            position = wait_for(process, expected, position, 30)
            print(f"'{typed}' answered")
        process.send("shutdown")
        status = process.wait(90)
        if status is None:
            failures.append("Warp did not stop within 90 s of /shutdown")
        elif status != 0:
            failures.append(f"Warp stopped with exit code {status}, not 0")
        if not STOPPED.search(process.plain()):
            failures.append("the console never said 'Stopped in'")
    except AssertionError as e:
        failures.append(str(e))
    finally:
        if process.wait(0) is None:
            process.kill()

    output = process.text()
    if args.mode == "pipe" and ESC in output:
        failures.append("the plain console (a pipe) wrote escape sequences")
    if args.mode == "pty":
        if ESC not in output:
            failures.append("the console on a pseudo-terminal wrote no colour")
        if "conn/s" not in process.plain():
            failures.append("the console on a pseudo-terminal showed no status line")

    log = directory / "logs" / "warp.log"
    if not log.is_file():
        failures.append(f"no log file at {log}")
    else:
        text = log.read_text(encoding="utf-8", errors="replace")
        if ESC in text:
            line = next(line for line in text.splitlines() if ESC in line)
            failures.append(f"the log file holds an escape sequence: {line!r}")
        for typed, _ in COMMANDS:
            if f"> {typed}" not in text:
                failures.append(f"the log file does not record the command '{typed}'")
        if "Ready in" not in text:
            failures.append("the log file does not say 'Ready in'")

    print("--- console output (escape sequences removed) ---")
    print(process.plain()[-6000:])
    if failures:
        for failure in failures:
            print(f"::error::{args.mode} on {sys.platform}: {failure}")
        if log.is_file():
            print("--- log file ---")
            print(log.read_text(encoding="utf-8", errors="replace")[-6000:])
    if not args.keep:
        shutil.rmtree(directory, ignore_errors=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
