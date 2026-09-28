package com.aipro.upgrade.client.impl;

import com.aipro.upgrade.client.UpgradePrompt;
import com.aipro.upgrade.client.core.VersionInfo;

/**
 * 空操作实现（NONE 策略用）
 * <p>
 * 所有回调均为空实现：不弹窗、不打日志、不更新 UI。
 * NONE 策略本身不会进入升级流程，本类主要作为安全哨兵。
 */
public class NoOpPrompt implements UpgradePrompt {

    @Override
    public boolean onAsk(VersionInfo versionInfo, String currentVersion, String skipVersion) {
        return false;
    }

    @Override
    public void onProgress(long progress, long total, String fileName, int fileIndex, int fileTotal, int attempt) {
        // 无操作
    }

    @Override
    public void onResult(boolean success, String message) {
        // 无操作
    }
}
