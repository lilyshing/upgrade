package com.aipro.upgrade.client.util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.UUID;

/**
 * 客户端 UUID 工具
 * <p>
 * 优先使用 JDK 自带 UUID.randomUUID()；
 * 当需要确定性标识时（如 IP+MAC），使用 {@link #ipMacBased()} 生成基于硬件的稳定 UUID。
 * <p>
 * 设计意图：upgrade.properties 中 upgrade.client.id 缺失时由本工具生成，
 * 主选 randomUUID，避免硬件信息泄露；IP+MAC 方案仅在宿主要求稳定标识时使用。
 */
public final class UuidUtil {

    private UuidUtil() {
    }

    /** 生成随机 UUID 字符串（带横线，36 字符）。 */
    public static String random() {
        return UUID.randomUUID().toString();
    }

    /**
     * 生成基于本机 IP + MAC 的稳定 UUID。
     * <p>
     * 找不到 MAC（如纯回环）时退化为基于 host name 的 UUID，保证同机稳定。
     */
    public static String ipMacBased() {
        try {
            // 遍历网络接口，找第一个非回环、有 MAC 的网卡
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                    continue;
                }
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length == 0) {
                    continue;
                }
                InetAddress addr = getFirstNonLoopbackAddress(ni);
                String ipPart = addr == null ? "0.0.0.0" : addr.getHostAddress();
                String macPart = bytesToHex(mac);
                // 用 host name + ip + mac 拼接做稳定来源，再 hash 成 UUID 形态
                String src = ipPart + "|" + macPart;
                return stableUuidFromString(src);
            }
        } catch (SocketException e) {
            // 进入降级路径
        }
        return degradedHostUuid();
    }

    /** 从字符串生成稳定的 UUID（与 JDK UUID nameUUIDFromBytes 等价）。 */
    private static String stableUuidFromString(String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(bytes).toString();
    }

    /** 退化路径：基于 host name 生成 UUID，保证同机稳定。 */
    private static String degradedHostUuid() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return stableUuidFromString("host|" + host);
    }

    /** 取网卡上第一个非回环 IPv4 地址。 */
    private static InetAddress getFirstNonLoopbackAddress(NetworkInterface ni) throws SocketException {
        Enumeration<InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
            InetAddress a = addrs.nextElement();
            if (!a.isLoopbackAddress()) {
                return a;
            }
        }
        return null;
    }

    /** 字节数组转十六进制字符串（小写，无分隔）。 */
    private static String bytesToHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[i * 2] = hex[b >>> 4];
            out[i * 2 + 1] = hex[b & 0x0F];
        }
        return new String(out);
    }
}
