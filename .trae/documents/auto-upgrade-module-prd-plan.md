# 自动升级更新模块 PRD 编写计划

> 本文件是 Plan Mode 下的实施计划。用户批准后，将在执行阶段把完整 PRD 落地到 `d:\workspace\aipro\upgrade\PRD.md`。

---

## 一、任务摘要

为「自动升级更新模块」工具编写一份清晰、可执行的产品需求文档（PRD），输出**功能清单 / 界面说明 / 数据说明**三大块，Markdown 格式。该模块分两部分：

- **客户端模块**：Java SDK，以 Maven 依赖形式被主模块（宿主程序）引用，jar 包形态。
- **服务器端版本管理工具**：独立部署的 Web 服务，含管理后台 + SQLite3 数据库。

## 二、当前状态分析

- 工作目录 `d:\workspace\aipro\upgrade` 为空，从零开始。
- 同级 `recorder` 项目（JDK 1.8 + Maven + fat jar，Win7 离线部署）作为技术栈参考。
- 用户约束（来自 user_profile）：中文沟通、代码中文注释、JDK 1.8 x64、Windows 离线场景、交付前打包 + 文档 + 注释齐全。

## 三、关键决策（已与用户确认）

| 决策项 | 选择 | 说明 |
|---|---|---|
| 服务器端管理界面 | **Web 管理后台** | 浏览器访问，跨平台远程管理 |
| 数据存储 | **SQLite3 嵌入式数据库** | 单文件 `.db`、零安装、跨语言通用；通过 `sqlite-jdbc` 驱动（含 native dll）访问，Win7 离线场景需确保临时目录可解压 native 库 |

## 四、PRD 文档结构（执行阶段按此落地）

PRD 将写入 `d:\workspace\aipro\upgrade\PRD.md`，结构如下：

### 1. 概述
- 背景与目标
- 名词定义（主模块/宿主程序、客户端 SDK、服务器端版本管理工具、版本包、升级策略、灰度）
- 系统架构图（文字版：宿主程序 → 客户端 SDK → HTTP → 服务器端 Web 服务 + H2）
- 技术约束：JDK 1.8、Maven fat jar、Windows / Win7 离线、中文注释

### 2. 功能清单

#### 2.1 客户端模块（SDK）
| 编号 | 功能 | 描述 | 对应需求 |
|---|---|---|---|
| F-C-01 | 升级策略配置 | 通过 `upgrade.properties` 配置 `FORCE / ASK / NONE` 三种策略 | 需求 2 |
| F-C-02 | 启动版本检查 | 宿主启动时调用 SDK API 拉取服务器最新版本元信息 | 需求 3 |
| F-C-03 | 版本比对 | 客户端本地版本号 vs 服务器版本号，语义化版本比较 | 需求 3 |
| F-C-04 | 下载与重试 | HTTP 下载更新包，失败自动重试（默认 3 次，指数退避），支持断点续传 | 需求 4 |
| F-C-05 | 中文文件名支持 | 下载 URL 与本地落盘均按 UTF-8 处理中文文件名，避免乱码 | 需求 4 |
| F-C-06 | 文件占用处理与自动重启 | 通过**独立重启器**（bat 脚本，随 SDK 一并提供模板）解决 jar 文件占用：宿主退出 → 替换文件 → 拉起新版宿主 | 需求 5 |
| F-C-07 | 本地升级日志与文件清单 | 本地保存 `update_log.txt`（升级历史）与 `file_manifest.json`（本次文件更新清单） | 需求 5 |
| F-C-08 | 宿主集成 API | 提供 `UpgradeClient.checkAndUpgrade()` 等门面方法，宿主一行代码接入 | 需求 1、2 |
| F-C-09 | 询问升级交互 | ASK 策略下，提供默认 Swing 弹窗 + 可由宿主自定义回调 | 需求 2 |
| F-C-10 | 更新结果上报 | 更新成功/失败后回调服务器，写入更新记录 | 需求 7 |

#### 2.2 服务器端版本管理工具（Web 服务）
| 编号 | 功能 | 描述 | 对应需求 |
|---|---|---|---|
| F-S-01 | 更新包上传 | Web 后台上传 zip 更新包，自动解包、计算每个文件 SHA-256，生成 `version.json`（含版本号、文件清单、SHA-256、生成时间） | 需求 6（更优方案） |
| F-S-02 | 版本发布与下线 | 版本状态 `DRAFT / PUBLISHED / OFFLINE`，仅 PUBLISHED 推送给客户端 | 需求 6 |
| F-S-03 | 客户端版本查询 API | `GET /api/version/latest` 返回最新 PUBLISHED 版本元信息 | 需求 3 |
| F-S-04 | 文件下载 API | `GET /api/file/{version}/{filename}` 支持断点续传、中文文件名（Content-Disposition 按 RFC 5987 编码） | 需求 4 |
| F-S-05 | 客户端更新记录 | 接收客户端上报（ip、旧版本、新版本、更新时间、结果），写入 H2 `client_update_record` 表，可在后台查询 | 需求 7 |
| F-S-06 | 灰度推送数量控制 | 全局阈值（如 10），达到后停止向新客户端推送新版本；后台可一键放开/调整阈值 | 需求 8 |
| F-S-07 | 灰度白名单 | 可配置 IP/客户端 ID 白名单，白名单内不受阈值限制（用于试点验证） | 需求 8（增强） |
| F-S-08 | Web 管理后台 | 浏览器访问的页面集（见界面说明） | 需求 6、7、8 |
| F-S-09 | 版本回滚 | 后台一键将某版本置为 OFFLINE，客户端下次检查自动回退到上一可用版本 | 需求 6、8 |

### 3. 界面说明

#### 3.1 客户端
- 默认无独立界面（SDK 嵌入宿主）。
- **ASK 策略默认弹窗**（Swing `JOptionPane`）：标题、新版本号、更新说明、文件大小、"立即更新 / 稍后 / 跳过本次"按钮。
- 宿主可通过实现 `UpgradePrompt` 接口自定义交互（如 JavaFX / Web UI）。
- **强制升级（FORCE）**：弹窗仅显示进度，无取消按钮，宿主阻塞等待。
- 下载进度条：百分比 + 速度 + 剩余时间，重试时提示"第 N 次重试中"。

#### 3.2 服务器端 Web 管理后台（页面清单）
| 页面 | 路径 | 核心元素 |
|---|---|---|
| 登录页 | `/login` | 用户名/密码（默认 admin/admin，首次登录强制改密） |
| 仪表盘 | `/` | 当前最新版本、已发布版本数、累计更新客户端数、今日更新数、灰度状态卡片 |
| 版本列表 | `/versions` | 表格：版本号、状态、生成时间、文件数、总大小、更新客户端数；操作：查看/下线/回滚 |
| 上传新版本 | `/versions/new` | 上传 zip、填写版本号（语义化）、更新说明、目标客户端范围；提交后自动生成 version.json |
| 版本详情 | `/versions/{id}` | 文件清单（路径、SHA-256、大小）、更新记录子表、状态变更历史 |
| 客户端更新记录 | `/records` | 表格：客户端 IP/ID、旧版本、新版本、更新时间、结果（成功/失败）、耗时；支持按版本/IP/时间筛选与导出 CSV |
| 灰度控制 | `/policy` | 全局开关、推送阈值（默认 10）、当前已更新数、剩余配额、白名单 IP/ID 列表、一键放开/暂停按钮 |

> 界面风格：简洁后台风（参考 Bootstrap 风格的纯 HTML+原生 CSS，避免重前端框架依赖，便于离线部署）。

### 4. 数据说明

#### 4.1 客户端配置（`upgrade.properties`，随宿主打包）
| 配置项 | 说明 | 默认值 |
|---|---|---|
| `upgrade.server.url` | 服务器端基础 URL | `http://127.0.0.1:8090` |
| `upgrade.strategy` | 升级策略：FORCE/ASK/NONE | `ASK` |
| `upgrade.client.id` | 客户端唯一标识（缺失则用 IP） | 自动生成 UUID |
| `upgrade.retry.max` | 下载失败最大重试次数 | `3` |
| `upgrade.retry.backoff.ms` | 重试退避基数（指数） | `2000` |
| `upgrade.download.dir` | 下载临时目录 | `./upgrade/tmp` |
| `upgrade.backup.dir` | 回滚备份目录 | `./upgrade/backup` |
| `upgrade.current.version` | 当前版本号（自动维护） | 宿主初始版本 |

#### 4.2 服务器端 SQLite3 数据表
- `version`（版本表）：id、version_no、status、release_note、file_count、total_size、created_at、published_at
- `version_file`（版本文件清单）：id、version_id、file_path、sha256、size、created_at
- `client_update_record`（客户端更新记录）：id、client_id、client_ip、old_version、new_version、update_time、result（SUCCESS/FAIL）、fail_reason、duration_ms
- `push_policy`（灰度策略表，单行配置）：id、enabled、threshold、current_count、whitelist（CSV）
- `admin_user`（后台用户）：id、username、password_hash、created_at

#### 4.3 客户端本地文件
- `upgrade.properties`：配置
- `version.json`：当前版本元信息（版本号、SHA-256、上次更新时间）
- `update_log.txt`：升级历史（时间、旧版本→新版本、结果、耗时）
- `file_manifest.json`：最近一次文件更新清单（路径、SHA-256、旧SHA-256）
- `backup/`：上一版本文件备份，用于回滚

#### 4.4 关键接口（客户端 ↔ 服务器）
| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/version/latest` | 拉取最新 PUBLISHED 版本元信息 |
| GET | `/api/file/{version}/{filename}` | 下载指定文件（支持 Range、UTF-8 中文文件名） |
| POST | `/api/record/update` | 客户端上报更新结果 |
| GET | `/api/policy/status` | 查询当前灰度是否放开（客户端检查时若已关闭则跳过） |

### 5. 非功能性需求
- **安全**：更新包文件 SHA-256 校验，下载后逐文件比对，不一致则丢弃并重试；后台登录密码 BCrypt 哈希；可选 HTTPS。
- **可靠性**：下载断点续传、重试指数退避、原子替换（新文件先落临时目录，校验通过后 rename）。
- **兼容性**：JDK 1.8、Windows / Win7 离线、服务器端仅依赖 `sqlite-jdbc`（带 native dll，打进 fat jar，运行时解压到临时目录，需保证该目录可写）+ 轻量 HTTP 框架，无其他外部组件。
- **可观测**：客户端日志可配级别；服务器端访问日志与异常日志。
- **可回滚**：客户端保留上一版本备份，服务器端可下线问题版本触发客户端回退。

### 6. 假设与决策
1. **客户端为 SDK 模式**：以 Maven 依赖被宿主引用，jar 包形态（需求 1、2 已明确）。
2. **重启机制**：jar 文件占用无法在 JVM 内部替换，采用**独立重启器**（bat 脚本模板随 SDK 提供，宿主可定制）：宿主退出 → 脚本替换文件 → 拉起新版宿主。
3. **版本文件方案选 SHA-256 + version.json**：优于 md5+version.ini——SHA-256 抗碰撞性更强，JSON 结构化便于扩展（多文件、多平台、签名等），与 SQLite 表结构一致。
4. **服务器端 HTTP 框架**：轻量内嵌（候选：`com.sun.net.httpserver` 或 Jetty embedded），SQLite3 嵌入式（`sqlite-jdbc` 驱动 + native dll），统一打进 fat jar，零外部依赖。
5. **SQLite native 库加载**：`sqlite-jdbc` 内置各平台 native 库，运行时从 jar 解压到 `java.io.tmpdir` 或自定义 `org.sqlite.tmpdir` 目录；Win7 离线场景需确保该临时目录可写（参考 recorder 项目 javacpp 缓存处理：必要时用 `-Dorg.sqlite.tmpdir=D:\devbase\.sqlite` 指定可写目录）。
6. **灰度控制粒度**：默认全局配额（需求 8 原意），增强白名单用于试点。
7. **中文文件名**：URL 按 RFC 3986 百分号编码，HTTP `Content-Disposition` 按 RFC 5987 `filename*=UTF-8''` 编码。
8. **界面技术**：Web 后台用纯 HTML + 原生 CSS + 少量原生 JS，避免 Vue/React 等重前端，便于离线单 jar 部署。

### 7. 验证步骤（PRD 完整性自检）
- [ ] 三大块（功能清单 / 界面说明 / 数据说明）齐全且对应原始 8 条需求逐条覆盖。
- [ ] 每个功能编号可追溯到需求点。
- [ ] 数据表与配置项与功能描述一致（如灰度阈值字段存在于 push_policy）。
- [ ] 界面说明覆盖所有服务器端功能页，客户端交互（三种策略）描述完整。
- [ ] 非功能与假设对 Win7 离线 / JDK 1.8 兼容。

---

## 五、执行阶段动作

1. 读取本 plan 文件刷新上下文。
2. 在 `d:\workspace\aipro\upgrade\PRD.md` 写入完整 PRD（按上述 7 章 + 验证清单组织，中文撰写）。
3. 完成后向用户返回最终响应，不再调用 NotifyUser。
