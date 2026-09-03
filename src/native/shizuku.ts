import { Capacitor, registerPlugin } from '@capacitor/core';

type ShizukuStatus = {
  running: boolean;
  granted: boolean;
};

type ShizukuBridgePlugin = {
  status(): Promise<ShizukuStatus>;
  requestPermission(): Promise<ShizukuStatus>;
};

const ShizukuBridge =
  registerPlugin<ShizukuBridgePlugin>('ShizukuBridge');

export function canUseShizuku() {
  return (
    Capacitor.isNativePlatform() &&
    Capacitor.getPlatform() === 'android'
  );
}

export async function getShizukuStatus() {
  if (!canUseShizuku()) {
    return {
      running: false,
      granted: false
    };
  }

  return ShizukuBridge.status();
}

export async function requestShizukuPermission() {
  if (!canUseShizuku()) {
    throw new Error('Shizuku 仅支持 Android App');
  }

  return ShizukuBridge.requestPermission();
}
