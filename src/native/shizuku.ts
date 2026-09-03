import { Capacitor, registerPlugin } from '@capacitor/core';

export type ShizukuStatus = {
  running: boolean;
  granted: boolean;
};

export type ShizukuShellStatus = ShizukuStatus & {
  shellConnected: boolean;
  uid: number;
};

export type ShizukuShellExecResult = {
  exitCode: number;
  stdout: string;
  stderr: string;
  uid: number;
};

type ShizukuBridgePlugin = {
  status(): Promise<ShizukuStatus>;
  requestPermission(): Promise<ShizukuStatus>;
  shellStatus(): Promise<ShizukuShellStatus>;
  connectShell(): Promise<ShizukuShellStatus>;
  execShell(options: { command: string }): Promise<ShizukuShellExecResult>;
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

export async function getShizukuShellStatus() {
  if (!canUseShizuku()) {
    return {
      running: false,
      granted: false,
      shellConnected: false,
      uid: -1
    };
  }

  return ShizukuBridge.shellStatus();
}

export async function connectShizukuShell() {
  if (!canUseShizuku()) {
    throw new Error('Shizuku Shell 仅支持 Android App');
  }

  return ShizukuBridge.connectShell();
}

export async function execShizukuShell(command: string) {
  if (!canUseShizuku()) {
    throw new Error('Shizuku Shell 仅支持 Android App');
  }

  const trimmed = command.trim();
  if (!trimmed) {
    throw new Error('Shell 命令不能为空');
  }

  return ShizukuBridge.execShell({ command: trimmed });
}
