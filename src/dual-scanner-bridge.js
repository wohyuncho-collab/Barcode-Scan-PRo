import { registerPlugin } from '@capacitor/core';

const DualScanner = registerPlugin('DualScanner');

window.LabelScanDualScanner = {
  isNative: () => Boolean(window.Capacitor?.isNativePlatform?.()),
  start: async () => {
    if (!window.Capacitor?.isNativePlatform?.()) {
      throw new Error('듀얼 스캐너는 Android APK에서만 사용할 수 있습니다.');
    }
    await DualScanner.startScan();
  },
  stop: async () => {
    try { await DualScanner.stopScan(); } catch (_) { /* already closed */ }
  },
  addListener: (eventName, callback) => DualScanner.addListener(eventName, callback),
};
