package com.aipro.upgrade.client;

import com.aipro.upgrade.client.core.VersionInfo;

/**
 * 升级交互回调接口（F-C-09）
 * <p>
 * 宿主可实现本接口，把 SDK 默认 Swing 弹窗替换为自定义交互
 * （JavaFX / Web UI / 命令行等）。
 * <p>
 * 实现类需保证：
 * <ul>
 *   <li>{@link #onAsk} 在调用线程同步返回，不能异步</li>
 *   <li>{@link #onProgress} 在下载线程调用，UI 更新需自行切回 EDT</li>
 *   <li>{@link #onResult} 在主流程末尾调用</li>
 * </ul>
 */
public interface UpgradePrompt {

    /**
     * 询问用户是否升级（仅 ASK 策略调用）。
     *
     * @param versionInfo    服务器版本信息
     * @param currentVersion 当前版本
     * @param skipVersion    已跳过的版本（用户先前选过跳过本次）
     * @return true 同意升级；false 拒绝（本次启动不再询问）
     */
    boolean onAsk(VersionInfo versionInfo, String currentVersion, String skipVersion);

    /**
     * 下载进度回调（FORCE 与 ASK 都会调用）。
     *
     * @param progress 当前已下载字节
     * @param total    预期总字节（0 表示未知）
     * @param fileName 当前下载文件名
     * @param fileIndex 当前文件序号（1 起）
     * @param fileTotal 文件总数
     * @param attempt   第几次重试（1 表示首次）
     */
    void onProgress(long progress, long total, String fileName, int fileIndex, int fileTotal, int attempt);

    /**
     * 升级结果回调。
     *
     * @param success true 升级成功；false 失败
     * @param message 详细信息（失败原因或下一步动作提示）
     */
    void onResult(boolean success, String message);
}
