/**
 * app.js — 应用控制器
 * 多机器人协作巡检仿真系统
 *
 * 职责：状态管理、WebSocket 通信、演示模式、
 *       DOM 更新（增量式，防闪烁）、事件处理、渲染循环
 */

// ==================== 常量与配置 ====================

var WEBSOCKET_URL = 'ws://localhost:8887';
var WS_RECONNECT_MAX = 5;
var DEMO_TICK_MS = 100;
var DEFAULT_MAP_WIDTH = 26;
var DEFAULT_MAP_HEIGHT = 16;
var DEFAULT_OBSTACLE_DENSITY = 25;
var LOG_MAX_ENTRIES = 200;
var TREND_MAX_POINTS = 120; // 趋势图最多保留 120 个数据点

var STATUS_IDLE = 'IDLE';
var STATUS_RUNNING = 'RUNNING';
var STATUS_PAUSED = 'PAUSED';
var STATUS_FINISHED = 'FINISHED';

// ==================== DOM 引用缓存 ====================

var DOM = {};

// ==================== 应用状态 ====================

var state = {
    mapWidth: DEFAULT_MAP_WIDTH,
    mapHeight: DEFAULT_MAP_HEIGHT,
    mapView: [],
    staticBlock: [],
    dynamicBlock: [],
    exploredPercent: 0,
    tick: 0,
    cars: {},
    statsReport: null,
    coverageHistory: [],
    status: STATUS_IDLE,
    elapsedMs: 0,
    startTimestamp: null,
    logs: [],
    obstacleCount: 0,
    connectedRobots: 0
};

/** 当前用户信息 */
var currentUser = {
    userId: null,
    nickname: '未登录',
    preferences: {}
};

/** 用于防闪烁 — 缓存上次机器人数据快照 */
var _lastCarSnapshot = '';
/** 趋势图覆盖率历史（本地记录，与 coverageHistory 互补） */
var _trendData = [];

var layout = null;
var isDemoMode = false;
var demoInterval = null;
var demoStartTime = null;
var demoElapsedBeforePause = 0;
var ws = null;
var wsReconnectCount = 0;
var wsReconnectTimer = null;
var wsConnected = false;
var animFrameId = null;
var lastDomUpdate = 0;
var DOM_UPDATE_INTERVAL = 100;
var stateDirty = false;
var _lastStepsSnapshot = '';

// ==================== 初始化 ====================

document.addEventListener('DOMContentLoaded', function () { init(); });

function init() {
    cacheDomReferences();
    wireEvents();
    updateClock();
    setInterval(updateClock, 1000);
    applySettingsToUI();
    initCanvasAndLayout();
    initTrendCanvas();
    tryConnectWebSocket();
    startRenderLoop();
    addLog('info', '系统就绪，等待仿真启动...');
}

function cacheDomReferences() {
    DOM.systemTime = document.getElementById('systemTime');
    DOM.connectionStatus = document.getElementById('connectionStatus');

    // 导航
    DOM.navTabs = document.getElementById('navTabs');

    // 左侧
    DOM.btnStart = document.getElementById('btnStart');
    DOM.btnPause = document.getElementById('btnPause');
    DOM.btnReset = document.getElementById('btnReset');
    DOM.speedSlider = document.getElementById('speedSlider');
    DOM.speedValue = document.getElementById('speedValue');
    DOM.robotCount = document.getElementById('robotCount');
    DOM.obstacleDensity = document.getElementById('obstacleDensity');
    DOM.densityValue = document.getElementById('densityValue');
    DOM.mapWidth = document.getElementById('mapWidth');
    DOM.mapHeight = document.getElementById('mapHeight');
    DOM.btnRandomObs = document.getElementById('btnRandomObs');
    DOM.btnClearObs = document.getElementById('btnClearObs');
    DOM.manualToggle = document.getElementById('manualToggle');
    DOM.manualHint = document.getElementById('manualHint');
    DOM.carPlaceToggle = document.getElementById('carPlaceToggle');
    DOM.carPlaceHint = document.getElementById('carPlaceHint');
    DOM.mapImageInput = document.getElementById('mapImageInput');
    DOM.btnUploadMap = document.getElementById('btnUploadMap');
    DOM.mapImageInfo = document.getElementById('mapImageInfo');
    DOM.imageFileName = document.getElementById('imageFileName');
    DOM.btnRemoveMap = document.getElementById('btnRemoveMap');
    DOM.userAvatar = document.getElementById('userAvatar');
    DOM.headerUserName = document.getElementById('headerUserName');

    // 登录弹窗
    DOM.loginOverlay = document.getElementById('loginOverlay');
    DOM.loginNickname = document.getElementById('loginNickname');
    DOM.loginPassword = document.getElementById('loginPassword');
    DOM.loginBtn = document.getElementById('loginBtn');
    DOM.loginClose = document.getElementById('loginClose');
    DOM.loginError = document.getElementById('loginError');
    DOM.loginHint = document.getElementById('loginHint');

    // 回放分析
    DOM.replayPanel = document.getElementById('replayPanel');
    DOM.replaySessionSelect = document.getElementById('replaySessionSelect');
    DOM.replayBtnPlay = document.getElementById('replayBtnPlay');
    DOM.replayBtnPause = document.getElementById('replayBtnPause');
    DOM.replayBtnStop = document.getElementById('replayBtnStop');
    DOM.replayBtnPrev = document.getElementById('replayBtnPrev');
    DOM.replayBtnNext = document.getElementById('replayBtnNext');
    DOM.replaySpeed = document.getElementById('replaySpeed');
    DOM.replaySpeedVal = document.getElementById('replaySpeedVal');
    DOM.replayFrameInfo = document.getElementById('replayFrameInfo');
    DOM.replayCanvas = document.getElementById('replayCanvas');
    DOM.replayCoverage = document.getElementById('replayCoverage');
    DOM.replaySteps = document.getElementById('replaySteps');
    DOM.replayTick = document.getElementById('replayTick');
    DOM.replaySessionId = document.getElementById('replaySessionId');

    // 系统设置
    DOM.settingsPanel = document.getElementById('settingsPanel');
    DOM.settingsSaveBtn = document.getElementById('settingsSaveBtn');
    DOM.settingsResetBtn = document.getElementById('settingsResetBtn');
    DOM.ucOldPwd = document.getElementById('ucOldPwd');
    DOM.ucNewPwd = document.getElementById('ucNewPwd');
    DOM.ucNewPwd2 = document.getElementById('ucNewPwd2');
    DOM.ucChangePwdBtn = document.getElementById('ucChangePwdBtn');
    DOM.ucPwdMsg = document.getElementById('ucPwdMsg');

    // 用户中心
    DOM.ucNickname = document.getElementById('ucNickname');
    DOM.ucUserId = document.getElementById('ucUserId');
    DOM.ucInfoId = document.getElementById('ucInfoId');
    DOM.ucInfoNickname = document.getElementById('ucInfoNickname');
    DOM.ucInfoCreated = document.getElementById('ucInfoCreated');
    DOM.ucInfoLastLogin = document.getElementById('ucInfoLastLogin');
    DOM.ucInfoSessions = document.getElementById('ucInfoSessions');
    DOM.ucInfoReplays = document.getElementById('ucInfoReplays');
    DOM.ucPrefsContainer = document.getElementById('ucPrefsContainer');
    DOM.ucPrefKey = document.getElementById('ucPrefKey');
    DOM.ucPrefValue = document.getElementById('ucPrefValue');
    DOM.ucPrefSaveBtn = document.getElementById('ucPrefSaveBtn');
    DOM.ucHistoryContainer = document.getElementById('ucHistoryContainer');
    DOM.ucLogoutBtn = document.getElementById('ucLogoutBtn');

    // 中间
    DOM.centerPanel = document.getElementById('centerPanel');
    DOM.mapArea = document.getElementById('mapArea');
    DOM.bottomPanels = document.getElementById('bottomPanels');
    DOM.canvasWrapper = document.getElementById('canvasWrapper');
    DOM.mapCanvas = document.getElementById('mapCanvas');
    DOM.cellTooltip = document.getElementById('cellTooltip');
    DOM.trendCanvas = document.getElementById('trendCanvas');
    DOM.chartCoverageValue = document.getElementById('chartCoverageValue');

    // 右侧 — 机器人
    DOM.robotCountBadge = document.getElementById('robotCountBadge');
    DOM.robotCardsContainer = document.getElementById('robotCardsContainer');
    // 中间底部 — 步数统计
    DOM.stepsStatsContainer = document.getElementById('stepsStatsContainer');
    DOM.stepsTotalValue = document.getElementById('stepsTotalValue');
    // 中间底部 — 系统信息
    DOM.sysInfoStatus = document.getElementById('sysInfoStatus');
    DOM.sysInfoTick = document.getElementById('sysInfoTick');
    DOM.sysInfoElapsed = document.getElementById('sysInfoElapsed');
    DOM.sysInfoOnlineRobots = document.getElementById('sysInfoOnlineRobots');
    DOM.sysInfoMapSize = document.getElementById('sysInfoMapSize');
    DOM.sysInfoObstacles = document.getElementById('sysInfoObstacles');
    DOM.sysInfoCoverage = document.getElementById('sysInfoCoverage');
    // 右侧 — 统计
    DOM.coveragePercent = document.getElementById('coveragePercent');
    DOM.coverageBar = document.getElementById('coverageBar');
    DOM.totalSteps = document.getElementById('totalSteps');
    DOM.obstacleCount = document.getElementById('obstacleCount');
    DOM.elapsedTime = document.getElementById('elapsedTime');
    DOM.onlineRobotCount = document.getElementById('onlineRobotCount');
    DOM.logContainer = document.getElementById('logContainer');

    // 底部
    DOM.statCoverage = document.getElementById('statCoverage').querySelector('.stat-box-value');
    DOM.statSteps   = document.getElementById('statSteps').querySelector('.stat-box-value');
    DOM.statTick    = document.getElementById('statTick').querySelector('.stat-box-value');
    DOM.statTime    = document.getElementById('statTime').querySelector('.stat-box-value');
    DOM.sysStatusText = document.getElementById('sysStatusText');
    var statSystem = document.getElementById('statSystem');
    DOM.sysStatusDot = statSystem.querySelector('.sys-status-dot');
}

// ==================== 画布 ====================

function initCanvasAndLayout() {
    layout = initCanvas(DOM.mapCanvas, DOM.canvasWrapper, state.mapWidth, state.mapHeight);
}

function recalcMapLayout() {
    if (!DOM.canvasWrapper || !DOM.mapCanvas) return;
    resizeCanvas(DOM.mapCanvas, DOM.canvasWrapper, state.mapWidth, state.mapHeight);
    var rect = DOM.canvasWrapper.getBoundingClientRect();
    layout = calcLayout(rect.width, rect.height, state.mapWidth, state.mapHeight);
}

// ==================== 趋势图画布 ====================

function initTrendCanvas() {
    var tc = DOM.trendCanvas;
    if (!tc) return;
    var wrap = tc.parentElement;
    var dpr = window.devicePixelRatio || 1;
    var rect = wrap.getBoundingClientRect();
    tc.width = rect.width * dpr;
    tc.height = rect.height * dpr;
    tc.style.width = rect.width + 'px';
    tc.style.height = rect.height + 'px';
    var ctx = tc.getContext('2d');
    ctx.scale(dpr, dpr);

    if (window._trendResizeObserver) window._trendResizeObserver.disconnect();
    window._trendResizeObserver = new ResizeObserver(function () {
        var r2 = wrap.getBoundingClientRect();
        tc.width = r2.width * dpr;
        tc.height = r2.height * dpr;
        tc.style.width = r2.width + 'px';
        tc.style.height = r2.height + 'px';
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        ctx.scale(dpr, dpr);
        drawTrendChart();
    });
    window._trendResizeObserver.observe(wrap);
}

/**
 * 在趋势图画布上绘制覆盖率折线图
 */
function drawTrendChart() {
    var tc = DOM.trendCanvas;
    if (!tc) return;
    var ctx = tc.getContext('2d');
    var dpr = window.devicePixelRatio || 1;
    var w = tc.width / dpr;
    var h = tc.height / dpr;

    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, tc.width, tc.height);
    ctx.restore();

    if (_trendData.length < 2) {
        ctx.fillStyle = '#556677';
        ctx.font = '12px "Microsoft YaHei", sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('等待数据...', w / 2, h / 2);
        return;
    }

    var padLeft = 36, padRight = 12, padTop = 8, padBottom = 22;
    var pw = w - padLeft - padRight;
    var ph = h - padTop - padBottom;

    // 背景网格
    ctx.strokeStyle = 'rgba(30,58,95,0.2)';
    ctx.lineWidth = 0.5;
    var gridLines = 5;
    for (var g = 0; g <= gridLines; g++) {
        var gy = padTop + (ph / gridLines) * g;
        ctx.beginPath(); ctx.moveTo(padLeft, gy); ctx.lineTo(w - padRight, gy); ctx.stroke();
        // Y 标签
        ctx.fillStyle = '#445566';
        ctx.font = '9px "Consolas", monospace';
        ctx.textAlign = 'right';
        ctx.fillText(Math.round(100 - (100 / gridLines) * g) + '%', padLeft - 4, gy + 3);
    }

    // X 轴标签
    ctx.fillStyle = '#445566';
    ctx.font = '9px "Consolas", monospace';
    ctx.textAlign = 'center';
    var xSteps = Math.min(5, _trendData.length);
    for (var xg = 0; xg < xSteps; xg++) {
        var idx = Math.floor((_trendData.length - 1) * (xg / (xSteps - 1 || 1)));
        var xPos = padLeft + (pw / (_trendData.length - 1 || 1)) * idx;
        ctx.fillText('' + (idx + 1), xPos, h - 4);
    }

    // 绘制填充区域
    ctx.beginPath();
    var stepX = pw / (_trendData.length - 1 || 1);
    for (var i = 0; i < _trendData.length; i++) {
        var sx = padLeft + stepX * i;
        var sy = padTop + ph - (_trendData[i] / 100) * ph;
        if (i === 0) ctx.moveTo(sx, sy); else ctx.lineTo(sx, sy);
    }
    var lastX = padLeft + stepX * (_trendData.length - 1);
    ctx.lineTo(lastX, padTop + ph);
    ctx.lineTo(padLeft, padTop + ph);
    ctx.closePath();
    var fillGrad = ctx.createLinearGradient(0, padTop, 0, padTop + ph);
    fillGrad.addColorStop(0, 'rgba(30,196,255,0.25)');
    fillGrad.addColorStop(1, 'rgba(30,196,255,0.02)');
    ctx.fillStyle = fillGrad;
    ctx.fill();

    // 绘制折线
    ctx.beginPath();
    for (var j = 0; j < _trendData.length; j++) {
        var lx = padLeft + stepX * j;
        var ly = padTop + ph - (_trendData[j] / 100) * ph;
        if (j === 0) ctx.moveTo(lx, ly); else ctx.lineTo(lx, ly);
    }
    ctx.strokeStyle = '#1ec4ff';
    ctx.lineWidth = 2;
    ctx.shadowColor = 'rgba(30,196,255,0.6)';
    ctx.shadowBlur = 6;
    ctx.stroke();
    ctx.shadowBlur = 0;

    // 最后数据点圆点
    var lastPt = _trendData[_trendData.length - 1];
    var lpx = padLeft + stepX * (_trendData.length - 1);
    var lpy = padTop + ph - (lastPt / 100) * ph;
    ctx.beginPath();
    ctx.arc(lpx, lpy, 4, 0, Math.PI * 2);
    ctx.fillStyle = '#1ec4ff';
    ctx.fill();
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 1.5;
    ctx.stroke();
}

// ==================== 时钟 ====================

function updateClock() {
    var now = new Date();
    DOM.systemTime.textContent = padZero(now.getHours()) + ':' + padZero(now.getMinutes()) + ':' + padZero(now.getSeconds());
}

// ==================== 状态规范化 ====================

function normalizeState(raw) {
    if (!raw || typeof raw !== 'object') return;
    var oldMapWidth = state.mapWidth;
    var oldMapHeight = state.mapHeight;
    if (raw.mapWidth !== undefined) state.mapWidth = raw.mapWidth;
    if (raw.mapHeight !== undefined) state.mapHeight = raw.mapHeight;
    if (raw.status) {
        state.status = raw.status;
        if (raw.status === STATUS_FINISHED || raw.status === STATUS_IDLE || raw.status === STATUS_PAUSED) {
            state.startTimestamp = null;
        }
    }
    if (raw.tick !== undefined) state.tick = raw.tick;
    if (raw.exploredPercent !== undefined) state.exploredPercent = raw.exploredPercent;
    if (raw.mapView) state.mapView = raw.mapView;
    if (raw.staticBlock) state.staticBlock = raw.staticBlock;
    if (raw.dynamicBlock) state.dynamicBlock = raw.dynamicBlock;
    if (raw.statsReport) state.statsReport = raw.statsReport;
    if (raw.coverageHistory) state.coverageHistory = raw.coverageHistory;

    if (raw.staticBlock) {
        var count = 0;
        for (var i = 0; i < raw.staticBlock.length; i++) { if (raw.staticBlock[i]) count++; }
        state.obstacleCount = count;
    }

    if (raw.cars) {
        var newCars = {};
        if (Array.isArray(raw.cars)) {
            raw.cars.forEach(function (c, i) {
                var cid = c.id || c.carId || ('car' + (i + 1));
                newCars[cid] = buildCarInfo(cid, c);
            });
        } else if (typeof raw.cars === 'object') {
            Object.keys(raw.cars).forEach(function (cid) {
                newCars[cid] = buildCarInfo(cid, raw.cars[cid]);
            });
        }
        state.cars = newCars;
        state.connectedRobots = Object.keys(newCars).length;
    }
    if (state.connectedRobots === 0 && state.cars) {
        state.connectedRobots = Object.keys(state.cars).length;
    }

    // 记录趋势数据
    if (state.mapWidth !== oldMapWidth || state.mapHeight !== oldMapHeight) {
        recalcMapLayout();
    }

    recordTrendPoint();

    stateDirty = true;
}

function buildCarInfo(carId, c) {
    return {
        carId: c.carId || carId,
        position: c.position || { x: 0, y: 0 },
        target: c.target || null,
        routeList: c.routeList || [],
        status: c.status || 'IDLE',
        stepsWalked: c.stepsWalked || 0,
        battery: c.battery !== undefined ? c.battery : 100
    };
}

function recordTrendPoint() {
    _trendData.push(state.exploredPercent);
    if (_trendData.length > TREND_MAX_POINTS) _trendData.shift();
}

// ==================== WebSocket ====================

function tryConnectWebSocket() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
    try { ws = new WebSocket(WEBSOCKET_URL); } catch (e) { onWsFail(); return; }

    ws.onopen = function () {
        wsConnected = true; wsReconnectCount = 0;
        updateConnectionUI(true);
        addLog('success', 'WebSocket 已连接到 ' + WEBSOCKET_URL);
        if (isDemoMode) stopDemoMode();
    };
    ws.onmessage = function (event) {
        try {
            var raw = JSON.parse(event.data);
            // 障碍物刷新消息：只更新障碍物，不触发仿真
            if (raw.type === 'obstacle_update') {
                if (raw.staticBlock) state.staticBlock = raw.staticBlock;
                if (raw.dynamicBlock) state.dynamicBlock = raw.dynamicBlock;
                state.exploredPercent = raw.exploredPercent || 0;
                stateDirty = true;
                return;
            }
            // 命令响应（含 success 字段） vs 仿真状态广播
            if (raw.success !== undefined) {
                handleCommandResponse(raw);
            } else {
                normalizeState(raw);
                if (state.status === STATUS_IDLE && state.tick > 0) {
                    state.status = STATUS_RUNNING;
                    state.startTimestamp = Date.now() - state.elapsedMs;
                    updateControlButtons();
                }
            }
        } catch (e) { addLog('error', 'JSON 解析失败: ' + e.message); }
    };
    ws.onclose = function () { wsConnected = false; updateConnectionUI(false); addLog('warn', 'WebSocket 连接已断开'); scheduleReconnect(); };
    ws.onerror = function () { wsConnected = false; updateConnectionUI(false); };

    setTimeout(function () {
        if (!wsConnected) { addLog('warn', 'WebSocket 连接超时，切换到本地演示模式'); startDemoMode(); }
    }, 2000);
}

function scheduleReconnect() {
    if (wsReconnectCount >= WS_RECONNECT_MAX) { if (!isDemoMode) startDemoMode(); return; }
    var delay = Math.min(1000 * Math.pow(2, wsReconnectCount), 30000);
    wsReconnectCount++;
    addLog('info', '将在 ' + (delay/1000) + ' 秒后尝试重连 (' + wsReconnectCount + '/' + WS_RECONNECT_MAX + ')');
    if (wsReconnectTimer) clearTimeout(wsReconnectTimer);
    wsReconnectTimer = setTimeout(tryConnectWebSocket, delay);
}

function onWsFail() { wsConnected = false; updateConnectionUI(false); addLog('warn', '无法创建 WebSocket 连接，切换到本地演示模式'); startDemoMode(); }

function updateConnectionUI(connected) {
    var el = DOM.connectionStatus; if (!el) return;
    el.className = 'connection-status ' + (connected ? 'connected' : 'disconnected');
    el.querySelector('.status-text').textContent = connected ? '已连接' : '未连接';
}

function sendCommand(cmd, data) {
    var payload = { command: cmd }; if (data) payload.data = data;
    if (ws && ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify(payload)); }
    else if (!isDemoMode) { addLog('warn', 'WebSocket 未连接，无法发送命令: ' + cmd); }
}

/** 处理后端返回的命令响应（用户操作等） */
function handleCommandResponse(raw) {
    if (raw.success) {
        var data = raw.data || {};

        // LOGIN 响应
        if (data.userId && data.nickname) {
            currentUser.userId = data.userId;
            currentUser.nickname = data.nickname;
            currentUser.preferences = data.preferences || {};
            currentUser.createdAt = data.createdAt || null;
            currentUser.lastLogin = data.lastLogin || null;
            currentUser.sessionCount = data.sessionCount || 0;
            currentUser.replayCount = data.replayCount || 0;
            DOM.headerUserName.textContent = data.nickname;
            DOM.loginBtn.disabled = false;
            DOM.loginBtn.textContent = '登 录 / 注 册';
            hideLoginModal();
            addLog('success', data.message || ('登录成功: ' + data.nickname));
        }
        // LOGOUT 响应
        else if (data.message && data.message.indexOf('登出') >= 0) {
            currentUser.userId = null;
            currentUser.nickname = '未登录';
            currentUser.preferences = {};
            DOM.headerUserName.textContent = '未登录';
            addLog('info', data.message);
        }
        // UPDATE_NICKNAME 响应
        else if (data.nickname !== undefined && !data.userId) {
            currentUser.nickname = data.nickname;
            DOM.headerUserName.textContent = data.nickname;
            DOM.ucNickname.textContent = data.nickname;
            DOM.ucInfoNickname.textContent = data.nickname;
            addLog('success', data.message || '昵称已更新');
        }
        // SAVE_PREF 响应
        else if (data.key !== undefined && data.value !== undefined) {
            currentUser.preferences[data.key] = data.value;
            addLog('success', '偏好已保存: ' + data.key);
        }
        // GET_PROFILE / GET_HISTORY 响应
        else if (data.history || data.preferences !== undefined) {
            if (data.preferences) currentUser.preferences = data.preferences;
            if (data.history) {
                var histHtml = '';
                if (data.history.length === 0) {
                    histHtml = '<p class="uc-empty">暂无会话记录</p>';
                } else {
                    data.history.forEach(function (h) {
                        histHtml += '<div class="uc-history-item">';
                        histHtml += '<span class="uc-history-id">' + escapeHtml(h.sessionId || '-') + '</span>';
                        histHtml += '<span class="uc-history-time">' + escapeHtml(h.joinedAt || '-') + '</span>';
                        histHtml += '<span>' + escapeHtml(h.mapSize || '-') + ' / ' + escapeHtml(String(h.carCount || '-')) + '辆</span>';
                        histHtml += '<span class="uc-history-status">' + escapeHtml(h.status || '-') + '</span>';
                        histHtml += '</div>';
                    });
                }
                if (DOM.ucHistoryContainer) DOM.ucHistoryContainer.innerHTML = histHtml;
            }
            if (currentUser.userId) {
                DOM.ucInfoCreated.textContent = currentUser.createdAt || '--';
                DOM.ucInfoLastLogin.textContent = currentUser.lastLogin || '--';
                DOM.ucInfoSessions.textContent = currentUser.sessionCount || '0';
                DOM.ucInfoReplays.textContent = currentUser.replayCount || '0';
            }
        }
        else if (data.message && data.message.indexOf('密码') >= 0) {
            // CHANGE_PASSWORD 响应
            DOM.ucOldPwd.value = ''; DOM.ucNewPwd.value = ''; DOM.ucNewPwd2.value = '';
            DOM.ucPwdMsg.textContent = data.message;
            DOM.ucPwdMsg.style.color = 'var(--accent-green)';
            addLog('success', data.message);
        }
        else {
            addLog('info', raw.data ? JSON.stringify(raw.data) : '操作完成');
        }
    } else {
        addLog('error', raw.error || '操作失败');
        DOM.loginBtn.disabled = false;
        DOM.loginBtn.textContent = '登 录 / 注 册';
        DOM.loginError.textContent = raw.error || '操作失败';
        // 密码修改失败
        if (raw.error && (raw.error.indexOf('密码') >= 0 || raw.error.indexOf('登录') >= 0)) {
            if (DOM.ucPwdMsg) { DOM.ucPwdMsg.textContent = raw.error; DOM.ucPwdMsg.style.color = 'var(--accent-red)'; }
        }
    }
}

// ==================== 演示模式 ====================

function startDemoMode() {
    if (isDemoMode) return;
    isDemoMode = true;
    state.mapWidth = parseInt(DOM.mapWidth.value) || DEFAULT_MAP_WIDTH;
    state.mapHeight = parseInt(DOM.mapHeight.value) || DEFAULT_MAP_HEIGHT;
    state.status = STATUS_RUNNING;
    state.tick = 0; state.elapsedMs = 0; state.exploredPercent = 0;
    _trendData = [];

    var totalCells = state.mapWidth * state.mapHeight;
    var density = parseInt(DOM.obstacleDensity.value) || DEFAULT_OBSTACLE_DENSITY;
    state.staticBlock = new Array(totalCells).fill(false);
    var obsTarget = Math.floor(totalCells * density / 100);
    var obsPlaced = 0;
    while (obsPlaced < obsTarget) { var ri = Math.floor(Math.random() * totalCells); if (!state.staticBlock[ri]) { state.staticBlock[ri] = true; obsPlaced++; } }
    state.obstacleCount = obsPlaced;
    state.mapView = new Array(totalCells).fill(false);
    state.dynamicBlock = new Array(totalCells).fill(false);

    var robotCount = parseInt(DOM.robotCount.value) || 5;
    state.cars = {};
    clearRobotColorCache();
    for (var r = 0; r < robotCount; r++) {
        var carId = 'car' + (r + 1);
        var px, py, attempts = 0;
        do { px = Math.floor(Math.random() * state.mapWidth); py = Math.floor(Math.random() * state.mapHeight); attempts++; }
        while (state.staticBlock[py * state.mapWidth + px] && attempts < 200);
        state.cars[carId] = { carId: carId, position: { x: px, y: py }, target: null, routeList: [], status: 'MOVING', stepsWalked: 0, battery: 100 };
        lightCell(px, py, 3);
    }
    state.connectedRobots = robotCount;
    demoStartTime = Date.now(); demoElapsedBeforePause = 0;
    _lastCarSnapshot = '';
    _lastStepsSnapshot = '';

    initCanvasAndLayout();
    demoInterval = setInterval(demoTick, DEMO_TICK_MS);
    addLog('success', '本地演示模式已启动（' + robotCount + ' 个机器人，密度 ' + density + '%）');
    updateControlButtons();
}

function stopDemoMode() { if (demoInterval) { clearInterval(demoInterval); demoInterval = null; } isDemoMode = false; demoStartTime = null; }

function demoTick() {
    if (state.status !== STATUS_RUNNING) return;
    state.tick++;
    var speed = parseInt(DOM.speedSlider.value) || 5;
    var movesPerTick = Math.max(1, Math.floor(speed / 3));
    var carIds = Object.keys(state.cars);

    carIds.forEach(function (carId) {
        var car = state.cars[carId];
        if (!car || car.status === 'BLOCKED' || car.status === 'OFFLINE') return;
        for (var move = 0; move < movesPerTick; move++) {
            if (!car.target || Math.random() < 0.02) assignDemoTarget(car);
            if (!car.target) continue;
            var dx = Math.sign(car.target.x - car.position.x);
            var dy = Math.sign(car.target.y - car.position.y);
            var moves = [];
            if (dx !== 0) moves.push({ dx: dx, dy: 0 });
            if (dy !== 0) moves.push({ dx: 0, dy: dy });
            if (dx !== 0 && dy !== 0) moves.push({ dx: dx, dy: dy });
            var moved = false;
            for (var mi = 0; mi < moves.length; mi++) {
                var nx = car.position.x + moves[mi].dx;
                var ny = car.position.y + moves[mi].dy;
                if (nx >= 0 && nx < state.mapWidth && ny >= 0 && ny < state.mapHeight) {
                    var idx = ny * state.mapWidth + nx;
                    if (!state.staticBlock[idx]) {
                        car.position.x = nx; car.position.y = ny; car.stepsWalked++; car.status = 'MOVING';
                        lightCell(nx, ny, 3); moved = true;
                        if (nx === car.target.x && ny === car.target.y) { car.target = null; car.routeList = []; }
                        break;
                    }
                }
            }
            if (!moved) {
                car.status = 'BLOCKED'; car.target = null; car.routeList = [];
                setTimeout((function (c) { return function () { if (c.status === 'BLOCKED') { c.status = 'MOVING'; assignDemoTarget(c); } }; })(car), 300);
                break;
            }
        }
        car.battery = Math.max(0, car.battery - 0.005 * movesPerTick);
    });

    var explored = 0;
    for (var i = 0; i < state.mapView.length; i++) { if (state.mapView[i]) explored++; }
    var totalCells = state.mapWidth * state.mapHeight;
    state.exploredPercent = Math.round(explored / (totalCells - state.obstacleCount) * 1000) / 10;
    state.elapsedMs = demoElapsedBeforePause + (Date.now() - demoStartTime);
    recordTrendPoint();
    stateDirty = true;
}

function assignDemoTarget(car) {
    var tx, ty, attempts = 0;
    do { tx = Math.floor(Math.random() * state.mapWidth); ty = Math.floor(Math.random() * state.mapHeight); attempts++; }
    while (state.staticBlock[ty * state.mapWidth + tx] && attempts < 100);
    car.target = { x: tx, y: ty }; car.status = 'MOVING';
}

function lightCell(cx, cy, radius) {
    for (var dy = -radius; dy <= radius; dy++) {
        for (var dx = -radius; dx <= radius; dx++) {
            var nx = cx + dx, ny = cy + dy;
            if (nx >= 0 && nx < state.mapWidth && ny >= 0 && ny < state.mapHeight && dx * dx + dy * dy <= radius * radius) {
                state.mapView[ny * state.mapWidth + nx] = true;
            }
        }
    }
}

// ==================== UI 更新 ====================

function updateUI() {
    var now = Date.now();
    if (now - lastDomUpdate < DOM_UPDATE_INTERVAL) return;
    lastDomUpdate = now;

    // 机器人卡片 — 仅当数据真正变化时才重建（防闪烁核心逻辑）
    var newSnapshot = makeCarSnapshot();
    if (newSnapshot !== _lastCarSnapshot) {
        _lastCarSnapshot = newSnapshot;
        updateRobotCards();
    }

    // 步数统计 — 同样增量比较
    if (newSnapshot !== _lastStepsSnapshot) {
        _lastStepsSnapshot = newSnapshot;
        updateStepsStats();
    }

    updateStatistics();
    updateStatusBar();
    updateSystemInfo();
    updateTrendChart();
    updateControlButtons();
}

/**
 * 生成机器人数据快照字符串 — 只包含影响 DOM 的字段
 */
function makeCarSnapshot() {
    var ids = Object.keys(state.cars).sort();
    var parts = [];
    ids.forEach(function (id) {
        var c = state.cars[id];
        parts.push(id + '|' + c.position.x + ',' + c.position.y + '|' + c.status + '|' + c.stepsWalked + '|' + Math.round(c.battery) + '|' + (c.target ? c.target.x + ',' + c.target.y : '-'));
    });
    return parts.join(';;');
}

// ---- 机器人状态卡片 ----
function updateRobotCards() {
    var container = DOM.robotCardsContainer;
    if (!container) return;
    var carIds = Object.keys(state.cars);

    if (carIds.length === 0) {
        container.innerHTML = '<div class="empty-hint">等待仿真数据...</div>';
        if (DOM.robotCountBadge) DOM.robotCountBadge.textContent = '0';
        return;
    }
    if (DOM.robotCountBadge) DOM.robotCountBadge.textContent = carIds.length;

    var html = '';
    carIds.forEach(function (carId) {
        var car = state.cars[carId];
        var color = getRobotColor(carId);
        var statusClass = (car.status || 'idle').toLowerCase();
        var statusText = getStatusText(car.status);
        var battery = car.battery !== undefined ? car.battery : 100;
        var batteryClass = battery > 60 ? 'high' : battery > 30 ? 'medium' : 'low';

        html += '<div class="robot-card">';
        html += '<div class="robot-card-header">';
        html += '<span class="robot-card-id"><span class="robot-card-dot" style="background:' + color + ';box-shadow:0 0 6px ' + color + ';"></span>' + carId + '</span>';
        html += '<span class="status-badge ' + statusClass + '">' + statusText + '</span>';
        html += '</div>';
        html += '<div class="robot-card-info">';
        html += '<div class="info-item"><span class="info-label">位置</span><span class="info-value">(' + car.position.x + ', ' + car.position.y + ')</span></div>';
        html += '<div class="info-item"><span class="info-label">步数</span><span class="info-value">' + (car.stepsWalked || 0) + '</span></div>';
        html += '</div>';
        if (car.target && car.target.x !== undefined) {
            html += '<div class="robot-card-target">目标: (' + car.target.x + ', ' + car.target.y + ')</div>';
        }
        html += '<div class="battery-bar"><div class="battery-fill ' + batteryClass + '" style="width:' + battery + '%;"></div></div>';
        html += '</div>';
    });
    container.innerHTML = html;
}

// ---- 步数统计 ----
function updateStepsStats() {
    var container = DOM.stepsStatsContainer;
    if (!container) return;
    var carIds = Object.keys(state.cars);
    if (carIds.length === 0) { container.innerHTML = '<div class="empty-hint">等待仿真数据...</div>'; if (DOM.stepsTotalValue) DOM.stepsTotalValue.textContent = '0'; return; }

    var maxSteps = 0, totalSteps = 0;
    carIds.forEach(function (id) { var s = state.cars[id].stepsWalked || 0; maxSteps = Math.max(maxSteps, s); totalSteps += s; });
    if (maxSteps === 0) maxSteps = 1;
    if (DOM.stepsTotalValue) DOM.stepsTotalValue.textContent = totalSteps;

    var html = '';
    carIds.forEach(function (carId) {
        var car = state.cars[carId];
        var color = getRobotColor(carId);
        var steps = car.stepsWalked || 0;
        var pct = Math.round(steps / maxSteps * 100);
        html += '<div class="step-stat-item">';
        html += '<span class="step-stat-dot" style="background:' + color + ';"></span>';
        html += '<span class="step-stat-id">' + carId + '</span>';
        html += '<div class="step-stat-bar-wrap"><div class="step-stat-bar-fill" style="width:' + pct + '%;background:' + color + ';"></div></div>';
        html += '<span class="step-stat-value">' + steps + '</span>';
        html += '</div>';
    });
    container.innerHTML = html;
}

// ---- 统计信息 ----
function updateStatistics() {
    if (DOM.coveragePercent) DOM.coveragePercent.textContent = state.exploredPercent + '%';
    if (DOM.coverageBar) DOM.coverageBar.style.width = state.exploredPercent + '%';
    var totalSteps = 0;
    Object.keys(state.cars).forEach(function (id) { totalSteps += state.cars[id].stepsWalked || 0; });
    if (DOM.totalSteps) DOM.totalSteps.textContent = totalSteps;
    if (DOM.obstacleCount) DOM.obstacleCount.textContent = state.obstacleCount;
    if (DOM.elapsedTime) DOM.elapsedTime.textContent = formatElapsed(state.elapsedMs);
    if (DOM.onlineRobotCount) DOM.onlineRobotCount.textContent = state.connectedRobots;
}

// ---- 系统信息 ----
function updateSystemInfo() {
    if (DOM.sysInfoStatus) {
        var dotClass = state.status === STATUS_RUNNING ? 'running' : state.status === STATUS_PAUSED ? 'paused' : 'idle';
        var statusText = state.status === STATUS_RUNNING ? '运行中' : state.status === STATUS_PAUSED ? '已暂停' : '待命';
        DOM.sysInfoStatus.innerHTML = '<span class="inline-dot ' + dotClass + '"></span> ' + statusText;
    }
    if (DOM.sysInfoTick) DOM.sysInfoTick.textContent = state.tick;
    if (DOM.sysInfoElapsed) DOM.sysInfoElapsed.textContent = formatElapsed(state.elapsedMs);
    if (DOM.sysInfoOnlineRobots) DOM.sysInfoOnlineRobots.textContent = state.connectedRobots;
    if (DOM.sysInfoMapSize) DOM.sysInfoMapSize.textContent = state.mapWidth + ' × ' + state.mapHeight;
    if (DOM.sysInfoObstacles) DOM.sysInfoObstacles.textContent = state.obstacleCount;
    if (DOM.sysInfoCoverage) DOM.sysInfoCoverage.textContent = state.exploredPercent + '%';
}

// ---- 趋势图 ----
function updateTrendChart() {
    if (DOM.chartCoverageValue) DOM.chartCoverageValue.textContent = '覆盖率: ' + state.exploredPercent + '%';
    drawTrendChart();
}

// ---- 底部状态栏 ----
function updateStatusBar() {
    if (DOM.statCoverage) DOM.statCoverage.textContent = state.exploredPercent + '%';
    var totalSteps = 0;
    Object.keys(state.cars).forEach(function (id) { totalSteps += state.cars[id].stepsWalked || 0; });
    if (DOM.statSteps) DOM.statSteps.textContent = totalSteps;
    if (DOM.statTick) DOM.statTick.textContent = state.tick;
    if (DOM.statTime) DOM.statTime.textContent = formatElapsed(state.elapsedMs);

    if (DOM.sysStatusDot && DOM.sysStatusText) {
        var dot = DOM.sysStatusDot, text = DOM.sysStatusText;
        dot.className = 'sys-status-dot';
        switch (state.status) {
            case STATUS_RUNNING: dot.classList.add('running'); text.textContent = '运行中'; break;
            case STATUS_PAUSED:  dot.classList.add('paused'); text.textContent = '已暂停'; break;
            default:             dot.classList.add('idle'); text.textContent = '待命'; break;
        }
    }
}

function updateControlButtons() {
    if (!DOM.btnStart || !DOM.btnPause || !DOM.btnReset) return;
    var running = state.status === STATUS_RUNNING;
    DOM.btnStart.disabled = running;
    DOM.btnPause.disabled = !running;
    DOM.btnReset.disabled = false;
}

function getStatusText(status) {
    var map = { 'IDLE': '空闲', 'WAITING_ROUTE': '等待路径', 'READY': '就绪', 'MOVING': '移动中', 'BLOCKED': '受阻', 'OFFLINE': '离线', 'INIT': '初始化' };
    return map[status] || status || '未知';
}

// ==================== 事件处理 ====================

function wireEvents() {
    // 导航标签切换
    if (DOM.navTabs) {
        DOM.navTabs.addEventListener('click', function (e) {
            var tab = e.target.closest('.nav-tab');
            if (!tab) return;
            DOM.navTabs.querySelectorAll('.nav-tab').forEach(function (t) { t.classList.remove('active'); });
            tab.classList.add('active');
            var tabName = tab.getAttribute('data-tab');
            hideAllCenterPanels();
            switch (tabName) {
                case 'user':     showUserCenter(); break;
                case 'replay':   showReplayPanel(); break;
                case 'settings': showSettingsPanel(); break;
                default:         showMapView(); break;
            }
        });
    }

    // 头像点击 → 登录弹窗
    DOM.userAvatar.addEventListener('click', function () {
        if (currentUser.userId) {
            showUserCenter();
        } else {
            showLoginModal();
        }
    });

    // 登录弹窗事件
    DOM.loginClose.addEventListener('click', hideLoginModal);
    DOM.loginOverlay.addEventListener('click', function (e) {
        if (e.target === DOM.loginOverlay) hideLoginModal();
    });
    DOM.loginBtn.addEventListener('click', doLogin);
    DOM.loginNickname.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { DOM.loginPassword.focus(); e.preventDefault(); }
        DOM.loginError.textContent = '';
    });
    DOM.loginPassword.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') doLogin();
        DOM.loginError.textContent = '';
    });

    // 用户中心事件
    DOM.ucLogoutBtn.addEventListener('click', doLogout);
    DOM.ucPrefSaveBtn.addEventListener('click', saveUserPreference);

    DOM.btnStart.addEventListener('click', function () {
        if (wsConnected) {
            // 真实模式 → 发 START 命令到后端 SimulationController
            var robotCount = parseInt(DOM.robotCount.value, 10);
            var mapWidth = parseInt(DOM.mapWidth.value, 10);
            var mapHeight = parseInt(DOM.mapHeight.value, 10);
            var density = parseInt(DOM.obstacleDensity.value, 10);
            var params = {
                robotCount: Number.isNaN(robotCount) ? 5 : robotCount,
                carCount: Number.isNaN(robotCount) ? 5 : robotCount,
                mapWidth: Number.isNaN(mapWidth) ? 26 : mapWidth,
                mapHeight: Number.isNaN(mapHeight) ? 16 : mapHeight,
                density: Number.isNaN(density) ? 25 : density,
                obstacleDensity: Number.isNaN(density) ? 25 : density,
                algorithm: document.querySelector('input[name="algorithm"]:checked')?.value || 'BFS'
            };
            state.mapWidth = params.mapWidth;
            state.mapHeight = params.mapHeight;
            recalcMapLayout();
            state.status = STATUS_RUNNING;
            state.startTimestamp = Date.now();
            state.elapsedMs = 0;
            sendCommand('SET_CONFIG', params);
            sendCommand('START');
            addLog('success', '仿真已启动（' + params.robotCount + '辆, ' + params.mapWidth + '×' + params.mapHeight + '）');
        } else if (isDemoMode) {
            state.status = STATUS_RUNNING;
            demoStartTime = Date.now();
            addLog('success', '仿真已启动（演示模式）');
        } else {
            startDemoMode();
        }
        updateControlButtons(); updateStatusBar(); updateSystemInfo();
    });

    DOM.btnPause.addEventListener('click', function () {
        state.status = STATUS_PAUSED;
        if (isDemoMode) {
            demoElapsedBeforePause = state.elapsedMs;
            demoStartTime = null;
            addLog('info', '仿真已暂停（演示模式）');
        } else if (wsConnected) {
            sendCommand('PAUSE');
            addLog('info', '仿真已暂停');
        }
        updateControlButtons(); updateStatusBar(); updateSystemInfo();
    });

    DOM.btnReset.addEventListener('click', function () {
        if (isDemoMode) stopDemoMode();
        if (wsConnected) sendCommand('RESET');
        state.tick = 0; state.elapsedMs = 0; state.exploredPercent = 0; state.status = STATUS_IDLE;
        state.startTimestamp = null; state.cars = {}; state.mapView = []; state.dynamicBlock = [];
        state.staticBlock = []; state.obstacleCount = 0; state.connectedRobots = 0; state.logs = [];
        _trendData = []; _lastCarSnapshot = ''; _lastStepsSnapshot = '';
        clearRobotColorCache(); clearMapBackground();
        if (DOM.mapImageInfo) DOM.mapImageInfo.style.display = 'none';
        if (DOM.imageFileName) DOM.imageFileName.textContent = '';
        if (DOM.mapImageInput) DOM.mapImageInput.value = '';
        demoElapsedBeforePause = 0; demoStartTime = null;
        if (isDemoMode) startDemoMode();
        updateControlButtons(); updateUI(); updateStatusBar(); updateSystemInfo();
        addLog('info', '仿真已重置');
    });

    DOM.speedSlider.addEventListener('input', function () {
        DOM.speedValue.textContent = this.value + 'x';
        if (isDemoMode && demoInterval) {
            clearInterval(demoInterval);
            demoInterval = setInterval(demoTick, Math.max(30, DEMO_TICK_MS - (parseInt(this.value) - 1) * 15));
        }
        if (wsConnected) {
            sendCommand('SET_SPEED', { speed: parseInt(this.value) });
        }
    });
    DOM.obstacleDensity.addEventListener('input', function () { DOM.densityValue.textContent = this.value + '%'; });

    DOM.btnRandomObs.addEventListener('click', function () {
        var density = parseInt(DOM.obstacleDensity.value, 10);
        if (Number.isNaN(density)) density = 25;
        if (isDemoMode) {
            var tc = state.mapWidth * state.mapHeight; state.staticBlock = new Array(tc).fill(false);
            var obsTarget = Math.floor(tc * density / 100), obsPlaced = 0;
            while (obsPlaced < obsTarget) { var ri = Math.floor(Math.random() * tc); if (!state.staticBlock[ri]) { state.staticBlock[ri] = true; obsPlaced++; } }
            state.obstacleCount = obsPlaced;
            addLog('success', '已随机生成 ' + obsPlaced + ' 个障碍物（密度 ' + density + '%）'); stateDirty = true;
        } else if (wsConnected) {
            sendCommand('RANDOM_OBSTACLE', { density: density });
            addLog('info', '已发送随机障碍物命令（密度 ' + density + '%）');
        }
        updateStatistics(); updateSystemInfo();
    });
    DOM.btnClearObs.addEventListener('click', function () {
        if (isDemoMode) {
            state.staticBlock = new Array(state.mapWidth * state.mapHeight).fill(false);
            state.obstacleCount = 0; addLog('success', '已清除全部障碍物'); stateDirty = true;
        } else if (wsConnected) {
            sendCommand('CLEAR_OBSTACLE');
            addLog('info', '已发送清除障碍物命令');
        }
        updateStatistics(); updateSystemInfo();
    });
    DOM.manualToggle.addEventListener('change', function () {
        DOM.manualHint.textContent = this.checked ? '点击地图格子可添加/移除障碍物（按住 Shift 添加动态障碍）' : '开启后可点击添加/移除障碍物';
        if (this.checked) DOM.carPlaceToggle.checked = false; // 互斥
    });
    DOM.carPlaceToggle.addEventListener('change', function () {
        DOM.carPlaceHint.textContent = this.checked ? '点击地图格子放置小车（Ctrl+点击移除）' : '开启后可点击地图放置小车';
        if (this.checked) DOM.manualToggle.checked = false; // 互斥
    });
    // ---- 地图背景上传 ----
    DOM.btnUploadMap.addEventListener('click', function () {
        DOM.mapImageInput.click();
    });
    DOM.mapImageInput.addEventListener('change', function () {
        var file = this.files[0];
        if (!file) return;
        if (!file.type.match(/^image\/(jpeg|png|gif|bmp|webp)$/)) {
            addLog('warn', '不支持的文件类型: ' + file.type + '，请选择 JPG/PNG 图片');
            return;
        }
        var reader = new FileReader();
        reader.onload = function (e) {
            var img = new Image();
            img.onload = function () {
                setMapBackground(img);
                DOM.imageFileName.textContent = file.name;
                DOM.mapImageInfo.style.display = 'flex';
                addLog('success', '已加载地图背景: ' + file.name + ' (' + img.width + '×' + img.height + 'px)');
                // 用图片尺寸更新地图宽高
                // 不强制更新，保持用户设置；图片会自动拉伸填充网格区域
            };
            img.src = e.target.result;
        };
        reader.readAsDataURL(file);
    });
    DOM.btnRemoveMap.addEventListener('click', function () {
        clearMapBackground();
        DOM.imageFileName.textContent = '';
        DOM.mapImageInfo.style.display = 'none';
        DOM.mapImageInput.value = '';
        addLog('info', '已移除地图背景');
    });

    // 画布悬停
    DOM.mapCanvas.addEventListener('mousemove', function (e) {
        var rect = DOM.mapCanvas.getBoundingClientRect();
        var cell = getCellAt(DOM.mapCanvas, e.clientX - rect.left, e.clientY - rect.top, layout, state.mapWidth, state.mapHeight);
        var tip = DOM.cellTooltip;
        if (cell && cell.col >= 0 && cell.col < state.mapWidth && cell.row >= 0 && cell.row < state.mapHeight) {
            var idx = cell.row * state.mapWidth + cell.col;
            var isObs = state.staticBlock[idx] === true, isDyn = state.dynamicBlock[idx] === true, isExp = state.mapView[idx] === true;
            var st = isObs ? '障碍物' : isDyn ? '动态障碍' : isExp ? '已探索' : '未探索';
            var sc = isObs ? '#ff4444' : isDyn ? '#ff9900' : isExp ? '#1ec4ff' : '#556677';
            var robotHere = '';
            Object.keys(state.cars).forEach(function (cid) { if (state.cars[cid].position.x === cell.col && state.cars[cid].position.y === cell.row) robotHere += ' ' + cid; });
            tip.innerHTML = '<span style="color:#8899aa;">坐标:</span> (' + cell.col + ', ' + cell.row + ')  <span style="color:#8899aa;">状态:</span> <span style="color:' + sc + ';">' + st + '</span>' + (robotHere ? '  <span style="color:#8899aa;">机器人:</span><span style="color:#1ec4ff;">' + robotHere + '</span>' : '');
            tip.classList.add('visible');
            var cx = e.clientX - DOM.mapArea.getBoundingClientRect().left + 16;
            var cy = e.clientY - DOM.mapArea.getBoundingClientRect().top - 30;
            if (cx + 200 > DOM.mapArea.getBoundingClientRect().width) cx -= 200;
            if (cy < 5) cy = e.clientY - DOM.mapArea.getBoundingClientRect().top + 16;
            tip.style.left = cx + 'px'; tip.style.top = cy + 'px';
        } else { tip.classList.remove('visible'); }
    });
    DOM.mapCanvas.addEventListener('mouseleave', function () { DOM.cellTooltip.classList.remove('visible'); });

    // 地图点击——障碍物编辑 / 小车部署
    DOM.mapCanvas.addEventListener('click', function (e) {
        var rect = DOM.mapCanvas.getBoundingClientRect();
        var cell = getCellAt(DOM.mapCanvas, e.clientX - rect.left, e.clientY - rect.top, layout, state.mapWidth, state.mapHeight);
        if (!cell) return;
        var idx = cell.row * state.mapWidth + cell.col;

        // 模式1：小车部署
        if (DOM.carPlaceToggle.checked) {
            if (e.ctrlKey || e.metaKey) {
                // Ctrl+点击 → 移除小车
                removeCarAt(cell.col, cell.row);
            } else {
                // 点击 → 添加小车
                addCarAt(cell.col, cell.row);
            }
            return;
        }

        // 模式2：障碍物编辑
        if (DOM.manualToggle.checked) {
            if (e.shiftKey) {
                state.dynamicBlock[idx] = !state.dynamicBlock[idx];
                addLog('debug', (state.dynamicBlock[idx] ? '添加' : '移除') + ' 动态障碍物 (' + cell.col + ', ' + cell.row + ')');
                if (wsConnected && !isDemoMode) sendCommand('SET_OBSTACLE', { row: cell.row, col: cell.col, value: state.dynamicBlock[idx] });
            } else {
                state.staticBlock[idx] = !state.staticBlock[idx];
                state.obstacleCount += state.staticBlock[idx] ? 1 : -1;
                state.obstacleCount = Math.max(0, state.obstacleCount);
                addLog('debug', (state.staticBlock[idx] ? '添加' : '移除') + ' 障碍物 (' + cell.col + ', ' + cell.row + ')');
                if (wsConnected && !isDemoMode) sendCommand('SET_OBSTACLE', { row: cell.row, col: cell.col, value: state.staticBlock[idx] });
            }
            stateDirty = true;
            updateStatistics(); updateSystemInfo();
            return;
        }
    });

    window.addEventListener('resize', function () {
        if (layout) { var rect = DOM.canvasWrapper.getBoundingClientRect(); layout = calcLayout(rect.width, rect.height, state.mapWidth, state.mapHeight); }
    });
    window.addEventListener('keydown', function (e) {
        if (e.key === ' ') { e.preventDefault(); if (state.status === STATUS_RUNNING) DOM.btnPause.click(); else DOM.btnStart.click(); }
        if ((e.key === 'r' || e.key === 'R') && e.ctrlKey) { e.preventDefault(); DOM.btnReset.click(); }
    });
}

// ==================== 登录弹窗 ====================

function showLoginModal() {
    DOM.loginOverlay.style.display = 'flex';
    DOM.loginNickname.value = '';
    DOM.loginPassword.value = '';
    DOM.loginError.textContent = '';
    DOM.loginHint.textContent = '已有账号输入昵称+密码登录，新用户自动注册';
    setTimeout(function () { DOM.loginNickname.focus(); }, 100);
}

function hideLoginModal() {
    DOM.loginOverlay.style.display = 'none';
}

function doLogin() {
    var nickname = DOM.loginNickname.value.trim();
    var password = DOM.loginPassword.value;
    if (!nickname) {
        DOM.loginError.textContent = '请输入昵称';
        return;
    }
    if (!password) {
        DOM.loginError.textContent = '请输入密码';
        return;
    }
    if (password.length < 6) {
        DOM.loginError.textContent = '密码至少需要6位';
        return;
    }
    if (!wsConnected) {
        DOM.loginError.textContent = 'WebSocket 未连接，无法登录';
        return;
    }
    DOM.loginBtn.disabled = true;
    DOM.loginBtn.textContent = '处理中...';
    sendCommand('LOGIN', { nickname: nickname, password: password });
}

// ==================== 用户中心 ====================

function hideAllCenterPanels() {
    // 直接用 getElementById 避免 DOM 缓存问题
    var panels = ['mapArea', 'bottomPanels', 'userCenterPanel', 'replayPanel', 'settingsPanel'];
    panels.forEach(function(id) {
        var el = document.getElementById(id);
        if (el) el.style.display = 'none';
    });
}

function showUserCenter() {
    DOM.navTabs.querySelectorAll('.nav-tab').forEach(function (t) { t.classList.remove('active'); });
    var userTab = DOM.navTabs.querySelector('[data-tab="user"]');
    if (userTab) userTab.classList.add('active');

    hideAllCenterPanels();
    var panel = document.getElementById('userCenterPanel');
    if (!panel) return;
    panel.style.display = 'flex';
    initPasswordEvents();
    loadUserProfile();
}

function showMapView() {
    DOM.navTabs.querySelectorAll('.nav-tab').forEach(function (t) { t.classList.remove('active'); });
    var realtimeTab = DOM.navTabs.querySelector('[data-tab="realtime"]');
    if (realtimeTab) realtimeTab.classList.add('active');

    hideAllCenterPanels();
    var mapArea = document.getElementById('mapArea');
    var bottomPanels = document.getElementById('bottomPanels');
    if (mapArea) mapArea.style.display = '';
    if (bottomPanels) bottomPanels.style.display = '';
}

function loadUserProfile() {
    // 有当前用户：加载详情
    if (currentUser.userId) {
        DOM.ucNickname.textContent = currentUser.nickname;
        DOM.ucUserId.textContent = 'ID: ' + currentUser.userId;
        DOM.ucInfoId.textContent = currentUser.userId;
        DOM.ucInfoNickname.textContent = currentUser.nickname;

        // 用 cookie（临时缓存）或默认值填充
        DOM.ucInfoCreated.textContent = currentUser.createdAt || '--';
        DOM.ucInfoLastLogin.textContent = currentUser.lastLogin || '--';
        DOM.ucInfoSessions.textContent = currentUser.sessionCount || '0';
        DOM.ucInfoReplays.textContent = currentUser.replayCount || '0';

        renderPreferences();
        DOM.ucHistoryContainer.innerHTML = '<p class="uc-empty">加载中...</p>';
        if (wsConnected) {
            sendCommand('GET_HISTORY');
        }
    } else {
        DOM.ucNickname.textContent = '未登录';
        DOM.ucUserId.textContent = 'ID: --';
        DOM.ucInfoId.textContent = '--';
        DOM.ucInfoNickname.textContent = '--';
        DOM.ucInfoCreated.textContent = '--';
        DOM.ucInfoLastLogin.textContent = '--';
        DOM.ucInfoSessions.textContent = '0';
        DOM.ucInfoReplays.textContent = '0';
        DOM.ucPrefsContainer.innerHTML = '<p class="uc-empty">请先登录</p>';
        DOM.ucHistoryContainer.innerHTML = '<p class="uc-empty">请先登录</p>';
    }
}

function renderPreferences() {
    var keys = Object.keys(currentUser.preferences).filter(function(k) { return k !== '__pwd'; });
    if (keys.length === 0) {
        DOM.ucPrefsContainer.innerHTML = '<p class="uc-empty">暂无偏好设置</p>';
        return;
    }
    var html = '';
    keys.forEach(function (k) {
        html += '<div class="uc-pref-item">';
        html += '<span class="uc-pref-key">' + escapeHtml(k) + '</span>';
        html += '<span class="uc-pref-value">' + escapeHtml(String(currentUser.preferences[k])) + '</span>';
        html += '<button class="uc-pref-remove" data-key="' + escapeHtml(k) + '" title="删除">✕</button>';
        html += '</div>';
    });
    DOM.ucPrefsContainer.innerHTML = html;

    // 删除偏好事件
    DOM.ucPrefsContainer.querySelectorAll('.uc-pref-remove').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var key = btn.getAttribute('data-key');
            sendCommand('SAVE_PREF', { key: key, value: '' });
            delete currentUser.preferences[key];
            renderPreferences();
        });
    });
}

function saveUserPreference() {
    var key = DOM.ucPrefKey.value.trim();
    var value = DOM.ucPrefValue.value.trim();
    if (!key) { addLog('warn', '请输入偏好键名'); return; }
    if (!wsConnected) { addLog('warn', 'WebSocket 未连接'); return; }
    sendCommand('SAVE_PREF', { key: key, value: value });
    currentUser.preferences[key] = value;
    DOM.ucPrefKey.value = '';
    DOM.ucPrefValue.value = '';
    renderPreferences();
    addLog('success', '偏好已保存: ' + key);
}

function doLogout() {
    if (!currentUser.userId) return;
    if (wsConnected) sendCommand('LOGOUT');
    currentUser.userId = null;
    currentUser.nickname = '未登录';
    currentUser.preferences = {};
    currentUser.createdAt = null;
    currentUser.lastLogin = null;
    currentUser.sessionCount = 0;
    currentUser.replayCount = 0;
    DOM.headerUserName.textContent = '未登录';
    DOM.ucNickname.textContent = '未登录';
    DOM.ucUserId.textContent = 'ID: --';
    showMapView();
    addLog('info', '已退出登录');
}

// ==================== 回放分析 ====================

/** 回放快照缓存 */
var replaySnapshots = [];
var replayPlaying = false;
var replayInterval = null;
var replayFrameIdx = 0;
var replayLayout = null;

function showReplayPanel() {
    DOM.replayPanel.style.display = 'flex';
    initReplayEvents();
    refreshReplaySessions();
    if (!replayLayout) initReplayCanvas();
}

function refreshReplaySessions() {
    var sel = DOM.replaySessionSelect;
    sel.innerHTML = '<option value="">-- 选择会话 --</option>';
    // 当前仿真会话（如果有 tick 数据）
    if (state.tick > 0) {
        sel.innerHTML += '<option value="current">当前会话 (Tick 0-' + state.tick + ')</option>';
    }
    // 从 coverageHistory 读取历史（实际应从 Redis 获取）
    if (state.coverageHistory && state.coverageHistory.length > 0) {
        sel.innerHTML += '<option value="last">上次会话 (' + state.coverageHistory.length + ' 个快照)</option>';
    }
}

function initReplayCanvas() {
    var canvas = DOM.replayCanvas;
    var wrap = canvas.parentElement;
    var dpr = window.devicePixelRatio || 1;
    canvas.width = wrap.clientWidth * dpr;
    canvas.height = wrap.clientHeight * dpr;
    canvas.style.width = wrap.clientWidth + 'px';
    canvas.style.height = wrap.clientHeight + 'px';
    replayLayout = calcLayout(wrap.clientWidth, wrap.clientHeight, state.mapWidth || 26, state.mapHeight || 16);
}

// 回放控制按钮（延迟注册）
function initReplayEvents() {
    if (!DOM.replayBtnPrev || DOM.replayBtnPrev._wired) return;
    DOM.replayBtnPrev._wired = true;
    DOM.replayBtnPrev.addEventListener('click', function () { replaySeek(-1); });
    DOM.replayBtnNext.addEventListener('click', function () { replaySeek(1); });
    DOM.replayBtnPlay.addEventListener('click', startReplay);
    DOM.replayBtnPause.addEventListener('click', pauseReplay);
    DOM.replayBtnStop.addEventListener('click', stopReplay);
    DOM.replaySpeed.addEventListener('input', function () {
        DOM.replaySpeedVal.textContent = this.value + 'x';
        if (replayPlaying) { pauseReplay(); startReplay(); }
    });
    DOM.replaySessionSelect.addEventListener('change', function () {
        loadReplaySession(this.value);
    });
}

function loadReplaySession(sessionId) {
    if (!sessionId) { replaySnapshots = []; replayFrameIdx = 0; updateReplayFrame(); return; }
    stopReplay();
    // 模拟加载快照（实际从 Redis / MQ 获取）
    var snaps = [];
    var total = state.coverageHistory.length || state.tick;
    if (total === 0) { addLog('warn', '暂无回放数据'); return; }
    for (var i = 0; i < Math.min(total, 100); i++) {
        snaps.push({
            tick: i,
            coverage: state.coverageHistory[i] ? parseFloat(state.coverageHistory[i].split(',')[1]) : state.exploredPercent * (i / total),
            staticBlock: state.staticBlock,
            cars: JSON.parse(JSON.stringify(state.cars))
        });
    }
    replaySnapshots = snaps;
    replayFrameIdx = 0;
    updateReplayFrame();
    addLog('success', '已加载 ' + snaps.length + ' 帧回放数据');
}

function startReplay() {
    if (replaySnapshots.length === 0) { addLog('warn', '请先选择会话'); return; }
    if (replayFrameIdx >= replaySnapshots.length - 1) replayFrameIdx = 0;
    replayPlaying = true;
    DOM.replayBtnPlay.disabled = true;
    DOM.replayBtnPause.disabled = false;
    DOM.replayBtnStop.disabled = false;
    var speed = parseInt(DOM.replaySpeed.value) || 3;
    var interval = Math.max(30, 400 - speed * 35);
    replayInterval = setInterval(function () { replaySeek(1); }, interval);
}

function pauseReplay() {
    replayPlaying = false;
    DOM.replayBtnPlay.disabled = false;
    DOM.replayBtnPause.disabled = true;
    if (replayInterval) { clearInterval(replayInterval); replayInterval = null; }
}

function stopReplay() {
    pauseReplay();
    replayFrameIdx = 0;
    updateReplayFrame();
    DOM.replayBtnStop.disabled = true;
    DOM.replayBtnPause.disabled = true;
    DOM.replayBtnPlay.disabled = false;
}

function replaySeek(delta) {
    if (replaySnapshots.length === 0) return;
    replayFrameIdx = Math.max(0, Math.min(replaySnapshots.length - 1, replayFrameIdx + delta));
    updateReplayFrame();
    if (replayFrameIdx >= replaySnapshots.length - 1) stopReplay();
}

function updateReplayFrame() {
    if (replaySnapshots.length === 0 || !replayLayout) {
        DOM.replayFrameInfo.textContent = '0/0';
        return;
    }
    DOM.replayFrameInfo.textContent = (replayFrameIdx + 1) + '/' + replaySnapshots.length;
    var frame = replaySnapshots[replayFrameIdx];
    DOM.replayCoverage.textContent = (frame.coverage || 0).toFixed(1) + '%';
    DOM.replayTick.textContent = frame.tick || 0;

    var totalSteps = 0;
    if (frame.cars) {
        Object.keys(frame.cars).forEach(function (id) { totalSteps += (frame.cars[id].stepsWalked || 0); });
    }
    DOM.replaySteps.textContent = totalSteps;

    // 渲染回放地图
    var canvas = DOM.replayCanvas;
    var ctx = canvas.getContext('2d');
    var dpr = window.devicePixelRatio || 1;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#061529';
    ctx.fillRect(0, 0, canvas.width / dpr, canvas.height / dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    var simState = {
        mapWidth: state.mapWidth || 26,
        mapHeight: state.mapHeight || 16,
        mapView: state.mapView,
        staticBlock: frame.staticBlock || state.staticBlock,
        dynamicBlock: state.dynamicBlock,
        exploredPercent: frame.coverage || 0,
        cars: frame.cars || {}
    };
    renderAll(canvas, simState, replayLayout);
}

// ==================== 系统设置 ====================

/** 默认设置 */
var defaultSettings = {
    redisHost: 'localhost', redisPort: 6379,
    mqHost: 'localhost', mqPort: 5672,
    mapWidth: 26, mapHeight: 16,
    robotCount: 5, density: 25,
    algorithm: 'BFS'
};

function showSettingsPanel() {
    DOM.settingsPanel.style.display = 'flex';
    initSettingsEvents();
    var s = loadSettings();
    document.getElementById('setRedisHost').value = s.redisHost;
    document.getElementById('setRedisPort').value = s.redisPort;
    document.getElementById('setMqHost').value = s.mqHost;
    document.getElementById('setMqPort').value = s.mqPort;
    document.getElementById('setMapWidth').value = s.mapWidth;
    document.getElementById('setMapHeight').value = s.mapHeight;
    document.getElementById('setRobotCount').value = s.robotCount;
    document.getElementById('setDensity').value = s.density;
    var algoRadio = document.querySelector('input[name="algorithm"][value="' + s.algorithm + '"]');
    if (algoRadio) algoRadio.checked = true;
}

function loadSettings() {
    try {
        var saved = localStorage.getItem('bbSimSettings');
        if (saved) { var parsed = JSON.parse(saved); return Object.assign({}, defaultSettings, parsed); }
    } catch (e) {}
    return Object.assign({}, defaultSettings);
}

function saveSettings() {
    var s = {
        redisHost: document.getElementById('setRedisHost').value.trim() || 'localhost',
        redisPort: parseInt(document.getElementById('setRedisPort').value) || 6379,
        mqHost: document.getElementById('setMqHost').value.trim() || 'localhost',
        mqPort: parseInt(document.getElementById('setMqPort').value) || 5672,
        mapWidth: parseInt(document.getElementById('setMapWidth').value) || 26,
        mapHeight: parseInt(document.getElementById('setMapHeight').value) || 16,
        robotCount: parseInt(document.getElementById('setRobotCount').value) || 5,
        density: parseInt(document.getElementById('setDensity').value) || 25,
        algorithm: document.querySelector('input[name="algorithm"]:checked')?.value || 'BFS'
    };
    localStorage.setItem('bbSimSettings', JSON.stringify(s));
    // 同步到偏好设置
    if (wsConnected && currentUser.userId) {
        sendCommand('SAVE_PREF', { key: 'settings', value: JSON.stringify(s) });
    }
    addLog('success', '设置已保存');
}

function resetSettings() {
    localStorage.removeItem('bbSimSettings');
    showSettingsPanel();
    addLog('info', '设置已恢复默认');
}

function initSettingsEvents() {
    if (!DOM.settingsSaveBtn || DOM.settingsSaveBtn._wired) return;
    DOM.settingsSaveBtn._wired = true;
    DOM.settingsSaveBtn.addEventListener('click', saveSettings);
    DOM.settingsResetBtn.addEventListener('click', resetSettings);
}

function initPasswordEvents() {
    if (!DOM.ucChangePwdBtn || DOM.ucChangePwdBtn._wired) return;
    DOM.ucChangePwdBtn._wired = true;
    DOM.ucChangePwdBtn.addEventListener('click', function () {
        var oldPwd = DOM.ucOldPwd.value;
        var newPwd = DOM.ucNewPwd.value;
        var newPwd2 = DOM.ucNewPwd2.value;
        if (!oldPwd) { DOM.ucPwdMsg.textContent = '请输入当前密码'; return; }
        if (!newPwd || newPwd.length < 6) { DOM.ucPwdMsg.textContent = '新密码至少6位'; return; }
        if (newPwd !== newPwd2) { DOM.ucPwdMsg.textContent = '两次密码不一致'; return; }
        if (!wsConnected) { DOM.ucPwdMsg.textContent = 'WebSocket 未连接'; return; }
        sendCommand('CHANGE_PASSWORD', { oldPassword: oldPwd, newPassword: newPwd });
        DOM.ucPwdMsg.textContent = '处理中...';
        DOM.ucPwdMsg.style.color = 'var(--accent-cyan)';
    });
}

// 启动时加载设置到界面
function applySettingsToUI() {
    try {
        var s = loadSettings();
        if (DOM.mapWidth && s.mapWidth != null) DOM.mapWidth.value = s.mapWidth;
        if (DOM.mapHeight && s.mapHeight != null) DOM.mapHeight.value = s.mapHeight;
        if (DOM.robotCount && s.robotCount != null) DOM.robotCount.value = s.robotCount;
        if (DOM.obstacleDensity && s.density != null) DOM.obstacleDensity.value = s.density;
    } catch(e) { console.log('applySettingsToUI skipped:', e.message); }
}

// ==================== 小车手动部署 ====================

function addCarAt(col, row) {
    // 检查数量上限
    var maxCars = parseInt(DOM.robotCount.value) || 5;
    var currentCount = Object.keys(state.cars).length;
    if (currentCount >= maxCars) {
        addLog('warn', '已达到机器人数量上限 ' + maxCars + ' 辆，请先移除或调大数量');
        return;
    }
    var idx = row * state.mapWidth + col;
    // 不能放在障碍物上
    if (state.staticBlock[idx]) { addLog('warn', '不能放在障碍物上 (' + col + ', ' + row + ')'); return; }
    // 不能放在已有小车上
    var carExists = false;
    Object.keys(state.cars).forEach(function (cid) {
        var c = state.cars[cid];
        if (c.position.x === col && c.position.y === row) carExists = true;
    });
    if (carExists) { addLog('warn', '该位置已有小车 (' + col + ', ' + row + ')'); return; }

    // 生成新 ID
    var nextNum = Object.keys(state.cars).length + 1;
    var carId = 'Car' + String(nextNum).padStart(3, '0');
    while (state.cars[carId]) {
        nextNum++;
        carId = 'Car' + String(nextNum).padStart(3, '0');
    }

    state.cars[carId] = {
        carId: carId,
        position: { x: col, y: row },
        target: null,
        routeList: [],
        status: 'IDLE',
        stepsWalked: 0,
        battery: 100
    };
    state.connectedRobots = Object.keys(state.cars).length;
    stateDirty = true;
    _lastCarSnapshot = '';
    _lastStepsSnapshot = '';
    updateRobotCards();
    updateStepsStats();
    addLog('success', '已部署小车 ' + carId + ' 位置 (' + col + ', ' + row + ')');
    // 同步到后端
    if (wsConnected) {
        sendCommand('ADD_CAR', { carId: carId, row: row, col: col });
    }
}

function removeCarAt(col, row) {
    var found = null;
    Object.keys(state.cars).forEach(function (cid) {
        var c = state.cars[cid];
        if (c.position.x === col && c.position.y === row) found = cid;
    });
    if (!found) { addLog('warn', '该位置没有小车 (' + col + ', ' + row + ')'); return; }
    var removed = state.cars[found];
    delete state.cars[found];
    state.connectedRobots = Object.keys(state.cars).length;
    stateDirty = true;
    _lastCarSnapshot = '';
    _lastStepsSnapshot = '';
    updateRobotCards();
    updateStepsStats();
    addLog('info', '已移除小车 ' + found + ' 位置 (' + col + ', ' + row + ')');
    if (wsConnected) {
        sendCommand('REMOVE_CAR', { carId: found });
    }
}

// ==================== 渲染循环 ====================

function startRenderLoop() {
    function loop() {
        animFrameId = requestAnimationFrame(loop);
        if (state.status === STATUS_RUNNING) {
            if (isDemoMode && demoStartTime) state.elapsedMs = demoElapsedBeforePause + (Date.now() - demoStartTime);
            else if (!isDemoMode && state.startTimestamp) state.elapsedMs = Date.now() - state.startTimestamp;
        }
        if (stateDirty) {
            var now = Date.now();
            if (now - lastDomUpdate >= DOM_UPDATE_INTERVAL) { updateUI(); stateDirty = false; lastDomUpdate = now; }
        }
        if (layout) renderAll(DOM.mapCanvas, state, layout);
    }
    animFrameId = requestAnimationFrame(loop);
}

// ==================== 日志 ====================

function addLog(level, message) {
    var now = new Date();
    var time = padZero(now.getHours()) + ':' + padZero(now.getMinutes()) + ':' + padZero(now.getSeconds());
    state.logs.push({ level: level, message: message, time: time });
    if (state.logs.length > LOG_MAX_ENTRIES) state.logs.shift();
    var container = DOM.logContainer; if (!container) return;
    var entry = document.createElement('div');
    entry.className = 'log-entry log-' + level;
    entry.innerHTML = '<span class="log-time">' + time + '</span><span class="log-msg">' + escapeHtml(message) + '</span>';
    container.appendChild(entry);
    container.scrollTop = container.scrollHeight;
}

// ==================== 辅助 ====================

function padZero(n) { return n < 10 ? '0' + n : '' + n; }
function formatElapsed(ms) { if (!ms || ms < 0) return '00:00:00'; var s = Math.floor(ms / 1000); return padZero(Math.floor(s/3600)) + ':' + padZero(Math.floor((s%3600)/60)) + ':' + padZero(s%60); }
function escapeHtml(str) { var d = document.createElement('div'); d.appendChild(document.createTextNode(str)); return d.innerHTML; }

window._simState = state;
window._simAddLog = addLog;
window._simStartDemo = startDemoMode;
window._simStopDemo = stopDemoMode;
