# 自动升级更新模块

> 版本：v1.0 · 日期：2026-09-28
> 依据：`PRD.md` v1.0
> 适用：Java 应用群（如 `recorder` 屏幕录像工具）的统一自动升级与版本分发

---

## 1. 项目概述

为 Java 应用群提供统一自动升级能力，避免重复造轮子。包含两部分：

- **客户端 SDK（`upgrade-client`）**：以 jar 包被宿主引用，零侵入接入升级流程；
- **服务器端版本管理工具（`upgrade-server`）**：独立部署的 Web 服务（含管理后台 + SQLite3），负责版本发布、灰度推送、工具分发与更新记录。

支持 **Windows / Win7 离线** 场景，单 jar 部署，无外部组件依赖；通过 **灰度数量控制 + 版本回滚** 避免更新事故；对外发布时提供 **引导下载器（Bootstrap Installer）**，用户首次通过一个 URL 拿到小体积下载器，本地运行后向服务器拉取本体并启动。

### 1.1 名词速查

| 名词 | 含义 |
|---|---|
| 主模块 / 宿主程序 | 引用客户端 SDK 的业务程序（如 recorder.jar） |
| 客户端 SDK | 以 Maven 依赖形式被宿主引用的升级 jar 包 |
| 服务器端版本管理工具 | 独立部署的 Web 服务（管理后台 + SQLite3） |
| 工具（Tool） | 可对外分发的产品，对应 `toolId`（如 `recorder`） |
| 工具特征值（toolId） | URL 中承载的工具唯一标识 |
| 引导下载器 | 对外发布的小体积启动器（Win `.bat` / Linux `.sh`），不依赖 Java |
| 灰度（按工具） | 灰度配置按 `tool_id` 独立，每工具有自己的阈值/计数/白名单 |
| 角色 | `ADMIN` 管理员 / `USER` 普通用户；普通用户只管自己创建的工具 |

### 1.2 架构

```
┌──────────────────────┐        HTTP         ┌──────────────────────────────────┐
│  主模块 / 宿主程序   │  ───────────────►  │  服务器端版本管理工具             │
│  （recorder.jar 等） │                     │  ┌────────────┐  ┌────────────┐    │
│  ┌────────────────┐  │  ◄──文件下载────  │  │ Web 后台   │  │ SQLite3 DB │    │
│  │ 客户端升级 SDK │  │  ───更新上报────►  │  │ (管理页面) │  │ (.db 文件) │    │
│  └────────────────┘  │                    │  └────────────┘  └────────────┘    │
│  本地: version.json  │                                │ ▲
│  update_log.txt      │                                │ │ 浏览器访问
│  file_manifest.json  │                    ┌──────────────────────────────────┐
│  backup/ 上一版本    │                    │  运维人员（Web 浏览器）           │
└──────────────────────┘                    └──────────────────────────────────┘
```

首次分发链路（下载器）：
```
用户访问 http://host:8090/d/{toolId}?platform=win
  → 服务器返回 install.bat / install.sh（内嵌 toolId + serverUrl）
  → 本地运行下载器 → GET /api/bootstrap/{toolId}?platform=win&format=kv
  → 服务器返回 KV 清单（文件列表 + SHA-256 + 启动命令）
  → 下载器按清单 GET /api/file/tool/{toolId}/{version}/{filename} 逐文件下载
  → SHA-256 校验 → 落地 → 启动本体（内嵌升级 SDK）→ 后续走热升级闭环
```

---

## 2. 模块结构

```
upgrade/
├── pom.xml                  # 根 pom，多模块聚合 + dependencyManagement
├── settings.xml             # Maven 设置：本地仓库 D:\devbase\repository + 阿里云镜像
├── PRD.md                   # 产品需求文档（开发唯一依据）
├── README.md                # 本文档
├── DEPLOY.md                # 部署指南（含 Win7 离线）
├── e2e-test.ps1             # 端到端回归测试脚本
├── upgrade-client/          # 客户端 SDK 模块
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/aipro/upgrade/client/
│       │   ├── UpgradeClient.java        # 门面单例，checkAndUpgrade() 主入口
│       │   ├── UpgradeConfig.java         # upgrade.properties 配置加载
│       │   ├── UpgradeStrategy.java       # FORCE/ASK/NONE 策略枚举
│       │   ├── UpgradePrompt.java         # 交互接口
│       │   ├── impl/DefaultSwingPrompt.java
│       │   ├── impl/NoOpPrompt.java
│       │   ├── core/VersionInfo.java
│       │   ├── core/FileEntry.java
│       │   ├── core/VersionComparator.java    # SemVer 比较
│       │   ├── core/JsonParser.java           # 极简 JSON 解析
│       │   ├── core/Sha256Util.java
│       │   ├── core/HttpClient.java           # HttpURLConnection + Range 续传
│       │   ├── core/DownloadManager.java      # 断点续传+指数退避+SHA-256 校验
│       │   ├── core/FileReplacer.java         # backup+原子替换+manifest
│       │   ├── core/UpdateLogger.java
│       │   ├── core/PolicyChecker.java
│       │   ├── core/ReportClient.java
│       │   ├── core/RestartLauncher.java       # restart.bat / restart.sh
│       │   └── util/{IoUtil,UuidUtil}.java
│       └── test/java/.../               # 35 个单测
└── upgrade-server/          # 服务器端 fat jar 模块
    ├── pom.xml              # maven-shade-plugin 打 fat jar
    └── src/
        ├── main/
        │   ├── java/com/aipro/upgrade/server/
        │   │   ├── UpgradeServerMain.java     # 启动入口
        │   │   ├── db/DatabaseManager.java    # SQLite 单例
        │   │   ├── db/SchemaInitializer.java   # 9 张表 DDL
        │   │   ├── model/Models.java           # DTO
        │   │   ├── auth/PasswordHasher.java    # BCrypt
        │   │   ├── auth/SessionManager.java    # 会话表 TTL 12h
        │   │   ├── auth/AuthContext.java
        │   │   ├── service/UserService.java
        │   │   ├── service/ToolService.java
        │   │   ├── service/VersionService.java
        │   │   ├── service/PolicyService.java
        │   │   ├── service/RecordService.java
        │   │   ├── service/DownloadStatService.java
        │   │   ├── service/BootstrapService.java
        │   │   ├── http/Router.java
        │   │   ├── http/AdminApiHandler.java
        │   │   ├── http/PublicApiHandler.java
        │   │   └── http/util/{JsonUtil,HttpUtil}.java
        │   ├── resources/
        │   │   ├── bootstrap/install.bat.tmpl   # Windows 下载器模板
        │   │   ├── bootstrap/install.sh.tmpl   # Linux 下载器模板
        │   │   └── web/{index.html,app.css,app.js}
        │   └── test/java/.../                  # 17 个单测
        └── target/upgrade-server.jar           # 构建产物（fat jar，~13MB）
```

---

## 3. 快速开始

### 3.1 构建产物

```powershell
cd d:\workspace\aipro\upgrade
mvn -s settings.xml clean package -DskipTests
# 产物：
#   upgrade-client/target/upgrade-client.jar       （SDK，给宿主引用）
#   upgrade-server/target/upgrade-server.jar       （服务器 fat jar）
```

离线构建（断网必加 `-o`）：
```powershell
mvn -o -s settings.xml clean package -DskipTests
```

### 3.2 启动服务器

```powershell
cd d:\workspace\aipro\upgrade\upgrade-server
java -jar target\upgrade-server.jar [port] [host]
# 默认 port=8090 host=0.0.0.0
# 首次启动自动建表 + 写入默认 admin 账号（admin/admin，首登强制改密）
```

环境变量覆盖：
| 变量 | 默认值 | 说明 |
|---|---|---|
| `UPGRADE_SERVER_PORT` | 8090 | 监听端口 |
| `UPGRADE_SERVER_HOST` | 0.0.0.0 | 监听地址 |
| `UPGRADE_SERVER_BASE_URL` | `http://<host>:<port>` | 下载器分发用的对外 URL（反代时需覆盖） |

### 3.3 访问后台

浏览器打开 `http://127.0.0.1:8090/`，使用 `admin / admin` 登录，首次登录强制改密。

### 3.4 宿主接入 SDK

宿主 `pom.xml` 加依赖：
```xml
<dependency>
    <groupId>com.aipro</groupId>
    <artifactId>upgrade-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

宿主启动时调用一次：
```java
UpgradeClient.getInstance().checkAndUpgrade();
```

宿主工作目录放 `upgrade.properties`：
```properties
upgrade.server.url=http://your-server:8090
upgrade.strategy=ASK            # FORCE 强制 / ASK 询问（默认）/ NONE 不升级
upgrade.current.version=1.0.0
upgrade.retry.max=3
upgrade.retry.backoff.ms=2000
upgrade.download.dir=./upgrade/tmp
upgrade.backup.dir=./upgrade/backup
upgrade.prompt.impl=            # 空表示用默认 Swing 弹窗
```

加载顺序：工作目录 `upgrade.properties` → classpath 同名文件 → 内置默认值。

---

## 4. API 列表

### 4.1 公开 API（无鉴权，供客户端/下载器调用）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/version/latest` | 拉取最新已发布 SDK 版本元信息 |
| GET | `/api/file/{version}/{filename}` | 下载 SDK 版本文件（Range + 中文文件名） |
| GET | `/api/file/tool/{toolId}/{version}/{filename}` | 下载工具本体文件 |
| POST | `/api/record/update` | 客户端上报更新结果 |
| GET | `/api/policy/status?toolId=&clientId=` | 查询灰度是否放开 |
| GET | `/api/bootstrap/{toolId}?platform=&format=kv` | 下载器安装清单（KV 格式） |
| GET | `/d/{toolId}?platform=win` | 下载器脚本分发（返回 install.bat / install.sh） |

### 4.2 鉴权 API

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/auth/login` | 登录（返回 token + mustChangePwd） |
| POST | `/api/auth/logout` | 登出 |
| POST | `/api/auth/change-password` | 改密 |
| GET | `/api/auth/me` | 当前用户信息 |

所有后台 API 需带 `X-Auth-Token` 头。首登未改密前除 `/api/auth/me` 外均拒绝。

### 4.3 后台管理 API（`/api/admin/*`，需登录）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/admin/dashboard` | 仪表盘聚合数据 |
| GET/POST | `/api/admin/versions` | SDK 版本列表 / 上传新版本（body=zip） |
| POST | `/api/admin/versions/{id}/publish` | 发布 SDK 版本 |
| POST | `/api/admin/versions/{id}/offline` | 下线 SDK 版本 |
| GET | `/api/admin/versions/{id}/files` | SDK 版本文件清单 |
| GET/POST | `/api/admin/tools` | 工具列表 / 注册工具 |
| GET/POST | `/api/admin/tools/{toolId}/versions` | 工具版本列表 / 上传工具本体（body=zip） |
| POST | `/api/admin/tools/versions/{id}/publish` | 发布工具版本 |
| POST | `/api/admin/tools/versions/{id}/offline` | 下线工具版本 |
| GET | `/api/admin/tools/versions/{id}/files` | 工具版本文件清单 |
| GET/POST | `/api/admin/tools/{toolId}/policy` | 灰度配置（按工具） |
| POST | `/api/admin/tools/{toolId}/policy/release` | 一键放开灰度 |
| POST | `/api/admin/tools/{toolId}/policy/pause` | 一键暂停灰度 |
| POST | `/api/admin/tools/{toolId}/policy/reset` | 重置灰度计数 |
| GET | `/api/admin/tools/{toolId}/distribute` | 分发链接与下载统计 |
| GET | `/api/admin/records` | 客户端更新记录（普通用户按工具版本号过滤） |
| GET/POST | `/api/admin/users` | 用户管理（仅 ADMIN） |
| PATCH/DELETE | `/api/admin/users/{id}` | 改角色/启停/删除（`transferTo` 转移工具归属） |

权限模型：ADMIN 管所有工具 + 用户管理；USER 仅可见/操作自己创建的工具。

### 4.4 上传协议

工具本体/SDK 版本上传采用 `body=zip 二进制 + 元信息走 query param`，避免 multipart boundary 解析。例：
```
POST /api/admin/tools/recorder/versions?version=1.0.0&platform=win&startCommand=java+-jar+recorder.jar
Content-Type: application/zip
X-Auth-Token: sess-xxx

<zip 二进制>
```

---

## 5. 数据库表

服务器启动自动建 9 张表（SQLite，`upgrade.db` 文件随 jar 同目录）：

| 表 | 用途 |
|---|---|
| `admin_user` | 后台账号（BCrypt 哈希、role、status、must_change_pwd） |
| `version` | SDK 升级版本（versionNo、status、releaseNote、totalSize） |
| `version_file` | SDK 版本文件清单（filePath、sha256、size） |
| `client_update_record` | 客户端更新记录（clientId、clientIp、old/newVersion、result） |
| `tool` | 工具注册（toolId、name、ownerUserId、defaultStartCmd） |
| `tool_version` | 工具版本（toolId、version、platform、startCommand、status） |
| `tool_version_file` | 工具版本文件清单 |
| `push_policy` | 灰度策略（按 toolId：enabled、threshold、currentCount、whitelist） |
| `download_stat` | 下载统计（BOOTSTRAP/FILE 阶段、按平台/按天聚合） |

PRAGMA：`foreign_keys=ON`、`journal_mode=WAL`。

---

## 6. KV 清单格式

下载器解析 JSON 困难，清单采用 KV 格式（`for /f` / `while read` 友好）：

```
TOOL_ID=recorder
VERSION=1.0.0
PLATFORM=win
START_COMMAND=java -jar recorder.jar
TOTAL_SIZE=28
FILE_COUNT=1
FILE_1=recorder.jar|49768b2a3c26e3372d89c6c94ef89b09b6ea733656e5e6ae8a45dcaf903e19f8|28
```

`FILE_N` 格式：`<filename>|<sha256>|<size>`，逐行解析。

---

## 7. 自测验证

| 测试类型 | 数量 | 结果 |
|---|---|---|
| 服务器单测（HttpUtil/PasswordHasher/Database+Service） | 17 | 全过 |
| 客户端单测（VersionComparator/JsonParser/Sha256/Config） | 35 | 全过 |
| E2E 端到端（13 步闭环） | 13 | 全过 |

E2E 脚本 `e2e-test.ps1` 覆盖：登录→改密→注册工具→上传本体→发布→拉清单→下载器脚本→本体下载→SHA-256 校验→灰度状态→更新上报→仪表盘。

运行方式：
```powershell
# 1. 启动服务器
cd d:\workspace\aipro\upgrade\upgrade-server
java -jar target\upgrade-server.jar 18090 127.0.0.1

# 2. 另开终端跑 E2E
cd d:\workspace\aipro\upgrade
powershell -NoProfile -ExecutionPolicy Bypass -File e2e-test.ps1
```

---

## 8. 关键技术决策

| 项 | 选型 | 理由 |
|---|---|---|
| HTTP 框架 | `com.sun.net.httpserver.HttpServer` | JDK 自带，零依赖，符合离线单 jar |
| 数据库 | SQLite（`org.xerial:sqlite-jdbc` 3.45.1.0） | 内嵌，自带各平台 native 库 |
| 密码哈希 | BCrypt（`org.mindrot:jbcrypt` 0.4） | PRD F-S-10 |
| JSON | 手写 `JsonUtil` | 不依赖第三方库，便于离线部署 |
| 打包 | `maven-shade-plugin` 3.5.1 | fat jar 含全部依赖与 native 库 |
| JDK | 1.8 x64 | 用户约束，Win7 离线场景 |
| 代码注释 | 全中文 | 用户约束 |

---

## 9. 更多文档

- 部署与运维：见 [DEPLOY.md](DEPLOY.md)
- 完整需求规格：见 [PRD.md](PRD.md)
