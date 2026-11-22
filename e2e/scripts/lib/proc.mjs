// Processes and ports for the lifecycle script. It only ever stops what it started: a process is stopped by the id and
// start time recorded when this harness started it, never by looking for whatever holds a port.
import { execFileSync, spawn } from "node:child_process";
import fs from "node:fs";
import net from "node:net";

const WINDOWS = process.platform === "win32";

function connects(port, host) {
  return new Promise((resolve) => {
    const socket = net.connect({ port, host });
    socket.setTimeout(1000);
    socket.once("connect", () => {
      socket.destroy();
      resolve(true);
    });
    const no = () => {
      socket.destroy();
      resolve(false);
    };
    socket.once("timeout", no);
    socket.once("error", no);
  });
}

/** True when something on this machine accepts connections on the port (IPv4 or IPv6 loopback). */
export async function portInUse(port) {
  return (await connects(port, "127.0.0.1")) || (await connects(port, "::1"));
}

/** Polls `check` until it is truthy; no fixed sleep decides anything. */
export async function waitFor(what, check, { timeoutMs, everyMs = 500 }) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() > deadline) throw new Error(`Timed out after ${Math.round(timeoutMs / 1000)} s waiting for ${what}`);
    await new Promise((resolve) => setTimeout(resolve, everyMs));
  }
}

/**
 * Windows only: starts a command line (a .cmd wrapper and whatever it launches) of its own, in a hidden console, its
 * output written to a log file. A wrapper's children lose a redirected output when the wrapper itself has no console,
 * which is what a detached spawn gives it, so the command is handed to Start-Process, which gives it a hidden one.
 * The values travel in the environment so that no path needs quoting. cmd reads the line, so it must be a constant of
 * this harness and never text from outside.
 */
function startCommandLine(commandLine, { cwd, env, logFile }) {
  const script =
    "(Start-Process -FilePath $env:ComSpec " +
    "-ArgumentList ('/d /c ' + $env:E2E_SPAWN_LINE + ' > \"%E2E_SPAWN_LOG%\" 2>&1') " +
    "-WorkingDirectory $env:E2E_SPAWN_CWD -WindowStyle Hidden -PassThru).Id";
  const out = execFileSync("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", script], {
    env: { ...env, E2E_SPAWN_LINE: commandLine, E2E_SPAWN_LOG: logFile, E2E_SPAWN_CWD: cwd },
    encoding: "utf8",
    windowsHide: true,
  });
  return Number(out.trim());
}

/**
 * Starts a long-running process of its own, its output written to a log file, and returns its id. On Windows a .cmd or
 * .bat wrapper goes through {@link startCommandLine}; its arguments must then be constants of this harness.
 */
export function startDetached(command, args, { cwd, env, logFile }) {
  let pid;
  if (WINDOWS && /\.(cmd|bat)$/i.test(command)) {
    pid = startCommandLine([command, ...args].join(" "), { cwd, env, logFile });
  } else {
    const log = fs.openSync(logFile, "a");
    const child = spawn(command, args, { cwd, env, detached: true, windowsHide: true, stdio: ["ignore", log, log] });
    child.unref();
    fs.closeSync(log);
    pid = child.pid;
  }
  if (!Number.isInteger(pid) || pid <= 0) throw new Error(`Could not start ${command}`);
  return pid;
}

/** True while some process runs under the id: enough to notice that a process just started has ended, and no more. */
export function alive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error.code === "EPERM";
  }
}

/**
 * When each of these processes started, by id, as the system words it; an id nothing runs under is left out. A process
 * id is reused once its process has gone, so an id alone does not say that a process is the one this harness started:
 * the id together with its start time does.
 */
export function startTimes(pids) {
  const wanted = pids.filter((pid) => Number.isInteger(pid) && pid > 0);
  const found = new Map();
  if (wanted.length === 0) return found;
  let out = "";
  try {
    out = WINDOWS
      ? execFileSync(
          "powershell.exe",
          [
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            `Get-Process -Id ${wanted.join(",")} -ErrorAction SilentlyContinue | ` +
              "ForEach-Object { \"$($_.Id) $($_.StartTime.ToUniversalTime().ToString('o'))\" }",
          ],
          { encoding: "utf8", windowsHide: true, stdio: ["ignore", "pipe", "ignore"] },
        )
      : execFileSync("ps", ["-o", "pid=,lstart=", "-p", wanted.join(",")], {
          encoding: "utf8",
          stdio: ["ignore", "pipe", "ignore"],
        });
  } catch (error) {
    // ps exits non-zero when some of the ids are gone; what it did print still counts.
    out = typeof error.stdout === "string" ? error.stdout : "";
  }
  for (const line of out.split(/\r?\n/)) {
    const match = /^\s*(\d+)\s+(\S.*?)\s*$/.exec(line);
    if (match) found.set(Number(match[1]), match[2]);
  }
  return found;
}

/** A process as the lifecycle records it: its id and when it started. */
export function recordOf(pid) {
  const startedAt = startTimes([pid]).get(pid);
  if (!startedAt) throw new Error(`Process ${pid} ended before it could be recorded`);
  return { pid, startedAt };
}

/** Which of the recorded processes are still running as the same process (same id, same start time). */
export function stillRunning(records) {
  const known = records.filter((record) => record?.pid && record.startedAt);
  const now = startTimes(known.map((record) => record.pid));
  return known.filter((record) => now.get(record.pid) === record.startedAt);
}

/**
 * Stops a process this harness started, with its children, and says what it did: "stopped", "gone" when nothing runs
 * under the id any more, or "not ours" when the id now belongs to another process, which is left alone.
 */
export function stopTree(record) {
  if (!record?.pid) return "gone";
  const startedAt = startTimes([record.pid]).get(record.pid);
  if (!startedAt) return "gone";
  if (startedAt !== record.startedAt) return "not ours";
  try {
    if (WINDOWS) {
      execFileSync("taskkill", ["/PID", String(record.pid), "/T", "/F"], { stdio: "ignore" });
    } else {
      process.kill(-record.pid, "SIGTERM");
    }
  } catch {
    // Gone between the check and the signal: the read-back on the ports decides whether the stop worked.
  }
  return "stopped";
}

/** The process listening on a port of this machine, or null. Recorded at start so that stop targets it by id. */
export function listenerPid(port) {
  try {
    if (WINDOWS) {
      const out = execFileSync("netstat", ["-ano"], { encoding: "utf8" });
      for (const line of out.split(/\r?\n/)) {
        const cells = line.trim().split(/\s+/);
        if (cells[0] === "TCP" && cells[3] === "LISTENING" && cells[1].endsWith(`:${port}`)) return Number(cells[4]);
      }
      return null;
    }
    const out = execFileSync("lsof", ["-ti", `tcp:${port}`, "-sTCP:LISTEN"], { encoding: "utf8" });
    return Number(out.trim().split(/\s+/)[0]) || null;
  } catch {
    return null;
  }
}

/** Runs a command to its end and returns what it printed; a non-zero exit is an error carrying its output. */
export function run(command, args, { cwd } = {}) {
  try {
    return execFileSync(command, args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (error) {
    const detail = [error.stdout, error.stderr].filter(Boolean).join("\n").trim();
    throw new Error(`${command} ${args.slice(0, 6).join(" ")} failed (exit ${error.status}): ${detail}`);
  }
}

export function tail(file, lines = 30) {
  try {
    return fs.readFileSync(file, "utf8").split(/\r?\n/).slice(-lines).join("\n");
  } catch {
    return "(no log)";
  }
}
