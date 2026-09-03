package com.alyssa.polaris;

import android.content.pm.PackageManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import rikka.shizuku.Shizuku;

@CapacitorPlugin(name = "ShizukuBridge")
public class ShizukuBridgePlugin extends Plugin {

    private static final int REQUEST_CODE = 1001;
    private PluginCall pendingPermissionCall;

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

    @Override
    public void load() {
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    @Override
    protected void handleOnDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
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
}
