/* 自动升级管理后台 - 前端逻辑（原生 JS，无框架依赖，PRD §3.2 风格） */
(function () {
  'use strict';

  // ============== 全局状态 ==============
  var TOKEN = localStorage.getItem('upg_token') || '';
  var CURRENT_USER = JSON.parse(localStorage.getItem('upg_user') || 'null');
  var CURRENT_ROUTE = '/';

  // ============== 工具函数 ==============
  function api(method, path, body, isBinary) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open(method, path, true);
      if (TOKEN) {
        xhr.setRequestHeader('X-Auth-Token', TOKEN);
      }
      if (body && !isBinary) {
        xhr.setRequestHeader('Content-Type', 'application/json');
      }
      xhr.onload = function () {
        var data;
        try {
          data = JSON.parse(xhr.responseText);
        } catch (e) {
          data = { success: false, message: '响应非 JSON' };
        }
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve(data);
        } else if (xhr.status === 401) {
          // 会话失效，跳登录
          clearAuth();
          renderLogin('会话已失效，请重新登录');
          reject(data);
        } else {
          reject(data);
        }
      };
      xhr.onerror = function () {
        reject({ success: false, message: '网络错误' });
      };
      var payload = body;
      if (body && !isBinary) {
        payload = JSON.stringify(body);
      }
      xhr.send(payload);
    });
  }

  function apiBinaryUpload(method, path, file, params) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      var url = path;
      if (params) {
        var qs = Object.keys(params).map(function (k) {
          return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]);
        }).join('&');
        if (qs) url += '?' + qs;
      }
      xhr.open(method, url, true);
      if (TOKEN) xhr.setRequestHeader('X-Auth-Token', TOKEN);
      xhr.onload = function () {
        var data;
        try { data = JSON.parse(xhr.responseText); } catch (e) { data = { success: false, message: '响应非 JSON' }; }
        if (xhr.status >= 200 && xhr.status < 300) resolve(data);
        else reject(data);
      };
      xhr.onerror = function () { reject({ success: false, message: '网络错误' }); };
      xhr.send(file);
    });
  }

  function setAuth(token, user) {
    TOKEN = token;
    CURRENT_USER = user;
    localStorage.setItem('upg_token', token);
    localStorage.setItem('upg_user', JSON.stringify(user));
  }

  function clearAuth() {
    TOKEN = '';
    CURRENT_USER = null;
    localStorage.removeItem('upg_token');
    localStorage.removeItem('upg_user');
  }

  function msg(el, text, type) {
    var div = document.createElement('div');
    div.className = 'message ' + (type || 'error');
    div.textContent = text;
    el.appendChild(div);
    setTimeout(function () { if (div.parentNode) div.parentNode.removeChild(div); }, 4000);
  }

  function tag(status) {
    var cls = { PUBLISHED: 'tag-published', DRAFT: 'tag-draft', OFFLINE: 'tag-offline',
                ADMIN: 'tag-admin', USER: 'tag-user', ACTIVE: 'tag-active', DISABLED: 'tag-disabled' }[status];
    return '<span class="tag ' + (cls || '') + '">' + status + '</span>';
  }

  function fmtSize(bytes) {
    if (!bytes) return '-';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

  function fmtTime(s) {
    if (!s) return '-';
    return s.replace('T', ' ').substring(0, 19);
  }

  // ============== 路由 ==============
  function navigate(path) {
    if (!path) path = '/';
    if (path !== '/login' && !TOKEN) {
      return renderLogin();
    }
    if (path === '/login' && TOKEN) {
      path = '/';
    }
    location.hash = path;
    CURRENT_ROUTE = path;
    render(path);
    window.scrollTo(0, 0);
  }

  // ============== 主入口 ==============
  function init() {
    window.addEventListener('hashchange', function () {
      var h = location.hash.substring(1) || '/';
      navigate(h);
    });
    var initial = location.hash.substring(1) || '/';
    if (!TOKEN || initial === '/login') {
      renderLogin();
      if (TOKEN && initial !== '/login') {
        navigate(initial);
      }
    } else {
      navigate(initial);
    }
  }

  // ============== 渲染 ==============
  function render(path) {
    var app = document.getElementById('app');
    app.innerHTML = '';
    if (path === '/login' || !TOKEN) {
      renderLogin();
      return;
    }
    renderShell(app, path);
  }

  function renderShell(app, path) {
    var shell = document.createElement('div');
    var isAdmin = CURRENT_USER && CURRENT_USER.role === 'ADMIN';
    var userName = (CURRENT_USER && CURRENT_USER.displayName) || (CURRENT_USER && CURRENT_USER.username) || '';
    var userRole = CURRENT_USER ? CURRENT_USER.role : '';
    var mustChange = CURRENT_USER && CURRENT_USER.mustChangePwd;

    var navHtml = '<div class="header"><h1>自动升级管理后台</h1><div class="user-area">当前用户：' + userName + ' ' + tag(userRole) + ' <a href="#/me" style="color:#fff;margin-left:12px">个人中心</a> <a href="#" id="logout-link" style="color:#fff;margin-left:12px">退出</a></div></div>';
    navHtml += '<div class="nav">';
    navHtml += '<a href="#/" class="' + (path === '/' ? 'active' : '') + '">仪表盘</a>';
    navHtml += '<a href="#/versions" class="' + (path.indexOf('/versions') === 0 ? 'active' : '') + '">版本列表</a>';
    navHtml += '<a href="#/versions/new" class="' + (path === '/versions/new' ? 'active' : '') + '">上传新版本</a>';
    navHtml += '<a href="#/records" class="' + (path === '/records' ? 'active' : '') + '">更新记录</a>';
    navHtml += '<a href="#/policy" class="' + (path === '/policy' ? 'active' : '') + '">灰度控制</a>';
    navHtml += '<a href="#/tools" class="' + (path.indexOf('/tools') === 0 ? 'active' : '') + '">工具列表</a>';
    if (isAdmin) {
      navHtml += '<a href="#/admin/users" class="' + (path === '/admin/users' ? 'active' : '') + '">用户管理</a>';
    }
    navHtml += '</div>';

    shell.innerHTML = navHtml + '<div class="container" id="page-container"></div>';
    app.appendChild(shell);

    document.getElementById('logout-link').addEventListener('click', function (e) {
      e.preventDefault();
      api('POST', '/api/auth/logout').then(clearAuth).catch(clearAuth).then(function () {
        navigate('/login');
      });
    });

    var container = document.getElementById('page-container');
    if (mustChange) {
      renderChangePasswordPage(container, true);
      return;
    }

    // 路由分发
    if (path === '/') renderDashboard(container);
    else if (path === '/versions') renderVersions(container);
    else if (path === '/versions/new') renderVersionUpload(container);
    else if (path.indexOf('/versions/') === 0) renderVersionDetail(container, path);
    else if (path === '/records') renderRecords(container);
    else if (path === '/policy') renderPolicy(container);
    else if (path === '/tools') renderTools(container);
    else if (path.indexOf('/tools/') === 0 && path.indexOf('/versions') > 0) renderToolVersions(container, path);
    else if (path.indexOf('/tools/') === 0 && path.indexOf('/distribute') > 0) renderToolDistribute(container, path);
    else if (path === '/admin/users') renderUsers(container);
    else if (path === '/me') renderMe(container);
    else container.innerHTML = '<div class="card"><h2>404</h2><p>页面未找到：<code>' + path + '</code></p></div>';
  }

  // ============== 登录页 ==============
  function renderLogin(hint) {
    var app = document.getElementById('app');
    app.innerHTML = '<div class="login-box"><h2>自动升级后台</h2><div id="login-msg"></div><div class="form-group"><label>用户名</label><input id="li-user" type="text" value="" placeholder="admin"></div><div class="form-group"><label>密码</label><input id="li-pass" type="password" placeholder="admin"></div><button id="li-btn">登录</button><p class="muted mt" style="text-align:center">默认账号 admin/admin，首次登录强制改密</p></div>';
    if (hint) msg(document.getElementById('login-msg'), hint, 'error');
    document.getElementById('li-btn').addEventListener('click', function () {
      var u = document.getElementById('li-user').value.trim();
      var p = document.getElementById('li-pass').value;
      api('POST', '/api/auth/login', { username: u, password: p }).then(function (res) {
        var d = res.data;
        setAuth(d.token, d);
        if (d.mustChangePwd) {
          navigate('/');
        } else {
          navigate('/');
        }
      }).catch(function (err) {
        msg(document.getElementById('login-msg'), (err && err.message) || '登录失败', 'error');
      });
    });
  }

  // ============== 仪表盘 ==============
  function renderDashboard(container) {
    api('GET', '/api/admin/dashboard').then(function (res) {
      var d = res.data || {};
      var html = '<div class="card"><h2>仪表盘</h2><div class="row mb">';
      html += '<div class="metric"><div class="label">我的工具</div><div class="value">' + (d.myToolCount || 0) + '</div></div>';
      html += '<div class="metric"><div class="label">SDK 已发布版本数</div><div class="value">' + (d.versionCount || 0) + '</div></div>';
      html += '<div class="metric"><div class="label">累计更新客户端</div><div class="value">' + (d.totalClients || 0) + '</div></div>';
      html += '<div class="metric"><div class="label">今日更新数</div><div class="value">' + (d.todayUpdates || 0) + '</div></div>';
      html += '</div></div>';
      html += '<div class="card"><h2>我的工具</h2>';
      var tools = d.tools || [];
      if (tools.length === 0) {
        html += '<p class="muted">暂无工具，<a href="#/tools">前往注册</a></p>';
      } else {
        html += '<table><thead><tr><th>toolId</th><th>名称</th><th>下载器访问</th><th>本体下载</th><th>操作</th></tr></thead><tbody>';
        tools.forEach(function (t) {
          html += '<tr><td><code>' + t.toolId + '</code></td><td>' + (t.name || '') + '</td><td>' + (t.bootstrapCount || 0) + '</td><td>' + (t.fileCount || 0) + '</td><td><a href="#/tools/' + t.toolId + '/distribute">分发链接</a></td></tr>';
        });
        html += '</tbody></table>';
      }
      html += '</div>';
      container.innerHTML = html;
    }).catch(function (err) {
      container.innerHTML = '<div class="card"><p class="message error">' + ((err && err.message) || '加载失败') + '</p></div>';
    });
  }

  // ============== SDK 版本列表 ==============
  function renderVersions(container) {
    api('GET', '/api/admin/versions').then(function (res) {
      var list = res.data || [];
      var html = '<div class="card"><h2>SDK 版本列表</h2><p class="muted mb">SDK 升级用版本（与工具本体分发独立）</p>';
      if (list.length === 0) {
        html += '<p class="muted">暂无版本，<a href="#/versions/new">前往上传</a></p>';
      } else {
        html += '<table><thead><tr><th>ID</th><th>版本号</th><th>状态</th><th>文件数</th><th>大小</th><th>生成时间</th><th>发布时间</th><th>操作</th></tr></thead><tbody>';
        list.forEach(function (v) {
          html += '<tr><td>' + v.id + '</td><td>' + v.versionNo + '</td><td>' + tag(v.status) + '</td><td>' + (v.fileCount || 0) + '</td><td>' + fmtSize(v.totalSize) + '</td><td>' + fmtTime(v.createdAt) + '</td><td>' + fmtTime(v.publishedAt) + '</td><td>';
          html += '<a href="#/versions/' + v.id + '">详情</a> ';
          if (v.status === 'DRAFT' || v.status === 'OFFLINE') {
            html += ' <button class="ghost" data-publish="' + v.id + '">发布</button>';
          } else if (v.status === 'PUBLISHED') {
            html += ' <button class="ghost danger" data-offline="' + v.id + '">下线</button>';
          }
          html += '</td></tr>';
        });
        html += '</tbody></table>';
      }
      html += '</div>';
      container.innerHTML = html;
      container.querySelectorAll('[data-publish]').forEach(function (b) {
        b.addEventListener('click', function () {
          if (!confirm('确认发布版本 #' + b.getAttribute('data-publish') + '?')) return;
          api('POST', '/api/admin/versions/' + b.getAttribute('data-publish') + '/publish').then(function () {
            renderVersions(container);
          }).catch(function (e) { alert((e && e.message) || '发布失败'); });
        });
      });
      container.querySelectorAll('[data-offline]').forEach(function (b) {
        b.addEventListener('click', function () {
          if (!confirm('确认下线版本 #' + b.getAttribute('data-offline') + '?')) return;
          api('POST', '/api/admin/versions/' + b.getAttribute('data-offline') + '/offline').then(function () {
            renderVersions(container);
          }).catch(function (e) { alert((e && e.message) || '下线失败'); });
        });
      });
    }).catch(function (err) {
      container.innerHTML = '<div class="card"><p class="message error">' + ((err && err.message) || '加载失败') + '</p></div>';
    });
  }

  // ============== SDK 版本上传 ==============
  function renderVersionUpload(container) {
    var html = '<div class="card"><h2>上传新版本（SDK）</h2><div id="up-msg"></div>';
    html += '<div class="row mb"><label>版本号: <input id="vup-version" placeholder="1.2.0"></label></div>';
    html += '<div class="mb"><label>更新说明:<br><textarea id="vup-note" style="width:100%;min-height:80px"></textarea></label></div>';
    html += '<div class="mb"><label>zip 文件（含若干待替换文件）: <input id="vup-file" type="file" accept=".zip"></label></div>';
    html += '<button id="vup-btn">上传</button> <a href="#/versions" class="button secondary" style="display:inline-block;padding:6px 14px;border:1px solid #1976d2;border-radius:4px">返回列表</a>';
    html += '</div>';
    container.innerHTML = html;
    document.getElementById('vup-btn').addEventListener('click', function () {
      var v = document.getElementById('vup-version').value.trim();
      var note = document.getElementById('vup-note').value;
      var f = document.getElementById('vup-file').files[0];
      if (!v) return msg(container.querySelector('#up-msg'), '请填版本号');
      if (!f) return msg(container.querySelector('#up-msg'), '请选 zip 文件');
      apiBinaryUpload('POST', '/api/admin/versions', f, { version: v, releaseNote: note }).then(function () {
        msg(container.querySelector('#up-msg'), '上传成功', 'success');
        setTimeout(function () { navigate('/versions'); }, 800);
      }).catch(function (e) { msg(container.querySelector('#up-msg'), (e && e.message) || '上传失败'); });
    });
  }

  // ============== SDK 版本详情 ==============
  function renderVersionDetail(container, path) {
    var id = path.substring('/versions/'.length);
    Promise.all([
      api('GET', '/api/admin/versions/' + id + '/files')
    ]).then(function (results) {
      var files = results[0].data || [];
      var html = '<div class="card"><h2>版本 #' + id + ' 文件清单</h2>';
      if (files.length === 0) {
        html += '<p class="muted">无文件</p>';
      } else {
        html += '<table><thead><tr><th>路径</th><th>SHA-256</th><th>大小</th></tr></thead><tbody>';
        files.forEach(function (f) {
          html += '<tr><td>' + f.filePath + '</td><td><code style="font-size:11px">' + f.sha256 + '</code></td><td>' + fmtSize(f.size) + '</td></tr>';
        });
        html += '</tbody></table>';
      }
      html += '<p class="mt"><a href="#/versions">返回版本列表</a></p>';
      html += '</div>';
      container.innerHTML = html;
    }).catch(function (err) {
      container.innerHTML = '<div class="card"><p class="message error">' + ((err && err.message) || '加载失败') + '</p></div>';
    });
  }

  // ============== 客户端更新记录 ==============
  function renderRecords(container) {
    api('GET', '/api/admin/records').then(function (res) {
      var list = res.data || [];
      var html = '<div class="card"><h2>客户端更新记录</h2>';
      html += '<div class="row mb"><label>版本: <input id="r-ver" style="width:80px"></label><label>IP: <input id="r-ip" style="width:140px"></label><button id="r-filter" class="secondary">筛选</button> <button id="r-export" class="ghost">导出 CSV</button></div>';
      if (list.length === 0) {
        html += '<p class="muted">暂无记录</p>';
      } else {
        html += '<table><thead><tr><th>客户端ID</th><th>IP</th><th>旧版本</th><th>新版本</th><th>时间</th><th>结果</th><th>耗时</th><th>失败原因</th></tr></thead><tbody>';
        list.forEach(function (r) {
          html += '<tr><td>' + (r.clientId || '-') + '</td><td>' + (r.clientIp || '-') + '</td><td>' + (r.oldVersion || '-') + '</td><td>' + (r.newVersion || '-') + '</td><td>' + fmtTime(r.updateTime) + '</td><td>' + tag(r.result === 'SUCCESS' ? 'PUBLISHED' : 'OFFLINE') + ' ' + (r.result || '-') + '</td><td>' + (r.durationMs || 0) + 'ms</td><td>' + (r.failReason || '') + '</td></tr>';
        });
        html += '</tbody></table>';
      }
      html += '</div>';
      container.innerHTML = html;
      document.getElementById('r-filter').addEventListener('click', function () {
        var ver = document.getElementById('r-ver').value;
        var ip = document.getElementById('r-ip').value;
        var qs = [];
        if (ver) qs.push('newVersion=' + encodeURIComponent(ver));
        if (ip) qs.push('clientIp=' + encodeURIComponent(ip));
        var q = qs.length ? '?' + qs.join('&') : '';
        api('GET', '/api/admin/records' + q).then(function (res2) {
          var l2 = res2.data || [];
          var html2 = '<table><thead><tr><th>客户端ID</th><th>IP</th><th>旧版本</th><th>新版本</th><th>时间</th><th>结果</th><th>耗时</th><th>失败原因</th></tr></thead><tbody>';
          l2.forEach(function (r) {
            html2 += '<tr><td>' + (r.clientId || '-') + '</td><td>' + (r.clientIp || '-') + '</td><td>' + (r.oldVersion || '-') + '</td><td>' + (r.newVersion || '-') + '</td><td>' + fmtTime(r.updateTime) + '</td><td>' + (r.result || '-') + '</td><td>' + (r.durationMs || 0) + 'ms</td><td>' + (r.failReason || '') + '</td></tr>';
          });
          html2 += '</tbody></table>';
          container.querySelector('table').outerHTML = html2;
        });
      });
      document.getElementById('r-export').addEventListener('click', function () {
        var csv = 'client_id,client_ip,old_version,new_version,update_time,result,fail_reason,duration_ms\n';
        list.forEach(function (r) {
          csv += [r.clientId, r.clientIp, r.oldVersion, r.newVersion, r.updateTime, r.result, r.failReason, r.durationMs].map(function (s) { return '"' + (s || '') + '"'; }).join(',') + '\n';
        });
        var blob = new Blob([csv], { type: 'text/csv' });
        var a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = 'records.csv';
        a.click();
      });
    }).catch(function (err) {
      container.innerHTML = '<div class="card"><p class="message error">' + ((err && err.message) || '加载失败') + '</p></div>';
    });
  }

  // ============== 灰度控制 ==============
  function renderPolicy(container) {
    api('GET', '/api/admin/tools').then(function (res) {
      var tools = res.data || [];
      var html = '<div class="card"><h2>灰度推送控制</h2>';
      html += '<div class="mb"><label>选择工具: <select id="pol-tool"><option value="">请选择</option>';
      tools.forEach(function (t) { html += '<option value="' + t.toolId + '">' + t.toolId + ' (' + (t.name || '') + ')</option>'; });
      html += '</select></div>';
      html += '<div id="pol-detail"></div>';
      html += '</div>';
      container.innerHTML = html;
      document.getElementById('pol-tool').addEventListener('change', function () {
        var tid = this.value;
        if (!tid) { document.getElementById('pol-detail').innerHTML = ''; return; }
        loadPolicy(tid);
      });
    });
  }

  function loadPolicy(tid) {
    api('GET', '/api/admin/tools/' + tid + '/policy').then(function (res) {
      var p = res.data || {};
      var html = '<div class="card"><h3>灰度配置 - ' + tid + '</h3>';
      html += '<div class="row mb"><label><input type="checkbox" id="pol-enabled" ' + (p.enabled ? 'checked' : '') + '> 开启灰度</label></div>';
      html += '<div class="row mb"><label>推送阈值: <input id="pol-threshold" type="number" value="' + (p.threshold || 10) + '" style="width:80px"></label> <span class="muted">当前已更新: ' + (p.currentCount || 0) + '  剩余配额: ' + (p.remaining == null || p.remaining < 0 ? '不限' : p.remaining) + '</span></div>';
      html += '<div class="mb"><label>白名单 IP/ID (CSV): <input id="pol-whitelist" value="' + (p.whitelist || '') + '" style="width:400px"></label></div>';
      html += '<div class="row"><button id="pol-save">保存配置</button> <button id="pol-release" class="secondary">一键放开（清除计数）</button> <button id="pol-pause" class="ghost">一键暂停</button> <button id="pol-reset" class="ghost">重置计数</button></div>';
      html += '</div>';
      document.getElementById('pol-detail').innerHTML = html;
      document.getElementById('pol-save').addEventListener('click', function () {
        api('POST', '/api/admin/tools/' + tid + '/policy', {
          enabled: document.getElementById('pol-enabled').checked,
          threshold: parseInt(document.getElementById('pol-threshold').value, 10),
          whitelist: document.getElementById('pol-whitelist').value
        }).then(function () { loadPolicy(tid); }).catch(function (e) { alert((e && e.message) || '保存失败'); });
      });
      document.getElementById('pol-release').addEventListener('click', function () {
        if (!confirm('确认一键放开？将清除计数并解除阈值限制')) return;
        api('POST', '/api/admin/tools/' + tid + '/policy/release').then(function () { loadPolicy(tid); });
      });
      document.getElementById('pol-pause').addEventListener('click', function () {
        api('POST', '/api/admin/tools/' + tid + '/policy/pause').then(function () { loadPolicy(tid); });
      });
      document.getElementById('pol-reset').addEventListener('click', function () {
        api('POST', '/api/admin/tools/' + tid + '/policy/reset').then(function () { loadPolicy(tid); });
      });
    });
  }

  // ============== 工具列表 ==============
  function renderTools(container) {
    api('GET', '/api/admin/tools').then(function (res) {
      var list = res.data || [];
      var html = '<div class="card"><h2>工具列表</h2>';
      html += '<div class="mb"><button id="t-new">+ 注册新工具</button></div>';
      if (list.length === 0) {
        html += '<p class="muted">暂无工具</p>';
      } else {
        html += '<table><thead><tr><th>toolId</th><th>名称</th><th>描述</th><th>归属</th><th>默认启动命令</th><th>创建时间</th><th>操作</th></tr></thead><tbody>';
        list.forEach(function (t) {
          html += '<tr><td><code>' + t.toolId + '</code></td><td>' + (t.name || '') + '</td><td>' + (t.description || '') + '</td><td>' + (t.ownerUsername || '') + '</td><td>' + (t.defaultStartCmd || '') + '</td><td>' + fmtTime(t.createdAt) + '</td><td><a href="#/tools/' + t.toolId + '/versions">管理版本</a> <a href="#/tools/' + t.toolId + '/distribute">分发链接</a></td></tr>';
        });
        html += '</tbody></table>';
      }
      html += '</div>';
      container.innerHTML = html;
      document.getElementById('t-new').addEventListener('click', function () {
        var html2 = '<div class="card"><h3>注册新工具</h3><div id="t-new-msg"></div>';
        html2 += '<div class="mb"><label>toolId: <input id="nt-id" placeholder="recorder" style="width:200px"></label> <span class="muted">小写字母数字短串 2-64 位</span></div>';
        html2 += '<div class="mb"><label>名称: <input id="nt-name" style="width:240px"></label></div>';
        html2 += '<div class="mb"><label>描述: <input id="nt-desc" style="width:400px"></label></div>';
        html2 += '<div class="mb"><label>默认启动命令: <input id="nt-cmd" placeholder="java -jar recorder.jar" style="width:400px"></label></div>';
        html2 += '<button id="nt-submit">注册</button> <button id="nt-cancel" class="ghost">取消</button>';
        html2 += '</div>';
        container.innerHTML = html2;
        document.getElementById('nt-submit').addEventListener('click', function () {
          api('POST', '/api/admin/tools', {
            toolId: document.getElementById('nt-id').value.trim(),
            name: document.getElementById('nt-name').value,
            description: document.getElementById('nt-desc').value,
            defaultStartCmd: document.getElementById('nt-cmd').value
          }).then(function () { renderTools(container); }).catch(function (e) {
            msg(document.getElementById('t-new-msg'), (e && e.message) || '注册失败');
          });
        });
        document.getElementById('nt-cancel').addEventListener('click', function () { renderTools(container); });
      });
    }).catch(function (err) {
      container.innerHTML = '<div class="card"><p class="message error">' + ((err && err.message) || '加载失败') + '</p></div>';
    });
  }

  // ============== 工具版本管理 ==============
  function renderToolVersions(container, path) {
    var parts = path.split('/');
    var tid = parts[2];
    api('GET', '/api/admin/tools/' + tid + '/versions').then(function (res) {
      var list = res.data || [];
      var html = '<div class="card"><h2>工具 ' + tid + ' 版本管理</h2>';
      html += '<div class="mb"><button id="tv-up">+ 上传新本体</button> <a href="#/tools">返回工具列表</a> <a href="#/tools/' + tid + '/distribute">分发链接</a></div>';
      if (list.length === 0) {
        html += '<p class="muted">暂无版本</p>';
      } else {
        html += '<table><thead><tr><th>ID</th><th>版本</th><th>平台</th><th>状态</th><th>文件数</th><th>大小</th><th>启动命令</th><th>创建时间</th><th>操作</th></tr></thead><tbody>';
        list.forEach(function (v) {
          html += '<tr><td>' + v.id + '</td><td>' + v.version + '</td><td>' + (v.platform || '-') + '</td><td>' + tag(v.status) + '</td><td>' + (v.fileCount || 0) + '</td><td>' + fmtSize(v.totalSize) + '</td><td>' + (v.startCommand || '') + '</td><td>' + fmtTime(v.createdAt) + '</td><td>';
          html += '<a href="#" data-files="' + v.id + '">查看文件</a>';
          if (v.status === 'DRAFT' || v.status === 'OFFLINE') html += ' <button class="ghost" data-pub="' + v.id + '">发布</button>';
          else if (v.status === 'PUBLISHED') html += ' <button class="ghost danger" data-off="' + v.id + '">下线</button>';
          html += '</td></tr>';
        });
        html += '</tbody></table>';
      }
      html += '<div id="tv-files"></div>';
      html += '</div>';
      container.innerHTML = html;
      container.querySelectorAll('[data-pub]').forEach(function (b) {
        b.addEventListener('click', function () {
          if (!confirm('确认发布 #' + b.getAttribute('data-pub') + '?')) return;
          api('POST', '/api/admin/tools/versions/' + b.getAttribute('data-pub') + '/publish').then(function () { renderToolVersions(container, path); });
        });
      });
      container.querySelectorAll('[data-off]').forEach(function (b) {
        b.addEventListener('click', function () {
          if (!confirm('确认下线 #' + b.getAttribute('data-off') + '?')) return;
          api('POST', '/api/admin/tools/versions/' + b.getAttribute('data-off') + '/offline').then(function () { renderToolVersions(container, path); });
        });
      });
      container.querySelectorAll('[data-files]').forEach(function (a) {
        a.addEventListener('click', function (e) {
          e.preventDefault();
          api('GET', '/api/admin/tools/versions/' + a.getAttribute('data-files') + '/files').then(function (res2) {
            var fs = res2.data || [];
            var h = '<h3>文件清单</h3><table><thead><tr><th>路径</th><th>SHA-256</th><th>大小</th></tr></thead><tbody>';
            fs.forEach(function (f) { h += '<tr><td>' + f.filePath + '</td><td><code style="font-size:11px">' + f.sha256 + '</code></td><td>' + fmtSize(f.size) + '</td></tr>'; });
            h += '</tbody></table>';
            document.getElementById('tv-files').innerHTML = h;
          });
        });
      });
      document.getElementById('tv-up').addEventListener('click', function () {
        var h = '<div class="card"><h3>上传工具本体</h3><div id="tv-msg"></div>';
        h += '<div class="mb"><label>版本号: <input id="tu-ver" placeholder="1.0.0"></label></div>';
        h += '<div class="mb"><label>平台: <select id="tu-plat"><option value="win">win</option><option value="linux">linux</option></select></label></div>';
        h += '<div class="mb"><label>启动命令: <input id="tu-cmd" placeholder="留空用工具默认"></label></div>';
        h += '<div class="mb"><label>更新说明: <input id="tu-note"></label></div>';
        h += '<div class="mb"><label>zip 文件: <input id="tu-file" type="file" accept=".zip"></label></div>';
        h += '<button id="tu-go">上传</button> <button id="tu-cancel" class="ghost">取消</button>';
        h += '</div>';
        container.innerHTML = h;
        document.getElementById('tu-go').addEventListener('click', function () {
          var ver = document.getElementById('tu-ver').value.trim();
          var plat = document.getElementById('tu-plat').value;
          var cmd = document.getElementById('tu-cmd').value;
          var note = document.getElementById('tu-note').value;
          var f = document.getElementById('tu-file').files[0];
          if (!ver || !f) return msg(document.getElementById('tv-msg'), '请填版本号与 zip 文件');
          apiBinaryUpload('POST', '/api/admin/tools/' + tid + '/versions', f, {
            version: ver, platform: plat, startCommand: cmd, releaseNote: note
          }).then(function () { renderToolVersions(container, path); }).catch(function (e) {
            msg(document.getElementById('tv-msg'), (e && e.message) || '上传失败');
          });
        });
        document.getElementById('tu-cancel').addEventListener('click', function () { renderToolVersions(container, path); });
      });
    });
  }

  // ============== 分发链接页 ==============
  function renderToolDistribute(container, path) {
    var parts = path.split('/');
    var tid = parts[2];
    api('GET', '/api/admin/tools/' + tid + '/distribute').then(function (res) {
      var d = res.data || {};
      var html = '<div class="card"><h2>分发链接 - ' + tid + '</h2>';
      html += '<div class="mb"><label>分发 URL: <input value="' + d.distributeUrl + '" readonly style="width:400px"></label> <button id="dl-copy" class="secondary">复制</button></div>';
      html += '<div class="row mb"><div class="metric"><div class="label">下载器访问</div><div class="value">' + (d.bootstrapCount || 0) + '</div></div><div class="metric"><div class="label">本体下载</div><div class="value">' + (d.fileCount || 0) + '</div></div></div>';
      var pb = d.platformBreakdown || {};
      html += '<p class="muted">按平台：win=' + (pb.win || 0) + ' / linux=' + (pb.linux || 0) + '</p>';
      html += '<p class="mt"><a href="#/tools/' + tid + '/versions">返回版本管理</a></p>';
      html += '</div>';
      container.innerHTML = html;
      document.getElementById('dl-copy').addEventListener('click', function () {
        var inp = container.querySelector('input[readonly]');
        inp.select();
        document.execCommand('copy');
        alert('已复制：' + inp.value);
      });
    }).catch(function (err) {
      container.innerHTML = '<div class="card"><p class="message error">' + ((err && err.message) || '加载失败') + '</p></div>';
    });
  }

  // ============== 用户管理（仅 ADMIN） ==============
  function renderUsers(container) {
    api('GET', '/api/admin/users').then(function (res) {
      var list = res.data || [];
      var html = '<div class="card"><h2>用户管理</h2>';
      html += '<div class="mb"><button id="u-new">+ 新增用户</button></div>';
      html += '<table><thead><tr><th>ID</th><th>用户名</th><th>角色</th><th>状态</th><th>显示名</th><th>创建时间</th><th>最近登录</th><th>工具数</th><th>操作</th></tr></thead><tbody>';
      list.forEach(function (u) {
        html += '<tr><td>' + u.id + '</td><td>' + u.username + '</td><td>' + tag(u.role) + '</td><td>' + tag(u.status) + '</td><td>' + (u.displayName || '') + '</td><td>' + fmtTime(u.createdAt) + '</td><td>' + fmtTime(u.lastLoginAt) + '</td><td>' + (u.toolCount || 0) + '</td><td>';
        html += '<button class="ghost" data-reset="' + u.id + '">重置密码</button> ';
        if (u.status === 'ACTIVE') html += '<button class="ghost" data-disable="' + u.id + '">禁用</button> ';
        else html += '<button class="ghost" data-enable="' + u.id + '">启用</button> ';
        if (u.role === 'USER') html += '<button class="ghost" data-admin="' + u.id + '">升为 ADMIN</button> ';
        else html += '<button class="ghost" data-user="' + u.id + '">降为 USER</button> ';
        html += '<button class="ghost danger" data-del="' + u.id + '">删除</button>';
        html += '</td></tr>';
      });
      html += '</tbody></table>';
      html += '</div>';
      container.innerHTML = html;
      container.querySelectorAll('[data-reset]').forEach(function (b) {
        b.addEventListener('click', function () {
          var newP = prompt('为用户 #' + b.getAttribute('data-reset') + ' 设置新密码（至少 6 位）');
          if (!newP) return;
          api('PATCH', '/api/admin/users/' + b.getAttribute('data-reset'), { newPassword: newP }).then(function () { renderUsers(container); }).catch(function (e) { alert((e && e.message) || '失败'); });
        });
      });
      container.querySelectorAll('[data-disable]').forEach(function (b) {
        b.addEventListener('click', function () {
          api('PATCH', '/api/admin/users/' + b.getAttribute('data-disable'), { status: 'DISABLED' }).then(function () { renderUsers(container); });
        });
      });
      container.querySelectorAll('[data-enable]').forEach(function (b) {
        b.addEventListener('click', function () {
          api('PATCH', '/api/admin/users/' + b.getAttribute('data-enable'), { status: 'ACTIVE' }).then(function () { renderUsers(container); });
        });
      });
      container.querySelectorAll('[data-admin]').forEach(function (b) {
        b.addEventListener('click', function () {
          if (!confirm('确认升为 ADMIN?')) return;
          api('PATCH', '/api/admin/users/' + b.getAttribute('data-admin'), { role: 'ADMIN' }).then(function () { renderUsers(container); }).catch(function (e) { alert((e && e.message) || '失败'); });
        });
      });
      container.querySelectorAll('[data-user]').forEach(function (b) {
        b.addEventListener('click', function () {
          if (!confirm('确认降为 USER?')) return;
          api('PATCH', '/api/admin/users/' + b.getAttribute('data-user'), { role: 'USER' }).then(function () { renderUsers(container); }).catch(function (e) { alert((e && e.message) || '失败'); });
        });
      });
      container.querySelectorAll('[data-del]').forEach(function (b) {
        b.addEventListener('click', function () {
          var to = prompt('删除前需转移工具归属，输入目标用户 ID（留空则不转移，工具归属会丢失）');
          if (to === null) return;
          var body = {};
          if (to) body.transferTo = to;
          api('DELETE', '/api/admin/users/' + b.getAttribute('data-del') + (to ? '?transferTo=' + to : '')).then(function () {
            // DELETE 没有请求体，transferTo 通过 query 传
            renderUsers(container);
          }).catch(function (e) { alert((e && e.message) || '删除失败'); });
          // 上面 Promise 已发起，下面同步重发避免歧义
        });
      });
      document.getElementById('u-new').addEventListener('click', function () {
        var html2 = '<div class="card"><h3>新增用户</h3><div id="u-new-msg"></div>';
        html2 += '<div class="mb"><label>用户名: <input id="nu-user" style="width:200px"></label></div>';
        html2 += '<div class="mb"><label>初始密码: <input id="nu-pass" type="password" style="width:200px"></label> <span class="muted">明文展示一次，落库 BCrypt 哈希</span></div>';
        html2 += '<div class="mb"><label>角色: <select id="nu-role"><option value="USER">USER</option><option value="ADMIN">ADMIN</option></select></label></div>';
        html2 += '<div class="mb"><label>显示名: <input id="nu-name" style="width:240px"></label></div>';
        html2 += '<button id="nu-go">新增</button> <button id="nu-cancel" class="ghost">取消</button>';
        html2 += '</div>';
        container.innerHTML = html2;
        document.getElementById('nu-go').addEventListener('click', function () {
          api('POST', '/api/admin/users', {
            username: document.getElementById('nu-user').value.trim(),
            password: document.getElementById('nu-pass').value,
            role: document.getElementById('nu-role').value,
            displayName: document.getElementById('nu-name').value
          }).then(function () { renderUsers(container); }).catch(function (e) {
            msg(document.getElementById('u-new-msg'), (e && e.message) || '新增失败');
          });
        });
        document.getElementById('nu-cancel').addEventListener('click', function () { renderUsers(container); });
      });
    }).catch(function (err) {
      container.innerHTML = '<div class="card"><p class="message error">' + ((err && err.message) || '加载失败') + '</p></div>';
    });
  }

  // ============== 个人中心 ==============
  function renderMe(container) {
    renderChangePasswordPage(container, false);
  }

  function renderChangePasswordPage(container, force) {
    var html = '<div class="card"><h2>' + (force ? '首次登录 - 修改初始密码' : '个人中心 - 修改密码') + '</h2>';
    html += '<div id="cp-msg"></div>';
    html += '<div class="mb"><label>当前用户: ' + ((CURRENT_USER && CURRENT_USER.username) || '-') + ' ' + tag(CURRENT_USER ? CURRENT_USER.role : '') + '</label></div>';
    html += '<div class="mb"><label>原密码: <input id="cp-old" type="password"></label></div>';
    html += '<div class="mb"><label>新密码: <input id="cp-new" type="password"></label> <span class="muted">至少 6 位</span></div>';
    html += '<div class="mb"><label>确认新密码: <input id="cp-new2" type="password"></label></div>';
    html += '<button id="cp-go">修改密码</button>';
    if (!force) html += ' <a href="#/" class="button secondary" style="display:inline-block;padding:6px 14px;border:1px solid #1976d2;border-radius:4px">返回仪表盘</a>';
    html += '</div>';
    container.innerHTML = html;
    document.getElementById('cp-go').addEventListener('click', function () {
      var oldP = document.getElementById('cp-old').value;
      var newP = document.getElementById('cp-new').value;
      var newP2 = document.getElementById('cp-new2').value;
      if (newP !== newP2) return msg(document.getElementById('cp-msg'), '两次新密码不一致');
      if (newP.length < 6) return msg(document.getElementById('cp-msg'), '新密码至少 6 位');
      api('POST', '/api/auth/change-password', { oldPassword: oldP, newPassword: newP }).then(function () {
        msg(document.getElementById('cp-msg'), '密码已修改', 'success');
        if (CURRENT_USER) {
          CURRENT_USER.mustChangePwd = false;
          localStorage.setItem('upg_user', JSON.stringify(CURRENT_USER));
        }
        setTimeout(function () { navigate('/'); }, 800);
      }).catch(function (e) {
        msg(document.getElementById('cp-msg'), (e && e.message) || '修改失败');
      });
    });
  }

  // ============== 启动 ==============
  init();
})();
