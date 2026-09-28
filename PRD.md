# 自动升级更新模块 PRD

> 版本：v1.0  ·  日期：2026-09-28  ·  作者：产品经理
> 本文档描述「自动升级更新模块」工具的产品需求，分为**客户端模块**与**服务器端版本管理工具**两部分，输出功能清单、界面说明、数据说明三大块。

---

## 1. 概述

### 1.1 背景与目标

为 Java 应用群（如 `recorder` 屏幕录像工具等）提供统一的自动升级能力，避免每个项目重复造轮子。目标：

- 客户端以 **SDK（jar 包）** 形式被主模块引用，零侵入接入升级流程；
- 服务器端提供 **Web 管理后台**，集中管理版本发布、灰度推送与客户端更新记录；
- 全链路支持 **Windows / Win7 离线** 场景，单 jar 部署，无外部组件依赖；
- 通过 **灰度数量控制 + 版本回滚** 机制，避免更新不当造成大范围问题。
- 对外发布时提供 **引导下载器（Bootstrap Installer）**：用户首次通过一个 URL 拿到的是体积很小的下载器（非本体），本地运行后才按 URL 中的"工具特征值"去服务器拉取本体并启动；本体内嵌升级 SDK，后续走自动升级闭环。支持 Windows 与 Linux。

### 1.2 名词定义

| 名词 | 含义 |
|---|---|
| 主模块 / 宿主程序 | 引用客户端 SDK 的业务程序（如 recorder.jar） |
| 客户端 SDK | 以 Maven 依赖形式被宿主引用的升级 jar 包，提供升级门面 API |
| 服务器端版本管理工具 | 独立部署的 Web 服务（含管理后台 + SQLite3），负责版本发布、推送控制、更新记录 |
| 版本包 | 一次升级的文件集合，zip 打包上传，含若干待替换文件 |
| 升级策略 | 宿主发现新版本时的行为：FORCE 强制 / ASK 询问 / NONE 不升级 |
| 灰度 | 限制新版本推送数量，达到阈值后停止向新客户端推送 |
| version.json | 服务器自动生成的版本元信息文件（版本号、文件清单、SHA-256、生成时间） |
| 工具特征值（toolId） | 对外分发的工具唯一标识（如 `recorder`、`upgrade-client`），URL 中承载，用于下载器定位要下载的本体 |
| 引导下载器（Bootstrap Installer） | 对外发布的小体积启动器（Win 用 `.bat`、Linux 用 `.sh`），不依赖 Java，运行后向服务器查询本体清单并下载本体，落地后启动 |
| 首次分发 | 用户第一次获取本体的过程，由下载器完成；与"自动升级"（热升级）相对，二者构成完整闭环 |
| 工具（Tool） | 一个可对外分发的产品（如 recorder），对应一个 toolId；一个服务器可同时托管多个工具 |
| 用户角色（Role） | 后台账号角色：`ADMIN` 管理员 / `USER` 普通用户；管理员管所有工具 + 用户管理 + 系统配置，普通用户只管自己创建的工具 |
| 工具归属（Ownership） | 每个工具有唯一 `owner_user_id`（创建者）；普通用户仅可见/操作自己的工具，管理员不受限 |
| 灰度（按工具） | 灰度配置按 `tool_id` 独立，每个工具有自己的阈值/计数/白名单；普通用户管理自己工具的灰度 |

### 1.3 系统架构

```
┌──────────────────────┐        HTTP         ┌──────────────────────────────────┐
│  主模块 / 宿主程序   │  ───────────────►  │  服务器端版本管理工具             │
│  （recorder.jar 等） │                     │  ┌────────────┐  ┌────────────┐    │
│  ┌────────────────┐  │  ◄──文件下载────  │  │ Web 后台   │  │ SQLite3 DB │    │
│  │ 客户端升级 SDK │  │  ───更新上报────►  │  │ (管理页面) │  │ (.db 文件) │    │
│  │  (jar 引用)    │  │                    │  └────────────┘  └────────────┘    │
│  └────────────────┘  │                    └──────────────────────────────────┘
│  本地: version.json  │                                ▲
│  update_log.txt      │                                │ 浏览器访问
│  file_manifest.json  │                                │
│  backup/ 上一版本    │                    ┌──────────────────────────────────┐
└──────────────────────┘                    │  运维人员（Web 浏览器）           │
                                            └──────────────────────────────────┘
```

#### 1.3.1 首次分发链路（下载器）

```
   用户                     服务器                            客户端工作目录
   ────                     ────                              ─────────────
    │                         │
    │  1.访问 http://host:8090/d/recorder
    │ ─────────────────────► │  返回对应平台下载器（bat/sh，内嵌 toolId+serverUrl）
    │ ◄───────────────────── │
    │                         │
    │  2.本地运行 install.bat / install.sh
    │ ─────────────────────► │  GET /api/bootstrap/recorder?platform=win
    │                         │  返回安装清单（本体文件列表 + SHA-256 + 启动命令）
    │ ◄───────────────────── │
    │                         │
    │  3.按清单逐文件下载本体  │
    │ ─────────────────────► │  GET /api/file/tool/{toolId}/{version}/{filename}
    │ ◄───────────────────── │  （含中文文件名、Range、SHA-256 校验）
    │                         │
    │  4.校验 + 落地 + 启动    │
    │                         │  recorder.jar（内嵌升级 SDK）
    │                         │  ──────────────────────────────────────
    │                         │  后续：宿主启动 → 升级 SDK 检查新版本 → 进入热升级闭环
```

### 1.4 技术约束

- **语言**：Java，JDK 1.8 x64。
- **构建**：Maven，`maven-shade-plugin` 打 fat jar（含全部依赖与 native 库）。
- **运行环境**：Windows / Win7 64 位离线场景（参考 `recorder` 项目 `DEPLOY-WIN7-CHECKLIST.md`）。
- **代码注释**：全部中文。
- **交付**：jar 包 + 项目说明文档 + 中文注释齐全。
- **下载器约束**：下载器**不依赖 Java**（用户首次可能未装 JRE），Windows 用 `.bat` + 内置 `curl` / `PowerShell`，Linux 用 `.sh` + `curl` / `wget`；体积控制在数十 KB 以内。
- **权限模型**：后台采用 RBAC，两类角色（ADMIN / USER），权限按工具归属隔离；普通用户账号由管理员创建（不开放自助注册）。

---

## 2. 功能清单

### 2.1 客户端模块（SDK）

| 编号 | 功能 | 描述 | 对应需求 |
|---|---|---|---|
| F-C-01 | 升级策略配置 | 通过 `upgrade.properties` 配置 `FORCE / ASK / NONE` 三种策略，宿主可热切换 | 需求 2 |
| F-C-02 | 启动版本检查 | 宿主启动时调用 SDK API 拉取服务器最新版本元信息 | 需求 3 |
| F-C-03 | 版本比对 | 本地版本号 vs 服务器版本号，语义化版本（SemVer）比较 | 需求 3 |
| F-C-04 | 下载与失败重试 | HTTP 下载更新包，失败自动重试（默认 3 次，指数退避），支持断点续传（Range） | 需求 4 |
| F-C-05 | 中文文件名支持 | 下载 URL 与本地落盘均按 UTF-8 处理中文文件名，避免乱码 | 需求 4 |
| F-C-06 | 文件占用处理与自动重启 | 通过**独立重启器**（bat 脚本模板随 SDK 提供）解决 jar 文件占用：宿主退出 → 替换文件 → 拉起新版宿主 | 需求 5 |
| F-C-07 | 本地升级日志与文件清单 | 本地保存 `update_log.txt`（升级历史）与 `file_manifest.json`（本次文件更新清单） | 需求 5 |
| F-C-08 | 宿主集成 API | 提供 `UpgradeClient.checkAndUpgrade()` 等门面方法，宿主一行代码接入 | 需求 1、2 |
| F-C-09 | 询问升级交互 | ASK 策略下默认 Swing 弹窗，宿主可实现 `UpgradePrompt` 接口自定义 | 需求 2 |
| F-C-10 | 更新结果上报 | 更新成功/失败后回调服务器，写入更新记录（含 ip、旧版本、新版本、时间） | 需求 7 |
| F-C-11 | 下载校验 | 下载完成后逐文件 SHA-256 比对，不一致丢弃并重试；全部通过后原子替换 | 需求 4、6 |
| F-C-12 | 回滚支持 | 保留上一版本备份 `backup/`，新版本启动失败时可手动/自动回退 | 需求 5 |

### 2.2 服务器端版本管理工具（Web 服务）

| 编号 | 功能 | 描述 | 对应需求 |
|---|---|---|---|
| F-S-01 | 更新包上传 | Web 后台上传 zip 更新包，自动解包、计算每个文件 SHA-256，生成 `version.json` | 需求 6（更优方案） |
| F-S-02 | 版本发布与下线 | 版本状态 `DRAFT / PUBLISHED / OFFLINE`，仅 PUBLISHED 推送给客户端 | 需求 6 |
| F-S-03 | 客户端版本查询 API | `GET /api/version/latest` 返回最新 PUBLISHED 版本元信息 | 需求 3 |
| F-S-04 | 文件下载 API | `GET /api/file/{version}/{filename}`，支持 Range 断点续传、中文文件名（Content-Disposition RFC 5987） | 需求 4 |
| F-S-05 | 客户端更新记录 | 接收客户端上报（ip、旧版本、新版本、更新时间、结果），写入 SQLite3 `client_update_record` 表，可在后台查询与导出 | 需求 7 |
| F-S-06 | 灰度推送数量控制 | **按工具独立阈值**（如 10），达到后停止向新客户端推送该工具新版本；后台可一键放开/调整阈值 | 需求 8 |
| F-S-07 | 灰度白名单 | 可配置 IP / 客户端 ID 白名单（按工具），白名单内不受阈值限制（用于试点验证） | 需求 8（增强） |
| F-S-08 | Web 管理后台 | 浏览器访问的页面集（见 §3.2） | 需求 6、7、8 |
| F-S-09 | 版本回滚 | 后台一键将某版本置为 OFFLINE，客户端下次检查自动回退到上一可用版本 | 需求 6、8 |
| F-S-10 | 多用户登录与 RBAC | 用户名/密码登录（BCrypt 哈希，首次登录强制改密）；角色 `ADMIN` / `USER`；权限按工具归属隔离 | 需求 6、7、8 |
| F-S-18 | 用户管理（仅管理员） | 增删用户、改角色（ADMIN/USER）、重置密码、启用/禁用账号；不开放自助注册 | 需求 6、7、8 |
| F-S-19 | 工具归属隔离 | 工具注册时绑定 `owner_user_id`；普通用户仅可见/操作自己的工具（工具列表、版本上传、分发链接、更新记录、灰度均按归属过滤）；管理员不受限 | 需求 6、7、8 |
| F-S-20 | 个人中心 | 普通用户可改自己的密码、查看自己创建的工具与更新记录汇总 | 需求 6、7、8 |

### 2.3 首次分发下载器（Bootstrap Installer）

> 对外发布的小体积启动器，不依赖 Java，负责把本体从服务器拉到本地并启动。下载器本身不参与后续升级（升级由本体内嵌的升级 SDK 负责）。

#### 2.3.1 客户端侧（下载器脚本，Win `install.bat` / Linux `install.sh`）

| 编号 | 功能 | 描述 |
|---|---|---|
| F-B-01 | 内嵌特征值 | 下载器脚本内嵌 `TOOL_ID`、`SERVER_URL`、`PLATFORM`，由服务器在 `/d/{toolId}` 时按请求平台动态生成 |
| F-B-02 | 平台自识别 | 默认按内嵌 `PLATFORM` 执行；若为 `auto`，则脚本内根据 `OS` / `uname` 判断 win/linux |
| F-B-03 | 拉取安装清单 | 调用 `GET /api/bootstrap/{toolId}?platform={win\|linux}` 获取本体文件清单（路径、SHA-256、大小、启动命令） |
| F-B-04 | 逐文件下载与重试 | 用 `curl`（Win10+/Linux）或 `PowerShell Invoke-WebRequest`（Win7 兜底）下载，失败重试 3 次，支持中文文件名（UTF-8 URL 编码） |
| F-B-05 | SHA-256 校验 | 下载后用 `certutil -hashfile`（Win）/ `sha256sum`（Linux）校验，不一致重试或终止并报错 |
| F-B-06 | 落地与目录管理 | 默认落地到当前目录 `./<toolId>/`；支持环境变量 `INSTALL_DIR` 覆盖；中文文件名按 UTF-8 写盘 |
| F-B-07 | 启动本体 | 校验通过后执行清单中的 `startCommand`（如 `java -jar recorder.jar`），无 Java 时给出明确提示并退出 |
| F-B-08 | 日志输出 | 控制台打印每步进度（拉清单 / 下载中 N/M / 校验 / 启动）；同时写 `install_<时间戳>.log` |
| F-B-09 | 离线兜底 | 若检测到本机已有完整本体（通过本地 manifest 比对），跳过下载直接启动 |

#### 2.3.2 服务器侧（工具分发）

| 编号 | 功能 | 描述 |
|---|---|---|
| F-S-11 | 工具注册 | 后台注册工具：toolId（唯一）、名称、描述、默认启动命令模板 |
| F-S-12 | 工具本体上传 | 按平台（win/linux）上传本体 zip，自动解包、SHA-256、生成 `tool_version` 与 `tool_version_file` 记录 |
| F-S-13 | 下载器分发路由 | `GET /d/{toolId}` 按 `User-Agent` 或 `?platform=` 返回对应平台下载器脚本，脚本内动态注入 `TOOL_ID`/`SERVER_URL`/`PLATFORM` |
| F-S-14 | 安装清单 API | `GET /api/bootstrap/{toolId}?platform={win\|linux}` 返回该工具最新 PUBLISHED 本体的文件清单 + SHA-256 + 启动命令 |
| F-S-15 | 本体文件下载 API | `GET /api/file/tool/{toolId}/{version}/{filename}` 复用 §F-S-04 的 Range / 中文文件名能力 |
| F-S-16 | 分发链接页 | 后台展示分发 URL（`http://host/d/{toolId}`）+ 各平台下载器预览 + 一键复制 / 二维码 |
| F-S-17 | 下载统计 | 记录下载器访问与本体下载次数（按平台、按天），在分发链接页展示 |

### 2.4 需求覆盖追溯

| 原始需求 | 客户端覆盖 | 服务器端覆盖 |
|---|---|---|
| 1. Java / jar 包 | F-C-08 | — |
| 2. 升级策略配置 | F-C-01、F-C-09 | — |
| 3. 启动检查版本 | F-C-02、F-C-03 | F-S-03 |
| 4. 失败重试 + 中文文件名 | F-C-04、F-C-05、F-C-11 | F-S-04 |
| 5. 自动重启 + 文件占用 + 本地清单 | F-C-06、F-C-07、F-C-12 | — |
| 6. 服务器自动生成版本文件 | — | F-S-01、F-S-02、F-S-09 |
| 7. 服务器记录客户端更新信息 | F-C-10 | F-S-05、F-S-08 |
| 8. 控制更新数量 | — | F-S-06、F-S-07、F-S-08 |
| 9.（新增）首次分发下载器 | F-B-01 ~ F-B-09 | F-S-11 ~ F-S-17 |

---

## 3. 界面说明

### 3.1 客户端（SDK 嵌入式交互）

客户端默认**无独立界面**，交互由策略触发：

#### 3.1.1 ASK 询问策略默认弹窗（Swing `JOptionPane`）

```
┌─────────────────────────────────────────────┐
│  发现新版本 v1.2.0                          │
├─────────────────────────────────────────────┤
│  当前版本：v1.1.0                          │
│  文件大小：12.3 MB                         │
│  更新说明：                                 │
│  - 修复录制颜色异常                          │
│  - 新增 HTTP API 暂停接口                    │
│                                             │
│       [立即更新]    [稍后]    [跳过本次]      │
└─────────────────────────────────────────────┘
```

- **立即更新**：进入下载进度界面。
- **稍后**：本次启动不更新，下次启动再问。
- **跳过本次**：记录跳过版本号，下次启动若仍是该版本则不再询问。

#### 3.1.2 FORCE 强制策略进度窗口

```
┌─────────────────────────────────────────────┐
│  正在更新到 v1.2.0（强制更新，请勿关闭）       │
├─────────────────────────────────────────────┤
│  下载进度：████████░░ 80%   2.3 MB/s        │
│  剩余时间：00:08                            │
│  [第 2 次重试中...]（仅重试时显示）          │
└─────────────────────────────────────────────┘
```

- **无取消按钮**，宿主主线程阻塞等待更新完成。
- 下载完成后自动替换文件并重启。

#### 3.1.3 自定义交互

宿主可实现 `UpgradePrompt` 接口（提供 `onAsk(versionInfo)` / `onProgress(progress)` / `onResult(success, msg)` 回调），将交互替换为 JavaFX / Web UI / 命令行等形式。

#### 3.1.4 下载器命令行交互（首次分发）

下载器为纯命令行工具，无 GUI，运行示例（Windows）：

```
C:\> install.bat
[INFO] 工具: recorder  平台: win  服务器: http://10.0.0.5:8090
[INFO] 拉取安装清单... 版本 v1.2.0  共 3 个文件 / 30.5 MB
[INFO] 下载 [1/3] recorder.jar ........ 28.6 MB  [████████████] 100%  2.3 MB/s
[INFO] 下载 [2/3] 配置/默认参数.properties ... 512 B  [████] 100%
[INFO] 下载 [3/3] 启动.bat ... 256 B  [████] 100%
[INFO] SHA-256 校验: 全部通过
[INFO] 落地目录: .\recorder\
[INFO] 启动本体: java -jar recorder.jar
[INFO] 未检测到 Java，请先安装 JDK 1.8 后重试。退出码 1。
```

Linux 示例：

```
$ chmod +x install.sh && ./install.sh
[INFO] 工具: recorder  平台: linux  服务器: http://10.0.0.5:8090
[INFO] 拉取安装清单... 版本 v1.2.0  共 3 个文件 / 30.5 MB
[INFO] 下载 [1/3] recorder.jar ........ 28.6 MB  [████████████] 100%
...
[INFO] 启动本体: java -jar recorder.jar
```

- 支持参数：`install.bat --dir=D:\mytool --platform=linux --no-start`（覆盖落地目录、平台、仅下载不启动）。
- 日志同时写入 `install_<时间戳>.log`，便于排查。

### 3.2 服务器端 Web 管理后台

> 风格：简洁后台（参考 Bootstrap 风格的纯 HTML + 原生 CSS + 少量原生 JS，避免 Vue/React 重前端，便于离线单 jar 部署）。
> 默认端口：8090，启动后访问 `http://<服务器IP>:8090/`。

#### 3.2.1 页面清单

| 页面 | 路径 | 核心元素 | 权限 |
|---|---|---|---|
| 登录页 | `/login` | 用户名/密码（默认 admin/admin，首次登录强制改密） | 公开 |
| 仪表盘 | `/` | 当前最新版本卡片、已发布版本数、累计更新客户端数、今日更新数、灰度状态卡片（开关 + 阈值 + 剩余配额）。普通用户仅展示自己工具的聚合数据 | ALL |
| 版本列表 | `/versions` | 表格：版本号、状态、生成时间、文件数、总大小、更新客户端数；操作：查看 / 下线 / 回滚 | ALL（按工具归属过滤） |
| 上传新版本 | `/versions/new` | 上传 zip、版本号（语义化）、更新说明、目标客户端范围（可选）；提交后自动生成 version.json 并展示 | ALL |
| 版本详情 | `/versions/{id}` | 文件清单（路径、SHA-256、大小）、更新记录子表、状态变更历史 | ALL（按归属过滤） |
| 客户端更新记录 | `/records` | 表格：客户端 IP/ID、旧版本、新版本、更新时间、结果（成功/失败）、耗时；按版本/IP/时间筛选；导出 CSV | ALL（按工具归属过滤） |
| 灰度控制 | `/policy` | **按工具**：选择工具后展示其开关、推送阈值（默认 10）、当前已更新数、剩余配额、白名单 IP/ID 列表（增删）、一键放开/暂停按钮 | ALL（普通用户仅自己工具） |
| 工具列表 | `/tools` | 已注册对外分发工具：toolId、名称、描述、最新版本、下载次数、**归属用户**；操作：管理版本 / 查看分发链接。普通用户仅显示自己的；管理员显示全部（可按归属筛选） | ALL |
| 工具版本管理 | `/tools/{toolId}/versions` | 按平台（win/linux）展示本体版本列表；上传新本体 zip、查看文件清单、设置 PUBLISHED、查看下载统计 | ALL（仅归属用户或管理员） |
| 分发链接 | `/tools/{toolId}/distribute` | 展示对外分发 URL `http://host/d/{toolId}`、各平台下载器脚本预览、一键复制、二维码、近期下载次数（按平台/按天） | ALL（仅归属用户或管理员） |
| 用户管理 | `/admin/users` | 用户列表：用户名、角色、状态、创建时间、拥有的工具数；操作：新增 / 改角色 / 重置密码 / 启用禁用 / 删除 | **仅 ADMIN** |
| 个人中心 | `/me` | 当前用户信息、修改密码、我创建的工具列表、我的工具更新记录汇总 | ALL |

#### 3.2.2 顶部导航与权限矩阵

**顶部导航栏**（登录后所有页面常驻）

```
┌──────────────────────────────────────────────────────────────────┐
│  自动升级管理后台           当前用户：alice  [USER]  [个人中心] [退出] │
├──────────────────────────────────────────────────────────────────┤
│  [仪表盘] [版本列表] [上传新版本] [更新记录] [灰度控制] [工具列表]   │
│  [用户管理]（仅 ADMIN 可见）                                       │
└──────────────────────────────────────────────────────────────────┘
```

- 普通用户（USER）：导航不含「用户管理」，所有列表页自动按归属过滤，仅显示自己创建的工具及其相关数据。
- 管理员（ADMIN）：导航含「用户管理」，所有列表页默认显示全部，支持按「归属用户」筛选。

**权限矩阵**

| 功能 / 页面 | ADMIN | USER |
|---|---|---|
| 查看所有工具及版本 | ✓ | ✗（仅自己的） |
| 注册新工具 | ✓ | ✓（自动归属自己） |
| 上传本体 / 版本发布与下线 / 回滚 | ✓（任意工具） | ✓（仅自己的工具） |
| 分发链接查看 | ✓（任意） | ✓（仅自己的） |
| 客户端更新记录查询与导出 | ✓（全部） | ✓（仅自己工具的记录） |
| 灰度控制（按工具） | ✓（任意工具） | ✓（仅自己的工具） |
| 用户管理（增删改角色 / 重置密码 / 启用禁用） | ✓ | ✗ |
| 修改自己密码 | ✓ | ✓ |
| 个人中心 | ✓ | ✓ |
| 客户端公开 API（`/d/{toolId}`、`/api/*`） | 公开（无登录） | 公开（无登录） |

#### 3.2.3 关键页面示意

**仪表盘 `/`**

```
┌──────────────────────────────────────────────────────────────────┐
│  自动升级管理后台        欢迎，alice  [USER]   [个人中心] [退出]    │
├──────────────────────────────────────────────────────────────────┤
│  [仪表盘] [版本列表] [上传新版本] [更新记录] [灰度控制] [工具列表]   │
├──────────────────────────────────────────────────────────────────┤
│  我的工具：2 个  当前最新版本：v1.2.0       灰度状态：开启         │
│  我的已发布版本数：3   累计更新客户端：12    阈值：10            │
│  今日更新数：2         最近更新：2026-09-28  当前已更新：3       │
│                                                  剩余配额：7     │
│                                                  [一键暂停推送]   │
└──────────────────────────────────────────────────────────────────┘
```

**灰度控制 `/policy`**

```
┌──────────────────────────────────────────────────────────────────┐
│  灰度推送控制                                                     │
├──────────────────────────────────────────────────────────────────┤
│  选择工具：[ recorder ▼ ]  （普通用户仅可选自己的工具）             │
├──────────────────────────────────────────────────────────────────┤
│  开关：[✓] 开启                                                   │
│  推送阈值：[ 10 ]    当前已更新：8    剩余配额：2                  │
│                                                                   │
│  白名单（不受阈值限制）：                                          │
│  ┌─────────────────────┐  [+ 添加 IP/ID]                          │
│  │ 192.168.1.10        │  [删除]                                   │
│  │ client-uuid-001     │  [删除]                                   │
│  └─────────────────────┘                                          │
│                                                                   │
│  [保存配置]   [一键放开（清除计数）]   [一键暂停]                   │
└──────────────────────────────────────────────────────────────────┘
```

**用户管理 `/admin/users`（仅 ADMIN）**

```
┌──────────────────────────────────────────────────────────────────┐
│  用户管理                                            [+ 新增用户]  │
├──────────────────────────────────────────────────────────────────┤
│  用户名       角色     状态     创建时间       工具数   操作        │
│  admin        ADMIN    启用     2026-09-01     5        [改密][禁用] │
│  alice        USER     启用     2026-09-10     2        [改密][禁用][删除] │
│  bob          USER     禁用     2026-09-15     0        [启用][删除]  │
└──────────────────────────────────────────────────────────────────┘
```

- 新增用户：用户名、初始密码（明文展示一次，落库前 BCrypt 哈希）、角色（ADMIN/USER）。
- 改角色：USER ↔ ADMIN（最后一个 ADMIN 不可降级，避免无人能管理）。
- 删除：先将其工具归属转移给 ADMIN 或其他用户，再删除账号。
- 禁用：账号 `status=DISABLED` 后无法登录，但历史记录与工具保留。

---

## 4. 数据说明

### 4.1 客户端配置（`upgrade.properties`，随宿主打包）

| 配置项 | 说明 | 默认值 |
|---|---|---|
| `upgrade.server.url` | 服务器端基础 URL | `http://127.0.0.1:8090` |
| `upgrade.strategy` | 升级策略：FORCE / ASK / NONE | `ASK` |
| `upgrade.client.id` | 客户端唯一标识（缺失则用 IP + MAC 生成 UUID） | 自动生成 UUID |
| `upgrade.retry.max` | 下载失败最大重试次数 | `3` |
| `upgrade.retry.backoff.ms` | 重试退避基数（指数：base × 2^n） | `2000` |
| `upgrade.download.dir` | 下载临时目录 | `./upgrade/tmp` |
| `upgrade.backup.dir` | 回滚备份目录 | `./upgrade/backup` |
| `upgrade.current.version` | 当前版本号（自动维护，更新后自动写入） | 宿主初始版本 |
| `upgrade.skip.version` | 用户跳过的版本号（ASK 跳过本次后记录） | 空 |
| `upgrade.prompt.impl` | 自定义交互实现类全名（可选） | 空（用默认 Swing） |

### 4.2 服务器端 SQLite3 数据表

> 数据库文件：`upgrade.db`，随服务器 jar 同目录部署，自动创建。
> 字符集：UTF-8（SQLite 默认）。

#### 4.2.1 `version`（版本表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | 主键 |
| version_no | TEXT NOT NULL UNIQUE | 版本号（语义化，如 1.2.0） |
| status | TEXT NOT NULL | 状态：DRAFT / PUBLISHED / OFFLINE |
| release_note | TEXT | 更新说明 |
| file_count | INTEGER | 文件数 |
| total_size | INTEGER | 总大小（字节） |
| created_at | TEXT | 生成时间（ISO 8601） |
| published_at | TEXT | 发布时间 |

#### 4.2.2 `version_file`（版本文件清单）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | 主键 |
| version_id | INTEGER | 外键 → version.id |
| file_path | TEXT | 文件相对路径（支持中文） |
| sha256 | TEXT | SHA-256 校验值 |
| size | INTEGER | 文件大小（字节） |
| created_at | TEXT | 生成时间 |

#### 4.2.3 `client_update_record`（客户端更新记录）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | 主键 |
| client_id | TEXT | 客户端唯一标识 |
| client_ip | TEXT | 客户端 IP |
| old_version | TEXT | 之前版本 |
| new_version | TEXT | 现在版本 |
| update_time | TEXT | 更新时间（ISO 8601） |
| result | TEXT | 结果：SUCCESS / FAIL |
| fail_reason | TEXT | 失败原因（失败时填） |
| duration_ms | INTEGER | 更新耗时（毫秒） |

#### 4.2.4 `push_policy`（灰度策略表，按工具配置）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | 主键 |
| tool_id | TEXT UNIQUE | 外键 → tool.tool_id，每工具一行 |
| enabled | INTEGER | 灰度是否开启：0/1 |
| threshold | INTEGER | 推送阈值（默认 10） |
| current_count | INTEGER | 当前已更新计数 |
| whitelist | TEXT | 白名单 IP/ID（CSV） |
| updated_at | TEXT | 最近修改时间 |

#### 4.2.5 `admin_user`（后台用户，多用户 RBAC）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | 主键 |
| username | TEXT UNIQUE | 用户名 |
| password_hash | TEXT | BCrypt 哈希 |
| role | TEXT | 角色：ADMIN / USER |
| status | TEXT | 状态：ACTIVE / DISABLED |
| display_name | TEXT | 显示名（可选） |
| must_change_pwd | INTEGER | 是否首次登录强制改密：0/1 |
| created_at | TEXT | 创建时间 |
| last_login_at | TEXT | 最近登录时间 |

#### 4.2.6 `tool`（对外分发工具注册表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | 主键 |
| tool_id | TEXT UNIQUE | 工具特征值（URL 路径用，如 `recorder`） |
| name | TEXT | 工具名称 |
| description | TEXT | 工具描述 |
| owner_user_id | INTEGER | 外键 → admin_user.id，工具归属用户 |
| default_start_cmd | TEXT | 默认启动命令模板（可被版本覆盖） |
| created_at | TEXT | 创建时间 |

#### 4.2.7 `tool_version`（工具本体版本，按平台）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | 主键 |
| tool_id | TEXT | 外键 → tool.tool_id |
| version | TEXT | 本体版本号（语义化） |
| platform | TEXT | 平台：win / linux |
| start_command | TEXT | 启动命令（如 `java -jar recorder.jar`） |
| status | TEXT | DRAFT / PUBLISHED / OFFLINE |
| file_count | INTEGER | 文件数 |
| total_size | INTEGER | 总大小（字节） |
| release_note | TEXT | 更新说明 |
| created_at | TEXT | 创建时间 |
| published_at | TEXT | 发布时间 |
| UNIQUE | (tool_id, version, platform) | 同工具同版本同平台唯一 |

#### 4.2.8 `tool_version_file`（工具本体文件清单）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | 主键 |
| tool_version_id | INTEGER | 外键 → tool_version.id |
| file_path | TEXT | 文件相对路径（支持中文） |
| sha256 | TEXT | SHA-256 |
| size | INTEGER | 文件大小（字节） |
| created_at | TEXT | 创建时间 |

#### 4.2.9 `download_stat`（分发下载统计）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | INTEGER PK AUTOINCREMENT | 主键 |
| tool_id | TEXT | 工具特征值 |
| stage | TEXT | 阶段：BOOTSTRAP（下载器访问）/ FILE（本体文件下载） |
| platform | TEXT | 平台：win / linux |
| client_ip | TEXT | 访问 IP |
| downloaded_at | TEXT | 下载时间（ISO 8601，按天聚合查询） |

### 4.3 客户端本地文件

| 文件 | 说明 |
|---|---|
| `upgrade.properties` | 配置文件（见 §4.1） |
| `version.json` | 当前版本元信息（版本号、SHA-256、上次更新时间） |
| `update_log.txt` | 升级历史（时间、旧版本→新版本、结果、耗时） |
| `install_<时间戳>.log` | 下载器安装日志（首次分发时生成，便于排查） |
| `file_manifest.json` | 最近一次文件更新清单（路径、新 SHA-256、旧 SHA-256） |
| `backup/` | 上一版本文件备份目录，用于回滚 |

#### 4.3.1 `version.json` 示例

```json
{
  "version": "1.2.0",
  "sha256": "a3f5...e21",
  "updatedAt": "2026-09-28T10:15:30+08:00"
}
```

#### 4.3.2 `file_manifest.json` 示例

```json
{
  "fromVersion": "1.1.0",
  "toVersion": "1.2.0",
  "updatedAt": "2026-09-28T10:15:30+08:00",
  "files": [
    {"path": "recorder.jar", "oldSha256": "b1c2...", "newSha256": "a3f5...", "size": 30241024},
    {"path": "config/default.properties", "oldSha256": null, "newSha256": "9d8e...", "size": 512}
  ]
}
```

### 4.4 关键接口（客户端 ↔ 服务器）

| 方法 | 路径 | 用途 | 关键参数 / 响应 |
|---|---|---|---|
| GET | `/api/version/latest` | 拉取最新 PUBLISHED 版本元信息 | 响应：`{version, releaseNote, files:[{path, sha256, size}], totalSize}` |
| GET | `/api/file/{version}/{filename}` | 下载指定文件 | 支持 `Range` 断点续传；`Content-Disposition: filename*=UTF-8''<编码后中文>` |
| POST | `/api/record/update` | 客户端上报更新结果 | 请求体：`{clientId, oldVersion, newVersion, result, failReason, durationMs}`；服务器自动记录 `client_ip` 与 `update_time` |
| GET | `/api/policy/status` | 查询当前灰度是否放开 | 响应：`{enabled, threshold, currentCount, remaining, whitelistAllowed:true/false}`；客户端若 `enabled=true && remaining<=0 && !whitelistAllowed` 则跳过本次 |
| GET | `/d/{toolId}` | **下载器分发**：返回对应平台下载器脚本 | 查询参数 `?platform=win\|linux` 可强制指定；缺省按 `User-Agent` 判定。响应 `Content-Type: text/plain`，Win 返回 `install.bat`、Linux 返回 `install.sh`，脚本内动态注入 `TOOL_ID` / `SERVER_URL` / `PLATFORM`。同时记录一条 `download_stat(stage=BOOTSTRAP)` |
| GET | `/api/bootstrap/{toolId}?platform={win\|linux}` | **安装清单**：返回该工具最新 PUBLISHED 本体的文件清单 | 响应：`{toolId, version, startCommand, files:[{path, sha256, size}], totalSize, releaseNote}`；客户端无可用版本时返回 404 |
| GET | `/api/file/tool/{toolId}/{version}/{filename}` | **本体文件下载**：复用 Range / 中文文件名能力 | 与 `/api/file/{version}/{filename}` 一致；下载完成记录 `download_stat(stage=FILE)` |

---

## 5. 非功能性需求

- **安全**：
  - 更新包文件 SHA-256 校验，下载后逐文件比对，不一致丢弃并重试。
  - 后台登录密码 BCrypt 哈希存储。
  - 可选 HTTPS（生产环境推荐）。
- **可靠性**：
  - 下载断点续传（HTTP Range）。
  - 失败重试指数退避（默认 3 次，基数 2s）。
  - 原子替换：新文件先落临时目录，校验通过后 `rename` 覆盖。
  - 客户端保留上一版本备份，启动失败可回退。
  - 下载器脚本单文件即可运行，跨平台（Win bat / Linux sh），下载失败自动重试 3 次，全部失败后保留已下载部分并打印明确错误。
- **兼容性**：
  - JDK 1.8 x64、Windows / Win7 离线。
  - 服务器端仅依赖 `sqlite-jdbc`（含 native dll，打进 fat jar，运行时解压到临时目录，需保证该目录可写）+ 轻量 HTTP 框架，无其他外部组件。
  - 下载器**不依赖 Java**：Win 用 `curl`/`PowerShell`，Linux 用 `curl`/`wget`；Win7 默认无 curl，自动降级到 PowerShell `Invoke-WebRequest`。
- **可观测**：
  - 客户端日志可配级别（INFO/DEBUG），默认 INFO。
  - 服务器端访问日志与异常日志，按天滚动。
- **可回滚**：
  - 客户端保留 `backup/` 上一版本。
  - 服务器端可下线问题版本（OFFLINE），客户端下次检查自动回退到上一 PUBLISHED 版本。

---

## 6. 假设与决策

1. **客户端为 SDK 模式**：以 Maven 依赖被宿主引用，jar 包形态（需求 1、2 已明确）。
2. **重启机制**：jar 文件占用无法在 JVM 内部替换，采用**独立重启器**（bat 脚本模板随 SDK 提供，宿主可定制）：宿主退出 → 脚本替换文件 → 拉起新版宿主。
3. **版本文件方案选 SHA-256 + version.json**：优于 md5+version.ini——SHA-256 抗碰撞性更强，JSON 结构化便于扩展（多文件、多平台、签名等），与 SQLite 表结构一致。
4. **服务器端 HTTP 框架**：轻量内嵌（候选：`com.sun.net.httpserver` 或 Jetty embedded），SQLite3 嵌入式（`sqlite-jdbc` 驱动 + native dll），统一打进 fat jar，零外部依赖。
5. **SQLite native 库加载**：`sqlite-jdbc` 内置各平台 native 库，运行时从 jar 解压到 `java.io.tmpdir` 或自定义 `org.sqlite.tmpdir` 目录；Win7 离线场景需确保该临时目录可写（参考 recorder 项目 javacpp 缓存处理：必要时用 `-Dorg.sqlite.tmpdir=D:\devbase\.sqlite` 指定可写目录）。
6. **灰度控制粒度**：默认全局配额（需求 8 原意），增强白名单用于试点。
7. **中文文件名**：URL 按 RFC 3986 百分号编码，HTTP `Content-Disposition` 按 RFC 5987 `filename*=UTF-8''` 编码。
8. **界面技术**：Web 后台用纯 HTML + 原生 CSS + 少量原生 JS，避免 Vue/React 等重前端，便于离线单 jar 部署。
9. **下载器形态**：选用**脚本**（Win `install.bat` + Linux `install.sh`）而非编译型 `.exe`/ELF——脚本可由服务器动态生成（注入 toolId/URL）、零编译成本、跨平台、体积小（数 KB）。脚本逻辑用 `curl`/`PowerShell`/`wget` 等系统自带工具，避免引入运行时依赖。
10. **工具特征值在 URL 路径中**：采用 `/d/{toolId}` 路径式而非查询参数，便于用户记忆、二维码传播、口口相传；`toolId` 全小写字母数字短串（如 `recorder`、`upg-client`）。
11. **下载器与升级 SDK 解耦**：下载器只管"首次落地"，不参与后续版本迭代；本体启动后由内嵌升级 SDK 接管热升级，职责单一、可独立演进。
12. **一个服务器管多个工具**：服务器端引入"工具注册"概念，同一套升级管理后台可托管 recorder、upgrade-client 等多个工具的分发与升级，避免为每个工具单独部署一套服务。
13. **多用户 RBAC**：后台账号分 `ADMIN` / `USER` 两类角色。普通用户只能管自己创建的工具（自动归属），管理员可管所有工具 + 用户管理 + 系统配置。普通用户账号由管理员创建，**不开放自助注册**（避免越权与垃圾账号）。删除用户前需先转移其工具归属。
14. **灰度按工具独立**：`push_policy` 由原全局单行改为按 `tool_id` 多行，每个工具有自己的阈值/计数/白名单。这样普通用户可独立控制自己工具的灰度，权限自然隔离，且某工具出问题不会影响其他工具的推送。
15. **客户端公开 API 不受登录约束**：`/d/{toolId}`、`/api/version/*`、`/api/file/*`、`/api/record/*`、`/api/bootstrap/*` 等面向客户端的接口保持公开（无登录态），仅管理后台页面需登录鉴权；后续可在公开接口加签名/IP 白名单增强安全。
16. **最后一个 ADMIN 保护**：系统禁止将最后一个 ADMIN 降级或禁用，避免无人能管理后台。

---

## 7. 验收清单（PRD 完整性自检）

- [x] 三大块（功能清单 / 界面说明 / 数据说明）齐全。
- [x] 原始 8 条需求逐条覆盖（见 §2.4 追溯表）。
- [x] 新增第 9 条需求（首次分发下载器）已覆盖（F-B-01 ~ F-B-09、F-S-11 ~ F-S-17）。
- [x] 新增多用户 RBAC（F-S-10、F-S-18、F-S-19、F-S-20）已覆盖，权限矩阵见 §3.2.2。
- [x] 每个功能编号可追溯到需求点。
- [x] 数据表与配置项与功能描述一致（如灰度阈值字段存在于 `push_policy` 且按 tool_id，对应 F-S-06；`admin_user.role` 对应 F-S-10；`tool.owner_user_id` 对应 F-S-19；`download_stat` 对应 F-S-17）。
- [x] 界面说明覆盖所有服务器端功能页（含用户管理、个人中心），客户端交互（三种策略 + 下载器 CLI）描述完整。
- [x] 非功能与假设对 Win7 离线 / JDK 1.8 兼容；下载器明确不依赖 Java。
- [x] 下载器支持 Windows 与 Linux 双平台，URL 路径承载 toolId 特征值。
- [x] 多用户权限隔离：普通用户仅可见/操作自己的工具及其相关数据，管理员不受限。
- [x] 客户端公开 API 不受登录约束，仅后台页面需鉴权。

---

## 8. 下载器实现逻辑（Bootstrap Installer）

> 本章给出下载器脚本的具体实现逻辑，作为开发落地依据。脚本由服务器在 `/d/{toolId}` 时按平台动态生成并注入 `TOOL_ID` / `SERVER_URL` / `PLATFORM`，单文件即可运行。

### 8.1 总体流程

```
┌─ 启动 ──────────────────────────────────────────────────────────────┐
│ 1. 解析内嵌变量 TOOL_ID / SERVER_URL / PLATFORM                      │
│ 2. 解析命令行参数 --dir= / --platform= / --no-start                  │
│ 3. 平台检测（PLATFORM=auto 时按 OS 判定 win/linux）                   │
│ 4. 选择下载工具：curl > PowerShell > wget（按可用性降级）             │
│ 5. 创建工作目录 INSTALL_DIR（默认 ./%TOOL_ID%/）                     │
└──────────────────────────────────────────────────────────────────────┘
                                  ▼
┌─ 拉取清单 ──────────────────────────────────────────────────────────┐
│ 6. GET /api/bootstrap/{TOOL_ID}?platform={PLATFORM}&format=kv        │
│ 7. 解析 KV 清单：版本号、文件列表(path|sha256|size)、启动命令          │
│    失败（HTTP 非 200 / 解析失败）→ 重试 3 次后退出码 1                │
└──────────────────────────────────────────────────────────────────────┘
                                  ▼
┌─ 逐文件下载 ─ for each file ────────────────────────────────────────┐
│ 8a. URL 百分号编码文件名（含中文）                                     │
│ 8b. 离线兜底：本地已存在且 SHA-256 匹配 → 跳过本文件                  │
│ 8c. 下载 GET /api/file/tool/{TOOL_ID}/{VER}/{FILENAME}               │
│     - 断点续传：curl -C - / PowerShell 带 Range: bytes=N-            │
│     - 失败重试 3 次，指数退避（2s/4s/8s）                              │
│ 8d. SHA-256 校验：certutil（Win）/ sha256sum（Linux）                │
│     - 不一致 → 删除重下，重试耗尽退出码 3                              │
│ 8e. 进度输出：[i/N] filename ... 100%                                │
└──────────────────────────────────────────────────────────────────────┘
                                  ▼
┌─ 落地与启动 ─────────────────────────────────────────────────────────┐
│ 9.  写 install_<时间戳>.log（清单、每文件校验结果、耗时）              │
│ 10. 若 START_COMMAND 含 java：检测 java -version                      │
│     - 无 Java → 打印明确提示，退出码 5（除非 --no-start 则退出码 0）   │
│ 11. 若未传 --no-start：执行 START_COMMAND（cd INSTALL_DIR 后）         │
│ 12. 退出码 0                                                          │
└──────────────────────────────────────────────────────────────────────┘
```

### 8.2 关键实现决策

| 决策 | 方案 | 理由 |
|---|---|---|
| 清单格式 | 服务器对下载器返回**行式 KV 格式**（`format=kv`），非 JSON | bat/sh 解析 JSON 困难（Win7 PowerShell 2.0 无 `ConvertFrom-Json`）；KV 用 `for /f` / `while read` 一行解析 |
| 下载工具降级 | Win：`curl`（Win10+）→ `PowerShell Invoke-WebRequest`（Win7 兜底）；Linux：`curl` → `wget` | 不引入运行时依赖，覆盖 Win7 离线 |
| SHA-256 工具 | Win：`certutil -hashfile <f> SHA256`；Linux：`sha256sum <f>` | 均为系统自带 |
| 中文文件名 | URL 按 RFC 3986 百分号编码请求；落盘按 UTF-8 写名（Win 用 `chcp 65001` 切到 UTF-8 代码页） | 避免 bat 默认 GBK 导致中文乱码 |
| 断点续传 | `curl -C -` 自动续传；PowerShell 手动带 `Range: bytes=N-` 头 | 大文件中断后无需重头下 |
| 脚本生成 | 服务器 `/d/{toolId}` 用模板 + 变量替换生成脚本（非编译） | 零编译成本，可热更新下载器模板 |
| 错误隔离 | 单文件失败重试 3 次耗尽即整体终止，不继续后续文件 | 避免部分下载造成本体不完整 |

### 8.3 清单 KV 格式（服务器 `/api/bootstrap/{toolId}?format=kv` 响应）

```
TOOL_ID=recorder
VERSION=1.2.0
PLATFORM=win
START_COMMAND=java -jar recorder.jar
TOTAL_SIZE=30241280
FILE_COUNT=3
FILE_1=recorder.jar|a3f5e21c9b8e7d6a4f3e2b1c0d9e8f7a6b5c4d3e2f1|28672000
FILE_2=config/默认参数.properties|9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a|512
FILE_3=启动.bat|b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0|256
```

- 字段分隔：`=` 分 K/V，文件行用 `|` 分 `path|sha256|size`。
- 文件路径可含中文与子目录（`config/默认参数.properties`），下载器按相对路径在工作目录下创建子目录。

### 8.4 `install.bat` 骨架（Windows）

```bat
@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM === 内嵌变量（服务器动态注入） ===
set "TOOL_ID=recorder"
set "SERVER_URL=http://10.0.0.5:8090"
set "PLATFORM=auto"

REM === 解析命令行参数 ===
set "INSTALL_DIR="
set "NO_START=0"
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--no-start" (set "NO_START=1" & shift & goto parse)
echo %~1 | findstr /r "^--dir=" >nul && (set "INSTALL_DIR=%~1" & set "INSTALL_DIR=!INSTALL_ID:~6!" & shift & goto parse)
echo %~1 | findstr /r "^--platform=" >nul && (set "PLATFORM=%~1" & set "PLATFORM=!PLATFORM:~11!" & shift & goto parse)
shift
goto parse
:parsed
if "!INSTALL_DIR!"=="" set "INSTALL_DIR=.\%TOOL_ID%%"

REM === 平台检测 ===
if /i "%PLATFORM%"=="auto" set "PLATFORM=win"

REM === 选择下载工具 ===
where curl >nul 2>nul && (set "DL=curl" & goto dlready)
where powershell >nul 2>nul && (set "DL=ps" & goto dlready)
echo [ERR] 未找到 curl 或 powershell，无法下载。& exit /b 6
:dlready

REM === 创建工作目录 ===
mkdir "%INSTALL_DIR%" 2>nul
if not exist "%INSTALL_DIR%" (echo [ERR] 创建目录失败 & exit /b 4)

REM === 拉取清单 ===
set "MANIFEST=%INSTALL_DIR%\.manifest.kv"
set "BOOT_URL=%SERVER_URL%/api/bootstrap/%TOOL_ID%?platform=%PLATFORM%^&format=kv"
echo [INFO] 拉取清单: %BOOT_URL%
call :download "%BOOT_URL%" "%MANIFEST%" || (echo [ERR] 拉取清单失败 & exit /b 1)

REM === 解析清单 ===
for /f "usebackq tokens=1,* delims==" %%a in ("%MANIFEST%") do (
  if "%%a"=="VERSION" set "VER=%%b"
  if "%%a"=="START_COMMAND" set "START_CMD=%%b"
  if "%%a"=="FILE_COUNT" set "FILE_COUNT=%%b"
)
echo [INFO] 版本 %VER%  共 %FILE_COUNT% 个文件

REM === 逐文件下载 ===
set "i=0"
:fileloop
set /a "i+=1"
if !i! gtr %FILE_COUNT% goto filedone
for /f "tokens=1,2,3 delims=|" %%a in ('findstr /b "FILE_!i!=" "%MANIFEST%"') do (
  set "FNAME=%%a"
  set "FSHA=%%b"
  set "FSIZE=%%c"
)
REM 去掉 FILE_N= 前缀
set "FNAME=!FNAME:*=!"

set "DEST=%INSTALL_DIR%\!FNAME!"
REM 创建子目录
for %%I in ("!DEST!") do mkdir "%%~dpI" 2>nul

echo [INFO] 下载 [!i!/%FILE_COUNT%] !FNAME! ...
set "FILE_URL=%SERVER_URL%/api/file/tool/%TOOL_ID%/%VER%/!FNAME!"
call :download "!FILE_URL!" "!DEST!" || (echo [ERR] 下载失败 !FNAME! & exit /b 2)

REM SHA-256 校验
for /f "skip=3 tokens=2 delims= " %%h in ('certutil -hashfile "!DEST!" SHA256') do (
  set "ACTUAL=%%h" & goto chksum
)
:chksum
if /i "!ACTUAL!" neq "!FSHA!" (echo [ERR] 校验失败 !FNAME! & exit /b 3)
echo [INFO] 校验通过 !FNAME!
goto fileloop
:filedone

REM === 写日志 ===
echo [%date% %time%] 安装完成 tool=%TOOL_ID% ver=%VER% dir=%INSTALL_DIR% > "%INSTALL_DIR%\install_%date:~0,4%%date:~5,2%%date:~8,2%.log"

REM === 启动 ===
if "%NO_START%"=="1" (echo [INFO] --no-start 跳过启动 & exit /b 0)
echo %START_CMD% | findstr /i "java" >nul && (
  where java >nul 2>nul || (echo [ERR] 未检测到 Java，请先安装 JDK 1.8 & exit /b 5)
)
echo [INFO] 启动: %START_CMD%
cd /d "%INSTALL_DIR%"
cmd /c "%START_CMD%"
exit /b %errorlevel%

REM === 下载子程序（含重试与断点续传） ===
:download
set "URL=%~1"
set "OUT=%~2"
set "retry=0"
:retry
if "%DL%"=="curl" (
  curl -L --retry 3 -C - -o "%OUT%" "%URL%"
) else (
  REM PowerShell 兜底（带 Range 续传）
  powershell -Command "$h=@{}; if(Test-Path '%OUT%'){$h.Range='bytes='+([IO.FileInfo]'%OUT%').Length+'-'}; IWR -Uri '%URL%' -Headers $h -OutFile '%OUT%' -UseBasicParsing"
)
if %errorlevel%==0 exit /b 0
set /a "retry+=1"
if !retry! lss 3 (
  echo [WARN] 第 !retry! 次失败，重试中...
  powershell -Command "Start-Sleep -Seconds (2 * [Math]::Pow(2,!retry-1!))" 2>nul || ping -n 3 127.0.0.1 >nul
  goto retry
)
exit /b 1
```

### 8.5 `install.sh` 骨架（Linux）

```sh
#!/usr/bin/env bash
set -euo pipefail

# === 内嵌变量（服务器动态注入） ===
TOOL_ID="recorder"
SERVER_URL="http://10.0.0.5:8090"
PLATFORM="auto"

# === 解析命令行参数 ===
INSTALL_DIR=""
NO_START=0
for arg in "$@"; do
  case "$arg" in
    --dir=*)        INSTALL_DIR="${arg#--dir=}" ;;
    --platform=*)   PLATFORM="${arg#--platform=}" ;;
    --no-start)     NO_START=1 ;;
    *) echo "[ERR] 未知参数: $arg"; exit 7 ;;
  esac
done
[ -z "$INSTALL_DIR" ] && INSTALL_DIR="./$TOOL_ID"

# === 平台检测 ===
[ "$PLATFORM" = "auto" ] && PLATFORM="linux"

# === 选择下载工具 ===
if command -v curl >/dev/null 2>&1; then DL="curl"
elif command -v wget >/dev/null 2>&1; then DL="wget"
else echo "[ERR] 未找到 curl/wget"; exit 6; fi

# === 工作目录 ===
mkdir -p "$INSTALL_DIR" || { echo "[ERR] 创建目录失败"; exit 4; }

# === 拉取清单 ===
MANIFEST="$INSTALL_DIR/.manifest.kv"
BOOT_URL="$SERVER_URL/api/bootstrap/$TOOL_ID?platform=$PLATFORM&format=kv"
echo "[INFO] 拉取清单: $BOOT_URL"
download "$BOOT_URL" "$MANIFEST" || { echo "[ERR] 拉取清单失败"; exit 1; }

# === 解析清单 ===
declare -a F_PATH F_SHA F_SIZE
FILE_COUNT=0
while IFS='=' read -r k v; do
  case "$k" in
    VERSION)        VER="$v" ;;
    START_COMMAND)  START_CMD="$v" ;;
    FILE_COUNT)     FILE_COUNT="$v" ;;
    FILE_*)
      idx="${k#FILE_}"
      IFS='|' read -r p s z <<< "$v"
      F_PATH[idx]="$p"; F_SHA[idx]="$s"; F_SIZE[idx]="$z"
      ;;
  esac
done < "$MANIFEST"
echo "[INFO] 版本 $VER  共 $FILE_COUNT 个文件"

# === 逐文件下载 ===
for ((i=1; i<=FILE_COUNT; i++)); do
  FNAME="${F_PATH[i]}"; DEST="$INSTALL_DIR/$FNAME"
  mkdir -p "$(dirname "$DEST")"
  # URL 编码文件名（含中文）
  ENC_NAME=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$FNAME" 2>/dev/null || echo "$FNAME")
  FILE_URL="$SERVER_URL/api/file/tool/$TOOL_ID/$VER/$ENC_NAME"
  echo "[INFO] 下载 [$i/$FILE_COUNT] $FNAME ..."
  download "$FILE_URL" "$DEST" || { echo "[ERR] 下载失败 $FNAME"; exit 2; }
  ACTUAL=$(sha256sum "$DEST" | awk '{print $1}')
  [ "$ACTUAL" = "${F_SHA[i]}" ] || { echo "[ERR] 校验失败 $FNAME"; exit 3; }
  echo "[INFO] 校验通过 $FNAME"
done

# === 日志 ===
LOG_FILE="$INSTALL_DIR/install_$(date +%Y%m%d_%H%M%S).log"
echo "[$(date '+%F %T')] 安装完成 tool=$TOOL_ID ver=$VER dir=$INSTALL_DIR" > "$LOG_FILE"

# === 启动 ===
[ "$NO_START" = "1" ] && { echo "[INFO] --no-start 跳过启动"; exit 0; }
case "$START_CMD" in
  *java*) command -v java >/dev/null || { echo "[ERR] 未检测到 Java，请先安装 JDK"; exit 5; } ;;
esac
echo "[INFO] 启动: $START_CMD"
cd "$INSTALL_DIR"
exec $START_CMD

# === 下载函数（含重试与断点续传） ===
download() {
  local url="$1" out="$2" retry=0 rc
  while :; do
    if [ "$DL" = "curl" ]; then
      curl -fsSL --retry 3 -C - -o "$out" "$url" && return 0
    else
      wget -c -O "$out" "$url" && return 0
    fi
    rc=$?
    retry=$((retry+1))
    [ "$retry" -lt 3 ] || { echo "[WARN] 重试 $retry/3 失败"; return $rc; }
    sleep $((2 * (2 ** (retry-1))))
  done
}
```

> 注：`download` 函数需在脚本中先定义后调用（bash 函数需前置声明），实际部署时将函数置于脚本顶部或使用 `source` 引入。上述骨架为逻辑示意。

### 8.6 错误码定义

| 退出码 | 含义 | 处理建议 |
|---|---|---|
| 0 | 成功 | — |
| 1 | 拉取安装清单失败 | 检查 `SERVER_URL` 可达性、toolId 是否存在、网络 |
| 2 | 文件下载失败（重试耗尽） | 查看网络/磁盘空间，重跑会断点续传 |
| 3 | SHA-256 校验失败 | 服务器文件可能损坏或被篡改，联系管理员 |
| 4 | 工作目录创建失败 | 检查写权限/路径合法性 |
| 5 | 未检测到 Java | 先安装 JDK 1.8 再重试，或加 `--no-start` 仅下载 |
| 6 | 无可用下载工具 | 安装 curl 或 PowerShell |
| 7 | 参数错误 | 查看下载器帮助 |

### 8.7 服务器端配合实现

| 路由 | 实现要点 |
|---|---|
| `GET /d/{toolId}` | 1. 查 `tool` 表确认 toolId 存在；2. 按 `User-Agent` 或 `?platform=` 判定平台；3. 读取对应平台下载器模板（`install.bat.tmpl` / `install.sh.tmpl`，随 jar 内置资源）；4. 字符串替换 `{{TOOL_ID}}`/`{{SERVER_URL}}`/`{{PLATFORM}}`；5. 设 `Content-Type: text/plain; charset=utf-8`、`Content-Disposition: attachment; filename="install.bat"`；6. 写一条 `download_stat(stage=BOOTSTRAP)` |
| `GET /api/bootstrap/{toolId}?format=kv` | 1. 查 `tool_version` 表取该 toolId 最新 PUBLISHED 版本（按 platform）；2. 拼装 KV 行：`TOOL_ID`/`VERSION`/`PLATFORM`/`START_COMMAND`/`TOTAL_SIZE`/`FILE_COUNT` + 逐行 `FILE_N=path\|sha256\|size`；3. 返回 `text/plain`，UTF-8；4. 无可用版本返回 404 |
| `GET /api/file/tool/{toolId}/{version}/{filename}` | 复用现有 `/api/file/...` 的 Range + 中文文件名逻辑；下载完成写 `download_stat(stage=FILE)` |

### 8.8 下载器模板内置与升级

- 下载器模板（`.tmpl`）作为资源文件打进服务器 jar（`resources/bootstrap/install.bat.tmpl`、`install.sh.tmpl`），服务器启动时加载到内存。
- 模板可随服务器升级而更新（如修复 bug、增加参数），无需重新分发已发出的旧下载器——下次有人访问 `/d/{toolId}` 自动用新模板。
- 已落地的客户端本体内嵌升级 SDK，后续走热升级闭环，与下载器模板演进解耦。
