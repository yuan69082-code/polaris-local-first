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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

@CapacitorPlugin(name = "ShizukuBridge")
public class ShizukuBridgePlugin extends Plugin {

    private static final int REQUEST_CODE = 1001;
    private static final long SHELL_BIND_TIMEOUT_MS = 8000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PluginCall pendingPermissionCall;
    private PluginCall pendingShellConnectCall;
    private Shizuku.UserServiceArgs shellServiceArgs;
    private volatile IShellService shellService;
    private volatile boolean shellBinding = false;

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

        if (pendingShellConnectCall != null) {
            pendingShellConnectCall.reject("Shizuku 服务已断开");
            pendingShellConnectCall = null;
        }
    };

    private final Runnable shellBindTimeout = () -> {
        if (!shellBinding || pendingShellConnectCall == null) {
            return;
        }

        PluginCall call = pendingShellConnectCall;
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

        call.reject("Shizuku Shell 连接超时，请重试");
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

    @Override
    public void load() {
        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderDeadListener(binderDeadListener);

        shellServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(
                        BuildConfig.APPLICATION_ID,
                        ShellUserService.class.getName()
                )
        )
                .daemon(false)
                .processNameSuffix("service")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE);
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
            call.reject("连接 Shizuku Shell 失败：" + message(error));
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
        if (service == null || !service.asBinder().pingBinder()) {
            call.reject("Shizuku Shell 未连接，请先连接");
            return;
        }

        shellExecutor.execute(() -> {
            try {
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

                try {
                    result.put("uid", service.uid());
                } catch (Throwable ignored) {
                    result.put("uid", -1);
                }

                call.resolve(result);
            } catch (Throwable error) {
                shellService = null;
                call.reject("Shell 执行失败：" + message(error));
            }
        });
    }

    private void cancelShellBindTimeout() {
        mainHandler.removeCallbacks(shellBindTimeout);
    }

    private JSObject buildShellStatus() {
        boolean running = Shizuku.pingBinder();
        boolean granted = false;
        boolean connected = false;
        int uid = -1;

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
                }
            } catch (Throwable ignored) {
                connected = false;
                shellService = null;
            }
        }

        if (!connected && running) {
            try {
                uid = Shizuku.getUid();
            } catch (Throwable ignored) {
            }
        }

        JSObject result = new JSObject();
        result.put("running", running);
        result.put("granted", granted);
        result.put("shellConnected", connected);
        result.put("uid", uid);

        return result;
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty()
                ? error.getClass().getSimpleName()
                : value;
    }
}
