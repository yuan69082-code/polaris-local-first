package com.alyssa.polaris;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

@CapacitorPlugin(name = "ShizukuBridge")
public class ShizukuBridgePlugin extends Plugin {

    private static final int REQUEST_CODE = 1001;
    private static final int SHELL_SERVICE_VERSION = 3;
    private static final long SHELL_BIND_TIMEOUT_MS = 8000L;
    private static final long COMMAND_TIMEOUT_SECONDS = 20L;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PluginCall pendingPermissionCall;
    private PluginCall pendingShellConnectCall;
    private Shizuku.UserServiceArgs shellServiceArgs;
    private volatile IShellService shellService;
    private volatile boolean shellBinding = false;
    private volatile boolean processFallbackReady = false;

    private final ExecutorService shellExecutor =
            Executors.newSingleThreadExecutor();

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_CODE || pendingPermissionCall == null) {
                    return;
                }

                JSObject result = new JSObject();
                result.put("running", Shizuku.pingBinder());
                result.put(
                        "granted",
                        grantResult == PackageManager.PERMISSION_GRANTED
                );

                pendingPermissionCall.resolve(result);
                pendingPermissionCall = null;
            };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        cancelShellBindTimeout();
        shellService = null;
        shellBinding = false;
        processFallbackReady = false;

        if (pendingShellConnectCall != null) {
            pendingShellConnectCall.reject("Shizuku 服务已断开");
            pendingShellConnectCall = null;
        }
    };

    private final ServiceConnection shellConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(
                        ComponentName name,
                        IBinder service
                ) {
                    cancelShellBindTimeout();

                    if (service == null || !service.pingBinder()) {
                        shellService = null;
                        shellBinding = false;

                        if (pendingShellConnectCall != null) {
                            pendingShellConnectCall.reject("Shizuku Shell 返回了无效 Binder");
                            pendingShellConnectCall = null;
                        }
                        return;
                    }

                    shellService = IShellService.Stub.asInterface(service);
                    shellBinding = false;
                    processFallbackReady = false;

                    if (pendingShellConnectCall != null) {
                        PluginCall call = pendingShellConnectCall;
                        pendingShellConnectCall = null;
                        call.resolve(buildShellStatus());
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    cancelShellBindTimeout();
                    shellService = null;
                    shellBinding = false;

                    if (pendingShellConnectCall != null) {
                        pendingShellConnectCall.reject("Shizuku Shell 连接断开");
                        pendingShellConnectCall = null;
                    }
                }
            };

    private final Runnable shellBindTimeout = () -> {
        if (!shellBinding || pendingShellConnectCall == null) {
            return;
        }

        final PluginCall call = pendingShellConnectCall;
        pendingShellConnectCall = null;
        shellBinding = false;
        shellService = null;

        try {
            if (Shizuku.pingBinder() && shellServiceArgs != null) {
                Shizuku.unbindUserService(
                        shellServiceArgs,
                        shellConnection,
                        true
                );
            }
        } catch (Throwable ignored) {
        }

        shellExecutor.execute(() -> {
            try {
                ShellExecResult probe = execViaProcessFallback("id");
                if (probe.exitCode != 0) {
                    throw new IllegalStateException(
                            probe.stderr.isEmpty()
                                    ? "兼容 Shell 返回 exit " + probe.exitCode
                                    : probe.stderr
                    );
                }

                processFallbackReady = true;
                call.resolve(buildShellStatus());
            } catch (Throwable error) {
                processFallbackReady = false;
                call.reject(
                        "Shizuku UserService 连接超时；兼容通道也失败："
                                + message(error)
                                + "（Shizuku API "
                                + safeShizukuVersion()
                                + "，UID "
                                + safeShizukuUid()
                                + "）"
                );
            }
        });
    };

    @Override
    public void load() {
        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderDeadListener(binderDeadListener);

        shellServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(
                        getContext().getPackageName(),
                        ShellUserService.class.getName()
                )
        )
                .daemon(false)
                .processNameSuffix("service")
                .debuggable(true)
                .version(SHELL_SERVICE_VERSION);
    }

    @Override
    protected void handleOnDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        cancelShellBindTimeout();

        if (pendingPermissionCall != null) {
            pendingPermissionCall.reject("Polaris 已关闭");
            pendingPermissionCall = null;
        }

        if (pendingShellConnectCall != null) {
            pendingShellConnectCall.reject("Polaris 已关闭");
            pendingShellConnectCall = null;
        }

        try {
            if (Shizuku.pingBinder()
                    && shellServiceArgs != null
                    && (shellBinding || shellService != null)) {
                Shizuku.unbindUserService(
                        shellServiceArgs,
                        shellConnection,
                        true
                );
            }
        } catch (Throwable ignored) {
        }

        shellService = null;
        shellBinding = false;
        processFallbackReady = false;
        shellExecutor.shutdownNow();

        super.handleOnDestroy();
    }

    @PluginMethod
    public void status(PluginCall call) {
        boolean running = Shizuku.pingBinder();
        boolean granted = false;

        if (running) {
            try {
                granted =
                        Shizuku.checkSelfPermission()
                                == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {
            }
        }

        JSObject result = new JSObject();
        result.put("running", running);
        result.put("granted", granted);

        call.resolve(result);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (!Shizuku.pingBinder()) {
            call.reject("Shizuku 未运行");
            return;
        }

        if (Shizuku.checkSelfPermission()
                == PackageManager.PERMISSION_GRANTED) {

            JSObject result = new JSObject();
            result.put("running", true);
            result.put("granted", true);

            call.resolve(result);
            return;
        }

        if (pendingPermissionCall != null) {
            call.reject("已有 Shizuku 授权请求");
            return;
        }

        pendingPermissionCall = call;
        Shizuku.requestPermission(REQUEST_CODE);
    }

    @PluginMethod
    public void shellStatus(PluginCall call) {
        call.resolve(buildShellStatus());
    }

    @PluginMethod
    public void connectShell(PluginCall call) {
        if (!Shizuku.pingBinder()) {
            call.reject("Shizuku 未运行");
            return;
        }

        if (Shizuku.getVersion() < 10) {
            call.reject("当前 Shizuku 版本不支持 UserService");
            return;
        }

        if (Shizuku.checkSelfPermission()
                != PackageManager.PERMISSION_GRANTED) {
            call.reject("Polaris 尚未获得 Shizuku 权限");
            return;
        }

        if (shellService != null
                && shellService.asBinder().pingBinder()) {
            call.resolve(buildShellStatus());
            return;
        }

        if (processFallbackReady) {
            call.resolve(buildShellStatus());
            return;
        }

        if (shellBinding || pendingShellConnectCall != null) {
            call.reject("Shizuku Shell 正在连接，请稍候");
            return;
        }

        if (shellServiceArgs == null) {
            call.reject("Shizuku Shell 尚未初始化");
            return;
        }

        shellBinding = true;
        pendingShellConnectCall = call;
        mainHandler.postDelayed(shellBindTimeout, SHELL_BIND_TIMEOUT_MS);

        try {
            Shizuku.bindUserService(
                    shellServiceArgs,
                    shellConnection
            );
        } catch (Throwable error) {
            cancelShellBindTimeout();
            shellBinding = false;
            pendingShellConnectCall = null;

            shellExecutor.execute(() -> {
                try {
                    ShellExecResult probe = execViaProcessFallback("id");
                    if (probe.exitCode != 0) {
                        throw new IllegalStateException(
                                probe.stderr.isEmpty()
                                        ? "兼容 Shell 返回 exit " + probe.exitCode
                                        : probe.stderr
                        );
                    }

                    processFallbackReady = true;
                    call.resolve(buildShellStatus());
                } catch (Throwable fallbackError) {
                    processFallbackReady = false;
                    call.reject(
                            "连接 Shizuku Shell 失败："
                                    + message(error)
                                    + "；兼容通道失败："
                                    + message(fallbackError)
                    );
                }
            });
        }
    }

    @PluginMethod
    public void execShell(PluginCall call) {
        final String command = call.getString("command");

        if (command == null || command.trim().isEmpty()) {
            call.reject("Shell 命令不能为空");
            return;
        }

        final IShellService service = shellService;
        final boolean useUserService =
                service != null && service.asBinder().pingBinder();

        if (!useUserService && !processFallbackReady) {
            call.reject("Shizuku Shell 未连接，请先连接");
            return;
        }

        shellExecutor.execute(() -> {
            try {
                if (useUserService) {
                    String[] raw = service.exec(command);
                    int exitCode = 1;
                    String stdout = "";
                    String stderr = "";

                    if (raw != null) {
                        if (raw.length > 0) {
                            try {
                                exitCode = Integer.parseInt(raw[0]);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        if (raw.length > 1 && raw[1] != null) {
                            stdout = raw[1];
                        }
                        if (raw.length > 2 && raw[2] != null) {
                            stderr = raw[2];
                        }
                    }

                    JSObject result = new JSObject();
                    result.put("exitCode", exitCode);
                    result.put("stdout", stdout);
                    result.put("stderr", stderr);
                    result.put("mode", "userService");

                    try {
                        result.put("uid", service.uid());
                    } catch (Throwable ignored) {
                        result.put("uid", -1);
                    }

                    call.resolve(result);
                    return;
                }

                ShellExecResult fallback = execViaProcessFallback(command);
                JSObject result = new JSObject();
                result.put("exitCode", fallback.exitCode);
                result.put("stdout", fallback.stdout);
                result.put("stderr", fallback.stderr);
                result.put("uid", safeShizukuUid());
                result.put("mode", "processFallback");
                call.resolve(result);
            } catch (Throwable error) {
                if (useUserService) {
                    shellService = null;
                } else {
                    processFallbackReady = false;
                }
                call.reject("Shell 执行失败：" + message(error));
            }
        });
    }

    private ShellExecResult execViaProcessFallback(String command)
            throws Exception {

        Method method = Shizuku.class.getDeclaredMethod(
                "newProcess",
                String[].class,
                String[].class,
                String.class
        );
        method.setAccessible(true);

        Process process = (Process) method.invoke(
                null,
                new Object[]{
                        new String[]{"/system/bin/sh", "-c", command},
                        null,
                        null
                }
        );

        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<String> stdoutFuture =
                readers.submit(() -> readLimited(process.getInputStream()));
        Future<String> stderrFuture =
                readers.submit(() -> readLimited(process.getErrorStream()));

        try {
            boolean finished =
                    process.waitFor(
                            COMMAND_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    );

            if (!finished) {
                process.destroy();
                return new ShellExecResult(
                        124,
                        readFuture(stdoutFuture),
                        "命令执行超时\n" + readFuture(stderrFuture)
                );
            }

            return new ShellExecResult(
                    process.exitValue(),
                    readFuture(stdoutFuture),
                    readFuture(stderrFuture)
            );
        } finally {
            readers.shutdownNow();
            try {
                process.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String readFuture(Future<String> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Throwable error) {
            return "";
        }
    }

    private static String readLimited(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        boolean truncated = false;

        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }

            int remaining = MAX_OUTPUT_BYTES - total;
            if (remaining <= 0) {
                truncated = true;
                break;
            }

            int writeCount = Math.min(read, remaining);
            output.write(buffer, 0, writeCount);
            total += writeCount;

            if (writeCount < read || total >= MAX_OUTPUT_BYTES) {
                truncated = true;
                break;
            }
        }

        String text = output.toString(StandardCharsets.UTF_8.name());
        if (truncated) {
            text += "\n[输出已截断]";
        }
        return text;
    }

    private void cancelShellBindTimeout() {
        mainHandler.removeCallbacks(shellBindTimeout);
    }

    private JSObject buildShellStatus() {
        boolean running = Shizuku.pingBinder();
        boolean granted = false;
        boolean connected = false;
        int uid = -1;
        String mode = "none";

        if (running) {
            try {
                granted =
                        Shizuku.checkSelfPermission()
                                == PackageManager.PERMISSION_GRANTED;
            } catch (Throwable ignored) {
            }
        }

        IShellService service = shellService;
        if (service != null) {
            try {
                connected = service.asBinder().pingBinder();
                if (connected) {
                    uid = service.uid();
                    mode = "userService";
                }
            } catch (Throwable ignored) {
                connected = false;
                shellService = null;
            }
        }

        if (!connected && processFallbackReady && running && granted) {
            connected = true;
            uid = safeShizukuUid();
            mode = "processFallback";
        }

        if (!connected && running) {
            uid = safeShizukuUid();
        }

        JSObject result = new JSObject();
        result.put("running", running);
        result.put("granted", granted);
        result.put("shellConnected", connected);
        result.put("uid", uid);
        result.put("mode", mode);
        result.put("shizukuVersion", safeShizukuVersion());

        return result;
    }

    private static int safeShizukuUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int safeShizukuVersion() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        String value = current.getMessage();
        return value == null || value.isEmpty()
                ? current.getClass().getSimpleName()
                : value;
    }

    private static final class ShellExecResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        ShellExecResult(
                int exitCode,
                String stdout,
                String stderr
        ) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }
    }
}
