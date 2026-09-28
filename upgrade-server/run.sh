#!/bin/bash
# ============================================================
#  自动升级管理后台 - Linux 管理脚本
#  用法: ./run.sh {start|stop|restart|status} [port] [host]
#  默认: port=8090 host=0.0.0.0
#  环境变量（可选）:
#    JAVA_HOME                指定 JDK 1.8 安装路径
#    UPGRADE_SERVER_BASE_URL  反代场景下覆盖对外分发 URL
#  日志: 同目录 upgrade-server.out
#  PID : 同目录 upgrade-server.pid
# ============================================================

set -e

# 确定 java 命令路径
if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/bin/java" ]; then
        JAVACMD="$JAVA_HOME/bin/java"
    else
        echo "[ERROR] JAVA_HOME 已设置但找不到 \$JAVA_HOME/bin/java"
        echo "        JAVA_HOME=$JAVA_HOME"
        exit 1
    fi
else
    JAVACMD="java"
fi

# 校验 java 可执行
if ! command -v "$JAVACMD" >/dev/null 2>&1; then
    echo "[ERROR] 找不到 java 命令，请安装 JDK 1.8 或设置 JAVA_HOME"
    exit 1
fi

# 定位 upgrade-server.jar
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_PATH="$SCRIPT_DIR/target/upgrade-server.jar"
if [ ! -f "$JAR_PATH" ]; then
    JAR_PATH="$SCRIPT_DIR/upgrade-server.jar"
fi
if [ ! -f "$JAR_PATH" ]; then
    echo "[ERROR] 找不到 upgrade-server.jar"
    echo "        请先在项目根目录执行: mvn -s settings.xml clean package -DskipTests"
    exit 1
fi

PORT="${2:-8090}"
HOST="${3:-0.0.0.0}"
PID_FILE="$SCRIPT_DIR/upgrade-server.pid"
LOG_FILE="$SCRIPT_DIR/upgrade-server.out"

# 判断进程是否存活
is_running() {
    if [ -f "$PID_FILE" ]; then
        local pid
        pid=$(cat "$PID_FILE" 2>/dev/null)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

start() {
    if is_running; then
        echo "[WARN] 服务已在运行，PID=$(cat "$PID_FILE")"
        return 0
    fi
    echo "[INFO] 启动服务..."
    echo "        监听: $HOST:$PORT"
    echo "        Jar : $JAR_PATH"
    echo "        日志: $LOG_FILE"
    nohup "$JAVACMD" -jar "$JAR_PATH" "$PORT" "$HOST" > "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    sleep 2
    if is_running; then
        echo "[INFO] 已启动，PID=$(cat "$PID_FILE")"
        echo "        访问: http://$(echo $HOST | sed 's/0.0.0.0/127.0.0.1/'):$PORT/"
    else
        echo "[ERROR] 启动失败，查看日志: $LOG_FILE"
        tail -n 20 "$LOG_FILE" 2>/dev/null || true
        rm -f "$PID_FILE"
        exit 1
    fi
}

stop() {
    if ! is_running; then
        echo "[WARN] 服务未运行"
        rm -f "$PID_FILE"
        return 0
    fi
    local pid
    pid=$(cat "$PID_FILE")
    echo "[INFO] 停止进程 PID=$pid ..."
    kill "$pid" 2>/dev/null || true
    for i in 1 2 3 4 5 6 7 8 9 10; do
        sleep 1
        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi
    done
    if kill -0 "$pid" 2>/dev/null; then
        echo "[WARN] 优雅停止超时，强制结束"
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
    echo "[INFO] 服务已停止"
}

status() {
    if is_running; then
        echo "[INFO] 服务运行中，PID=$(cat "$PID_FILE")"
        return 0
    else
        echo "[INFO] 服务未运行"
        return 1
    fi
}

case "${1:-}" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        stop
        start
        ;;
    status)
        status
        ;;
    *)
        echo "用法: $0 {start|stop|restart|status} [port] [host]"
        echo "默认: port=8090 host=0.0.0.0"
        echo ""
        echo "示例:"
        echo "  $0 start                 # 默认端口 8090 启动"
        echo "  $0 start 18090 127.0.0.1 # 指定端口和监听地址"
        echo "  $0 stop                  # 停止"
        echo "  $0 restart               # 重启"
        echo "  $0 status                # 查看状态"
        exit 1
        ;;
esac
