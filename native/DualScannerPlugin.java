package com.labelscan.pro;

import android.Manifest;
import android.content.Intent;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.lang.ref.WeakReference;

@CapacitorPlugin(
    name = "DualScanner",
    permissions = { @Permission(alias = "camera", strings = { Manifest.permission.CAMERA }) }
)
public class DualScannerPlugin extends Plugin {
    private static volatile DualScannerPlugin instance;
    private static volatile WeakReference<DualScannerActivity> activityRef = new WeakReference<>(null);

    @Override
    public void load() {
        instance = this;
    }

    @PluginMethod
    public void startScan(PluginCall call) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraPermissionResult");
            return;
        }
        launchScanner(call);
    }

    @PermissionCallback
    private void cameraPermissionResult(PluginCall call) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            call.reject("카메라 권한이 필요합니다. 앱 설정에서 카메라 권한을 허용하세요.");
            return;
        }
        launchScanner(call);
    }

    private void launchScanner(PluginCall call) {
        try {
            Intent intent = new Intent(getContext(), DualScannerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            getActivity().runOnUiThread(() -> getActivity().startActivity(intent));
            call.resolve();
        } catch (Exception ex) {
            call.reject("듀얼 스캐너를 시작하지 못했습니다: " + ex.getMessage(), ex);
        }
    }

    @PluginMethod
    public void stopScan(PluginCall call) {
        DualScannerActivity activity = activityRef.get();
        if (activity != null) activity.runOnUiThread(activity::finish);
        call.resolve();
    }

    static void setActiveActivity(DualScannerActivity activity) {
        activityRef = new WeakReference<>(activity);
    }

    static void emitBarcode(String value, String format, String engine) {
        DualScannerPlugin plugin = instance;
        if (plugin == null || value == null || value.trim().isEmpty()) return;
        JSObject data = new JSObject();
        data.put("value", value);
        data.put("format", format == null ? "UNKNOWN" : format);
        data.put("engine", engine == null ? "unknown" : engine);
        plugin.notifyListeners("barcodeDetected", data, true);
    }

    static void emitClosed() {
        DualScannerPlugin plugin = instance;
        if (plugin != null) plugin.notifyListeners("scanClosed", new JSObject(), true);
    }
}
