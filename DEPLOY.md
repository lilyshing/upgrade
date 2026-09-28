# 部署指南

> 适用场景：将「自动升级管理后台」部署到 Windows / Win7 64 位离线机器。
> 已在本机验证：`mvn -o -s settings.xml clean package -DskipTests` → `BUILD SUCCESS`，本地仓库依赖完整。
> 参考同构项目 `recorder` 的 `DEPLOY-WIN7-CHECKLIST.md`。

---

## 第一阶段：源机准备（联网机器上完成）

### 1.1 确认离线构建可行（必做，否则迁移后无法构建）

- [ ] 在源机执行离线构建验证：
      `mvn -o -s settings.xml clean package -DskipTests`
- [ ] 结果为 `BUILD SUCCESS`（无 `Could not resolve` / `Downloading`）—— 证明本地仓库完整，可迁移
- [ ] 若失败提示缺依赖：回到联网状态执行 `mvn -s settings.xml dependency:go-offline` 补全后重试

### 1.2 确认待复制项的实际路径与大小

| 复制项 | 源机路径 | 预计大小 | 用途 |
|---|---|---|---|
| JDK 1.8 x64 | `D:\devbase\jdk\jdk1.8\JDK1.8` | ~250MB | 编译 + 运行 |
| Maven 3.8.8 | `D:\devbase\apache-maven-3.8.8` | ~10MB | 构建（仅开发需要） |
| 本地 Maven 仓库 | `D:\devbase\repository` | ~60MB | 全部依赖（sqlite-jdbc/bcrypt/shade） |
| 项目源码 | `d:\workspace\aipro\upgrade\` | 小 | 开发用 |
| upgrade-server.jar（仅运行） | `upgrade-server\target\upgrade-server.jar` | ~13MB | 仅运行、不开发时用 |

- [ ] 确认上述路径存在
- [ ] 确认 `repository` 内 `org\xerial\sqlite-jdbc` 子目录存在（含各平台 native 库，~3MB）
- [ ] 确认 `repository` 内 `org\mindrot\jbcrypt` 子目录存在

### 1.3 清理与打包

- [ ] 关闭运行中的服务器进程（避免 jar 被锁）：`Ctrl+C` 或 `taskkill /F /IM java.exe`
- [ ] 排除不需要的目录：`target\`（构建产物，目标机重新生成）、`.idea\`/`.vscode\`、`upgrade-server\upgrade.db`（测试数据库）、`upgrade-server\toolfiles\`、`upgrade-server\versionfiles\`
- [ ] 将上述项 + 源码复制到 U盘/移动硬盘（建议保留原 `D:\devbase\...` 路径结构，省去改配置）

> 省心方案：直接整个 `D:\devbase\` 复制（含 JDK+Maven+仓库），再单独复制源码目录。

---

## 第二阶段：目标机（Win7）环境搭建

### 2.1 系统前提

- [ ] Win7 为 **64 位**系统（32 位会导致 SQLite native 库加载失败）
- [ ] 已安装必要的系统补丁（Win7 SP1 + KB2999226 等 VC++ 运行库，避免 Java 启动异常）
- [ ] 目标机确实断网（用 `ping baidu.com` 验证不通 —— 确认需走离线流程）

### 2.2 复制到目标机

- [ ] JDK → 保持 `D:\devbase\jdk\jdk1.8\JDK1.8`（路径不变则无需改任何配置）
- [ ] Maven → 保持 `D:\devbase\apache-maven-3.8.8`（仅开发需要）
- [ ] 本地仓库 → 保持 `D:\devbase\repository`
- [ ] 源码 → 保持 `d:\workspace\aipro\upgrade\`

> 若必须改路径（如目标盘符为 E:）：所有路径统一替换后，还需修改 `settings.xml` 里的 `<localRepository>`。

### 2.3 配置环境变量（右键计算机→属性→高级系统设置→环境变量）

- [ ] 新建系统变量 `JAVA_HOME` = `D:\devbase\jdk\jdk1.8\JDK1.8`
- [ ] 新建系统变量 `MAVEN_HOME` = `D:\devbase\apache-maven-3.8.8`（仅开发需要）
- [ ] 修改系统变量 `PATH`，追加：`;%JAVA_HOME%\bin;%MAVEN_HOME%\bin`
- [ ] 重开命令行窗口使环境变量生效

### 2.4 环境变量验证

- [ ] `java -version` → 显示 `1.8.0_xxx`（64-Bit Server VM）
- [ ] `javac -version` → 显示 `1.8.0_xxx`（确认 JDK 非仅 JRE，开发用）
- [ ] `mvn -version` → 显示 `Apache Maven 3.8.8`（仅开发需要）

---

## 第三阶段：离线构建验证

### 3.1 执行离线构建（关键：必须带 `-o`）

- [ ] 进入源码目录：`cd d:\workspace\aipro\upgrade`
- [ ] 执行：`mvn -o -s settings.xml clean package -DskipTests`
- [ ] 结果：`BUILD SUCCESS`
- [ ] 产物存在：
      - `upgrade-client\target\upgrade-client.jar`（SDK，给宿主引用）
      - `upgrade-server\target\upgrade-server.jar`（服务器 fat jar，约 13MB）

### 3.2 若构建失败的排查

| 报错关键字 | 原因 | 处理 |
|---|---|---|
| `Could not resolve dependencies` / `Could not find artifact` | 本地仓库缺依赖 | 回源机 `mvn dependency:go-offline` 补全仓库后重新复制 |
| `Failed to delete upgrade-server.jar` | jar 被运行中的 java 进程占用 | 先 `taskkill /F /IM java.exe` 或停掉服务器再构建 |
| `No compiler is provided` 或 `Unable to find javac` | 用的是 JRE 不是 JDK | 确认 `JAVA_HOME` 指向 JDK（含 `javac.exe`） |
| `mvn 不是内部或外部命令` | PATH 未生效 | 检查环境变量并重开命令行 |
| `UnsatisfiedLinkError` / 原生库加载失败 | 目标机 32 位 JDK | 换装 64 位 JDK 1.8 |

---

## 第四阶段：运行验证

### 4.1 启动服务器

- [ ] 进入产物目录：`cd d:\workspace\aipro\upgrade\upgrade-server`
- [ ] 启动：`java -jar target\upgrade-server.jar`
      （默认端口 8090，监听 0.0.0.0；自定义：`java -jar target\upgrade-server.jar 8090 0.0.0.0`）
- [ ] 控制台输出包含：
      ```
      自动升级管理后台已启动
      监听: 0.0.0.0:8090
      Web 控制台: http://127.0.0.1:8090/
      默认账号: admin / admin (首次登录强制改密)
      ```
- [ ] 首次启动自动在同目录生成：
      - `upgrade.db`（SQLite 数据库文件，含 9 张表 + 默认 admin）
      - `toolfiles\`（工具本体存储）
      - `versionfiles\`（SDK 版本文件存储）

### 4.2 后台访问与初始化

- [ ] 浏览器打开：`http://127.0.0.1:8090/`
- [ ] 用 `admin / admin` 登录 → 提示"请先修改初始密码"
- [ ] 改密后重新登录，进入仪表盘
- [ ] 注册工具（如 `toolId=recorder`，名称=Recorder，启动命令=`java -jar recorder.jar`）
- [ ] 上传工具本体（zip 打包，含若干待分发文件，每平台一版本）
- [ ] 发布版本 → 复制分发链接 `http://<host>:8090/d/recorder`

### 4.3 下载器脚本验证

- [ ] 访问 `http://127.0.0.1:8090/d/recorder?platform=win` → 下载 `install.bat`
- [ ] 用文本编辑器打开 `install.bat`，确认开头内嵌：
      ```bat
      set TOOL_ID=recorder
      set SERVER_URL=http://127.0.0.1:8090
      ```
- [ ] 在另一台机器执行 `install.bat`，验证能拉取清单 + 下载本体 + SHA-256 校验 + 启动

### 4.4 客户端 SDK 集成验证（宿主项目）

- [ ] 宿主 `pom.xml` 加入依赖：
      ```xml
      <dependency>
          <groupId>com.aipro</groupId>
          <artifactId>upgrade-client</artifactId>
          <version>1.0.0</version>
      </dependency>
      ```
- [ ] 宿主工作目录放 `upgrade.properties`：
      ```properties
      upgrade.server.url=http://your-server:8090
      upgrade.strategy=ASK
      upgrade.current.version=1.0.0
      ```
- [ ] 宿主启动代码加：
      ```java
      UpgradeClient.getInstance().checkAndUpgrade();
      ```
- [ ] 服务器后台发布一个新版本 → 重启宿主 → 弹窗询问升级 → 下载替换 → 重启

---

## 第五阶段：生产部署

### 5.1 反向代理（可选）

如需通过 Nginx 反代对外，需覆盖下载器分发 URL：

- [ ] 启动时设环境变量：`set UPGRADE_SERVER_BASE_URL=https://upgrade.your-domain.com`
- [ ] Nginx 配置：
      ```nginx
      server {
          listen 443 ssl;
          server_name upgrade.your-domain.com;
          location / {
              proxy_pass http://127.0.0.1:8090;
              proxy_set_header Host $host;
              proxy_set_header X-Forwarded-For $remote_addr;
          }
      }
      ```
- [ ] 重启服务器，验证 `/d/{toolId}` 返回的下载器内嵌 `SERVER_URL=https://upgrade.your-domain.com`

### 5.2 数据备份

- [ ] 定期备份 `upgrade.db`（SQLite 单文件，停服后直接复制即可）
- [ ] 备份 `toolfiles\` 与 `versionfiles\` 目录（工具本体与 SDK 版本文件）

### 5.3 升级服务器自身

- [ ] 停服：`Ctrl+C` 或 `taskkill /F /IM java.exe`
- [ ] 备份 `upgrade.db`、`toolfiles\`、`versionfiles\`
- [ ] 替换 `upgrade-server.jar`
- [ ] 重启：`java -jar target\upgrade-server.jar`
- [ ] 启动时自动建表，已存在的表保留数据（DDL `CREATE TABLE IF NOT EXISTS`）

---

## 关键命令速查

```powershell
# 离线构建（断网必加 -o）
cd d:\workspace\aipro\upgrade
mvn -o -s settings.xml clean package -DskipTests

# 启动服务器
cd d:\workspace\aipro\upgrade\upgrade-server
java -jar target\upgrade-server.jar                       # 默认 8090
java -jar target\upgrade-server.jar 18090 127.0.0.1       # 自定义端口/地址

# 反代场景需覆盖对外 URL
set UPGRADE_SERVER_BASE_URL=https://upgrade.your-domain.com
java -jar target\upgrade-server.jar

# 进程占用导致无法构建时
taskkill /F /IM java.exe      # 谨慎：会结束所有 java 进程

# 跑 E2E 回归测试
cd d:\workspace\aipro\upgrade
# 先在另一终端启动服务器: java -jar upgrade-server\target\upgrade-server.jar 18090 127.0.0.1
powershell -NoProfile -ExecutionPolicy Bypass -File e2e-test.ps1
```

## 默认配置

| 项 | 默认值 |
|---|---|
| 服务器端口 | 8090 |
| 服务器监听地址 | 0.0.0.0 |
| 数据库文件 | `upgrade-server\upgrade.db`（随 jar 同目录） |
| 工具本体根目录 | `upgrade-server\toolfiles\` |
| SDK 版本根目录 | `upgrade-server\versionfiles\` |
| 默认账号 | admin / admin（首登强制改密） |
| 会话 TTL | 12 小时 |
| 下载器分发 URL | `http://<host>:<port>`（可被 `UPGRADE_SERVER_BASE_URL` 覆盖） |

## 客户端 SDK 配置（`upgrade.properties`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `upgrade.server.url` | `http://127.0.0.1:8090` | 服务器 URL |
| `upgrade.strategy` | `ASK` | FORCE/ASK/NONE |
| `upgrade.client.id` | 自动生成 UUID | 客户端唯一标识，自动写入配置 |
| `upgrade.retry.max` | 3 | 下载失败重试次数 |
| `upgrade.retry.backoff.ms` | 2000 | 重试退避基数（毫秒，指数退避） |
| `upgrade.download.dir` | `./upgrade/tmp` | 下载临时目录 |
| `upgrade.backup.dir` | `./upgrade/backup` | 备份目录（旧版本） |
| `upgrade.current.version` | `0.0.0` | 当前版本号 fallback（优先读 version.json） |
| `upgrade.skip.version` | （空） | 用户选择跳过的版本 |
| `upgrade.prompt.impl` | （空=默认 Swing） | 自定义交互实现类全名 |

本地文件（宿主工作目录下）：
- `version.json` — 服务器自动生成的版本元信息
- `file_manifest.json` — 本次更新文件清单
- `update_log.txt` — 升级历史
- `upgrade/backup/` — 上一版本文件备份

## 注意事项

1. **必须 64 位**：JDK 与 Win7 系统都要 64 位，否则 SQLite native 库加载失败。
2. **`-o` 不能少**：断网构建时缺 `-o` 会尝试联网下载而失败。
3. **jar 被锁**：构建/复制前确保无 java 进程占用 `upgrade-server.jar`。
4. **路径一致性**：保持 `D:\devbase\...` 原路径最省心；改路径需同步改 `settings.xml` 的 `<localRepository>`。
5. **SQLite native 库**：`org.xerial:sqlite-jdbc` 自带各平台 native 库，运行时从 jar 解压到 `java.io.tmpdir`；若该目录不可写，加 JVM 参数 `-Dorg.sqlite.tmpdir=D:\tmp` 指定可写目录。
6. **首登改密**：admin 默认密码 admin，首次登录强制改密（`must_change_pwd=1`），未改密前所有后台 API 被拒（除 `/api/auth/me`）。
7. **灰度按工具独立**：每个工具的灰度策略在 `push_policy` 表一行，互不影响。
8. **路由前缀冲突**：`/api/file/tool/` 必须在 `/api/file/` 之前匹配（代码已处理）。
