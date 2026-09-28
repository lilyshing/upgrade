package com.aipro.upgrade.client.impl;

import com.aipro.upgrade.client.UpgradePrompt;
import com.aipro.upgrade.client.core.VersionInfo;

import javax.swing.JOptionPane;
import javax.swing.JDialog;
import javax.swing.JProgressBar;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * 默认 Swing 实现（F-C-09）
 * <p>
 * 询问：JOptionPane.showConfirmDialog，三个按钮：立即更新 / 稍后 / 跳过本次。
 * 进度：模态 JDialog + JProgressBar（FORCE 时阻塞主线程，但下载在后台线程跑）。
 * 结果：成功仅打印一行，失败弹 JOptionPane.showMessageDialog。
 * <p>
 * 无外部依赖，纯 JDK Swing。
 */
public class DefaultSwingPrompt implements UpgradePrompt {

    /** 进度对话框（构建一次复用）。 */
    private volatile JDialog progressDiag;
    private volatile JProgressBar progressBar;
    private volatile JLabel statusLabel;

    @Override
    public boolean onAsk(VersionInfo info, String currentVersion, String skipVersion) {
        // 同版本且已跳过：不再问，直接返回 false
        if (skipVersion != null && !skipVersion.isEmpty()
                && skipVersion.equalsIgnoreCase(info.getVersion())) {
            return false;
        }
        String[] options = {"立即更新", "稍后", "跳过本次"};
        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本 v").append(info.getVersion()).append("\n");
        msg.append("当前版本：v").append(currentVersion).append("\n");
        msg.append("文件大小：").append(humanSize(info.getTotalSize())).append("\n");
        if (info.getReleaseNote() != null && !info.getReleaseNote().isEmpty()) {
            msg.append("更新说明：\n").append(info.getReleaseNote());
        }
        int choice = JOptionPane.showOptionDialog(
                null,
                msg.toString(),
                "发现新版本",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[0]);
        // YES=立即更新（0），NO=稍后（1），CANCEL=跳过本次（2），CLOSED（-1）当稍后处理
        return choice == JOptionPane.YES_OPTION;
    }

    @Override
    public void onProgress(long progress, long total, String fileName, int fileIndex, int fileTotal, int attempt) {
        SwingUtilities.invokeLater(() -> {
            ensureProgressDialog();
            if (total > 0) {
                progressBar.setMaximum((int) Math.min(total, Integer.MAX_VALUE));
                progressBar.setValue((int) Math.min(progress, Integer.MAX_VALUE));
            } else {
                progressBar.setIndeterminate(true);
            }
            statusLabel.setText(String.format("下载 [%d/%d] %s %s",
                    fileIndex, fileTotal, fileName, attempt > 1 ? "(第 " + attempt + " 次重试)" : ""));
            progressDiag.setVisible(true);
        });
    }

    @Override
    public void onResult(boolean success, String message) {
        SwingUtilities.invokeLater(() -> {
            if (progressDiag != null) {
                progressDiag.setVisible(false);
                progressDiag.dispose();
                progressDiag = null;
            }
            if (!success) {
                JOptionPane.showMessageDialog(null, message, "升级失败", JOptionPane.ERROR_MESSAGE);
            } else {
                // 成功时静默，宿主自行决定后续动作
                System.out.println("[UpgradeClient] 升级成功: " + message);
            }
        });
    }

    /** 懒构建进度对话框。 */
    private void ensureProgressDialog() {
        if (progressDiag != null && progressDiag.isDisplayable()) {
            return;
        }
        progressDiag = new JDialog((java.awt.Frame) null, "正在更新", true);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        statusLabel = new JLabel("准备中...");
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(360, 22));
        panel.add(statusLabel, BorderLayout.NORTH);
        panel.add(progressBar, BorderLayout.CENTER);
        progressDiag.setContentPane(panel);
        progressDiag.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDiag.pack();
        progressDiag.setLocationRelativeTo(null);
    }

    /** 把字节数格式化为人类可读字符串。 */
    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        String[] units = {"KB", "MB", "GB"};
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i]);
    }
}
