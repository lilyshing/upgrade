package com.aipro.upgrade.client;

/**
 * 升级策略枚举（F-C-01）
 * <p>
 * 三种策略：
 * <ul>
 *   <li>FORCE：强制升级，进度窗口无取消按钮，主线程阻塞</li>
 *   <li>ASK：默认 Swing JOptionPane 询问用户是否升级</li>
 *   <li>NONE：不升级</li>
 * </ul>
 * 宿主可在 upgrade.properties 中通过 upgrade.strategy=FORCE/ASK/NONE 配置，
 * 也支持热切换（运行时通过 UpgradeClient.setStrategy 修改）。
 */
public enum UpgradeStrategy {

    /** 强制升级。 */
    FORCE,

    /** 询问用户。 */
    ASK,

    /** 不升级。 */
    NONE;

    /** 不区分大小写解析策略名；非法值返回 ASK（默认）。 */
    public static UpgradeStrategy fromString(String s) {
        if (s == null || s.trim().isEmpty()) {
            return ASK;
        }
        String t = s.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return UpgradeStrategy.valueOf(t);
        } catch (IllegalArgumentException e) {
            return ASK;
        }
    }
}
