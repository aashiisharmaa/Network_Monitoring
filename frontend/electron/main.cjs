const { app, BrowserWindow, shell, dialog, ipcMain } = require("electron");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const net = require("node:net");

const isDev = !app.isPackaged;
let backendProcess = null;
let backendRuntimeRoot = null;

const MYSQL_HOST = "127.0.0.1";
const MYSQL_PORT = 3306;
const BACKEND_PORT = 3000;
const APP_DB_NAME = "kpi_tool_nm";
const APP_DB_USER = "nm_app_user";
const APP_DB_PASSWORD = "NmApp1234";

function getBackendEnv() {
  return {
    ...process.env,
    ELECTRON_RUN_AS_NODE: "1",
    PORT: String(BACKEND_PORT),
    JWT_SECRET: process.env.JWT_SECRET || "local-desktop-jwt-secret-change-me",
    CORS_ORIGINS:
      process.env.CORS_ORIGINS ||
      "http://localhost:5173,http://127.0.0.1:5173,http://localhost:4173,http://127.0.0.1:4173,file://",
    DATABASE_URL:
      process.env.DATABASE_URL ||
      `mysql://${APP_DB_USER}:${APP_DB_PASSWORD}@127.0.0.1:3306/${APP_DB_NAME}`,
  };
}

function isPortReachable(host, port, timeoutMs = 1200) {
  return new Promise((resolve) => {
    const socket = new net.Socket();

    const onDone = (result) => {
      socket.removeAllListeners();
      socket.destroy();
      resolve(result);
    };

    socket.setTimeout(timeoutMs);
    socket.once("connect", () => onDone(true));
    socket.once("timeout", () => onDone(false));
    socket.once("error", () => onDone(false));
    socket.connect(port, host);
  });
}

async function waitForPort(host, port, retries = 20, delayMs = 1000) {
  for (let i = 0; i < retries; i += 1) {
    // eslint-disable-next-line no-await-in-loop
    const up = await isPortReachable(host, port, 1000);
    if (up) return true;
    // eslint-disable-next-line no-await-in-loop
    await new Promise((resolve) => setTimeout(resolve, delayMs));
  }
  return false;
}

function runProcess(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, options);
    child.once("error", reject);
    child.once("exit", (code) => resolve(Number(code ?? 1)));
  });
}

function runProcessCapture(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { ...options, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";

    child.stdout?.on("data", (chunk) => {
      stdout += String(chunk);
    });
    child.stderr?.on("data", (chunk) => {
      stderr += String(chunk);
    });

    child.once("error", reject);
    child.once("exit", (code) => {
      resolve({
        code: Number(code ?? 1),
        stdout,
        stderr,
      });
    });
  });
}

function escapeForSingleQuotedPowerShell(value) {
  return String(value || "").replace(/'/g, "''");
}

async function promptForMySqlRootPassword() {
  const parent = BrowserWindow.getFocusedWindow() || null;
  const promptWindow = new BrowserWindow({
    width: 420,
    height: 230,
    title: "MySQL Root Password",
    resizable: false,
    minimizable: false,
    maximizable: false,
    parent: parent || undefined,
    modal: Boolean(parent),
    autoHideMenuBar: true,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
    },
  });

  const html = `<!doctype html>
  <html>
    <head>
      <meta charset="utf-8" />
      <title>MySQL Root Password</title>
      <style>
        body { font-family: Segoe UI, Arial, sans-serif; margin: 18px; color: #1f2937; }
        .hint { color: #4b5563; font-size: 13px; margin-bottom: 12px; line-height: 1.4; }
        input { width: 100%; padding: 10px; font-size: 14px; border: 1px solid #d1d5db; border-radius: 6px; }
        .actions { margin-top: 14px; display: flex; gap: 8px; justify-content: flex-end; }
        button { padding: 8px 12px; border-radius: 6px; border: 1px solid #d1d5db; background: #fff; cursor: pointer; }
        button.primary { background: #2563eb; border-color: #2563eb; color: white; }
      </style>
    </head>
    <body>
      <div class="hint">
        Enter MySQL <b>root</b> password to complete local database setup.
      </div>
      <input id="pwd" type="password" placeholder="Root password" autofocus />
      <div class="actions">
        <button id="cancel">Cancel</button>
        <button id="submit" class="primary">Continue</button>
      </div>
      <script>
        const { ipcRenderer } = require("electron");
        const input = document.getElementById("pwd");
        document.getElementById("cancel").onclick = () => ipcRenderer.send("mysql-root-password-cancel");
        document.getElementById("submit").onclick = () => ipcRenderer.send("mysql-root-password-submit", input.value || "");
        input.addEventListener("keydown", (e) => {
          if (e.key === "Enter") ipcRenderer.send("mysql-root-password-submit", input.value || "");
          if (e.key === "Escape") ipcRenderer.send("mysql-root-password-cancel");
        });
      </script>
    </body>
  </html>`;

  await promptWindow.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);

  return new Promise((resolve) => {
    let settled = false;
    const onSubmit = (_event, value) => done(String(value || ""));
    const onCancel = () => done(null);
    const done = (value) => {
      if (settled) return;
      settled = true;
      ipcMain.removeListener("mysql-root-password-submit", onSubmit);
      ipcMain.removeListener("mysql-root-password-cancel", onCancel);
      try {
        promptWindow.close();
      } catch {
        // ignore
      }
      resolve(value);
    };

    ipcMain.once("mysql-root-password-submit", onSubmit);
    ipcMain.once("mysql-root-password-cancel", onCancel);
    promptWindow.on("closed", () => done(null));
  });
}

function getBackendRuntimeRoot() {
  if (!backendRuntimeRoot) {
    throw new Error("Backend runtime root is not initialized.");
  }
  return backendRuntimeRoot;
}

async function ensurePackagedBackendRuntime() {
  if (isDev) {
    backendRuntimeRoot = path.resolve(__dirname, "..", "..", "backend");
    return backendRuntimeRoot;
  }

  const zipPath = path.join(process.resourcesPath, "backend-runtime.zip");
  const baseRuntimeDir =
    process.env.LOCALAPPDATA || path.join(app.getPath("home"), "AppData", "Local");
  const runtimeParent = path.join(baseRuntimeDir, "NMRuntime");
  const versionTag = String(app.getVersion() || "0.0.0").replace(/[^0-9A-Za-z._-]/g, "_");
  const targetRoot = path.join(runtimeParent, `backend-${versionTag}`);
  const tsxCliExpected = path.join(targetRoot, "node_modules", "tsx", "dist", "cli.mjs");
  const serverExpected = path.join(targetRoot, "src", "server.ts");

  if (fs.existsSync(tsxCliExpected) && fs.existsSync(serverExpected)) {
    backendRuntimeRoot = targetRoot;
    return targetRoot;
  }

  if (!fs.existsSync(zipPath)) {
    throw new Error(`Packaged backend runtime zip not found at ${zipPath}`);
  }

  fs.mkdirSync(runtimeParent, { recursive: true });
  const extractRoot = path.join(runtimeParent, `backend-${versionTag}-${Date.now()}`);
  fs.mkdirSync(extractRoot, { recursive: true });

  const expandCommand = `Expand-Archive -LiteralPath "${zipPath}" -DestinationPath "${extractRoot}" -Force`;
  const exitCode = await runProcess(
    "powershell",
    ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", expandCommand],
    {
      windowsHide: true,
      stdio: "ignore",
    },
  );

  if (exitCode !== 0) {
    throw new Error("Failed to extract packaged backend runtime.");
  }

  const extractedTsx = path.join(extractRoot, "node_modules", "tsx", "dist", "cli.mjs");
  const extractedServer = path.join(extractRoot, "src", "server.ts");
  if (!fs.existsSync(extractedTsx) || !fs.existsSync(extractedServer)) {
    throw new Error(
      `Backend runtime extraction completed but required files are missing.
Expected:
${extractedTsx}
${extractedServer}`,
    );
  }

  // Promote extracted runtime to stable version path without deleting locked files.
  if (!fs.existsSync(targetRoot)) {
    fs.renameSync(extractRoot, targetRoot);
    backendRuntimeRoot = targetRoot;
    return targetRoot;
  }

  // If stable path already exists (possibly in use), keep this extracted folder for current run.
  backendRuntimeRoot = extractRoot;
  return extractRoot;
}

async function runPrismaDbPush() {
  const backendRoot = getBackendRuntimeRoot();
  const prismaCliCandidates = [
    path.join(backendRoot, "node_modules", "prisma", "build", "index.js"),
    path.join(backendRoot, "node_modules", ".bin", "prisma.cmd"),
    path.join(backendRoot, "node_modules", ".bin", "prisma"),
  ];
  const prismaCli = prismaCliCandidates.find((candidate) => fs.existsSync(candidate));

  if (!prismaCli) {
    const expectedPath = path.join(
      backendRoot,
      "node_modules",
      "prisma",
      "build",
      "index.js",
    );
    throw new Error(
      `Prisma CLI not found in packaged backend runtime. Expected path: ${expectedPath}`,
    );
  }

  const isNodeScript = prismaCli.endsWith(".js");
  const command = isNodeScript ? process.execPath : prismaCli;
  const args = isNodeScript ? [prismaCli, "db", "push", "--skip-generate"] : ["db", "push", "--skip-generate"];

  const result = await runProcessCapture(command, args, {
    cwd: backendRoot,
    windowsHide: true,
    env: getBackendEnv(),
    shell: !isNodeScript,
  });

  if (result.code !== 0) {
    const details = `${result.stderr || result.stdout || ""}`.trim();
    throw new Error(
      `Failed to initialize database schema (prisma db push).\n${details || "No output from Prisma."}`,
    );
  }
}

function spawnBackend() {
  if (backendProcess) return backendProcess;

  const backendRoot = getBackendRuntimeRoot();
  const tsxCli = path.join(backendRoot, "node_modules", "tsx", "dist", "cli.mjs");
  const serverEntry = path.join(backendRoot, "src", "server.ts");

  if (!fs.existsSync(tsxCli) || !fs.existsSync(serverEntry)) {
    throw new Error(
      `Backend runtime files not found. Expected:\n${tsxCli}\n${serverEntry}`,
    );
  }

  const child = spawn(process.execPath, [tsxCli, serverEntry], {
    cwd: backendRoot,
    windowsHide: true,
    env: getBackendEnv(),
    stdio: isDev ? "inherit" : "ignore",
  });

  backendProcess = child;
  child.on("exit", () => {
    backendProcess = null;
  });

  return child;
}

function stopBackend() {
  if (!backendProcess || backendProcess.killed) return;
  try {
    backendProcess.kill();
  } catch {
    // ignore shutdown errors
  }
}

function getMySqlSetupScriptPath() {
  if (isDev) {
    return path.resolve(__dirname, "windows", "setup-mysql-service.ps1");
  }
  return path.join(process.resourcesPath, "windows", "setup-mysql-service.ps1");
}

async function runMySqlSetupElevated(setupScript, rootPassword = "") {
  const escapedScriptPath = setupScript.replace(/"/g, '\\"');
  const tempDir = app.getPath("temp");
  const logPath = path.join(tempDir, `nm-mysql-setup-${Date.now()}.log`);
  const escapedLogPath = logPath.replace(/"/g, '\\"');
  const rootPasswordArg =
    rootPassword && rootPassword.length
      ? ` -RootPassword '${escapeForSingleQuotedPowerShell(rootPassword)}'`
      : "";
  const elevatedInnerCommand = `& "${escapedScriptPath}"${rootPasswordArg} *>&1 | Tee-Object -FilePath "${escapedLogPath}"`;
  const command = `$p = Start-Process -FilePath powershell -Verb RunAs -Wait -PassThru -ArgumentList '-NoProfile -ExecutionPolicy Bypass -Command "${elevatedInnerCommand}"'; exit $p.ExitCode`;

  const exitCode = await runProcess(
    "powershell",
    ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
    {
      windowsHide: false,
      stdio: "ignore",
    },
  );

  let output = "";
  try {
    if (fs.existsSync(logPath)) {
      output = fs.readFileSync(logPath, "utf8");
    }
  } catch {
    output = "";
  }

  return {
    ok: exitCode === 0,
    exitCode,
    output,
    logPath,
  };
}

async function ensureMySqlReady() {
  const mysqlUp = await waitForPort(MYSQL_HOST, MYSQL_PORT, 2, 600);
  if (mysqlUp) return true;

  const setupScript = getMySqlSetupScriptPath();
  const message = [
    "MySQL service is not reachable on 127.0.0.1:3306.",
    "",
    "Click 'Run Automatic Setup' to run setup with elevation (UAC prompt).",
    "If that fails, use the manual script path:",
    `${setupScript}`,
  ].join("\n");

  const { response } = await dialog.showMessageBox({
    type: "warning",
    title: "MySQL Setup Required",
    message: "Local MySQL service is required before starting the app.",
    detail: message,
    buttons: ["Run Automatic Setup", "Open Setup Script Folder", "Close"],
    defaultId: 0,
    cancelId: 2,
  });

  if (response === 1) {
    shell.showItemInFolder(setupScript);
    return false;
  }

  if (response === 2) {
    return false;
  }

  await dialog.showMessageBox({
    type: "info",
    title: "MySQL Setup",
    message: "Starting elevated MySQL setup...",
    detail: "Please approve the UAC prompt to continue.",
  });

  let setupResult = await runMySqlSetupElevated(setupScript);
  if (!setupResult.ok) {
    const providedRootPassword = await promptForMySqlRootPassword();
    if (providedRootPassword === null) {
      return false;
    }
    setupResult = await runMySqlSetupElevated(setupScript, providedRootPassword);
  }

  if (!setupResult.ok) {
    await dialog.showMessageBox({
      type: "error",
      title: "MySQL Setup Failed",
      message: "Automatic setup failed.",
      detail: `Run this script manually as Administrator:\n${setupScript}

Exit code: ${setupResult.exitCode}
Log file: ${setupResult.logPath}

Setup output:
${setupResult.output || "(no setup output captured)"}`,
    });
    return false;
  }

  const readyAfterSetup = await waitForPort(MYSQL_HOST, MYSQL_PORT, 20, 1000);
  if (readyAfterSetup) return true;

  await dialog.showMessageBox({
    type: "error",
    title: "MySQL Setup Incomplete",
    message: "MySQL is still not reachable on 127.0.0.1:3306.",
    detail: `Run this script manually as Administrator and relaunch:\n${setupScript}`,
  });
  return false;
}

async function initializeDesktopRuntime() {
  await ensurePackagedBackendRuntime();

  const mysqlReady = await ensureMySqlReady();
  if (!mysqlReady) return false;

  try {
    await runPrismaDbPush();
  } catch (error) {
    const message = String(error?.message || error);
    const maybeAuthIssue =
      /access denied|authentication|p1000|p1001|can't reach database|unknown database/i.test(
        message,
      );

    if (maybeAuthIssue) {
      const setupScript = getMySqlSetupScriptPath();
      const { response } = await dialog.showMessageBox({
        type: "warning",
        title: "Database Setup Needed",
        message:
          "Database schema initialization failed. The app can run MySQL setup again and retry.",
        detail: message,
        buttons: ["Run Setup And Retry", "Cancel"],
        defaultId: 0,
        cancelId: 1,
      });

      if (response === 0) {
        let setupResult = await runMySqlSetupElevated(setupScript);
        if (!setupResult.ok) {
          const providedRootPassword = await promptForMySqlRootPassword();
          if (providedRootPassword === null) {
            throw new Error(
              `Automatic MySQL setup did not complete.
Please run as Administrator:
${setupScript}

If root has a password, run:
powershell -ExecutionPolicy Bypass -File "${setupScript}" -RootPassword "<root_password>"`,
            );
          }
          setupResult = await runMySqlSetupElevated(
            setupScript,
            providedRootPassword,
          );
          if (!setupResult.ok) {
            throw new Error(
              `Automatic MySQL setup did not complete.
Please run as Administrator:
${setupScript}

If root has a password, run:
powershell -ExecutionPolicy Bypass -File "${setupScript}" -RootPassword "<root_password>"

Exit code: ${setupResult.exitCode}
Log file: ${setupResult.logPath}

Setup output:
${setupResult.output || "(no setup output captured)"}`,
            );
          }
        }
        const readyAfterSetup = await waitForPort(MYSQL_HOST, MYSQL_PORT, 20, 1000);
        if (!readyAfterSetup) {
          throw new Error(
            "MySQL is not reachable after setup retry. Please verify MySQL service is running.",
          );
        }
        await runPrismaDbPush();
      } else {
        throw error;
      }
    } else {
      throw error;
    }
  }

  spawnBackend();

  const backendUp = await waitForPort("127.0.0.1", BACKEND_PORT, 20, 1000);
  if (!backendUp) {
    throw new Error("Backend API failed to start on http://127.0.0.1:3000");
  }

  return true;
}

function createWindow() {
  const mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1200,
    minHeight: 760,
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.once("ready-to-show", () => {
    mainWindow.show();
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: "deny" };
  });

  if (isDev) {
    mainWindow.loadURL(process.env.ELECTRON_RENDERER_URL || "http://127.0.0.1:5173");
    mainWindow.webContents.openDevTools({ mode: "detach" });
    return;
  }

  mainWindow.loadFile(path.join(__dirname, "..", "dist", "index.html"));
}

app.whenReady().then(() => {
  initializeDesktopRuntime()
    .then((runtimeReady) => {
      if (!runtimeReady) return;
      createWindow();
    })
    .catch((error) => {
      dialog.showErrorBox("Startup Error", String(error?.message || error));
    });

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      initializeDesktopRuntime()
        .then((runtimeReady) => {
          if (!runtimeReady) return;
          createWindow();
        })
        .catch((error) => {
          dialog.showErrorBox("Startup Error", String(error?.message || error));
        });
    }
  });
});

app.on("window-all-closed", () => {
  stopBackend();
  if (process.platform !== "darwin") {
    app.quit();
  }
});

app.on("before-quit", () => {
  stopBackend();
});
