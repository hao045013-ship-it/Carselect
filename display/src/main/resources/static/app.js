/**
 * app.js — 应用控制器
 * 多机器人协作巡检仿真系统
 *
 * 职责：状态管理、WebSocket 通信、演示模式、
 *       DOM 更新（增量式，防闪烁）、事件处理、渲染循环
 */

// ==================== 常量与配置 ====================

var WEBSOCKET_URL = 'ws://' + window.location.hostname + ':8887';
var WS_RECONNECT_MAX = 30;
var DEFAULT_MAP_WIDTH = 26;
var DEFAULT_MAP_HEIGHT = 16;
var DEFAULT_OBSTACLE_DENSITY = 5;
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
    role: '',
    preferences: {}
};

/** 角色可访问的导航标签 */
var ROLE_TABS = {
    'admin':     ['settings', 'user'],
    'operator':  ['realtime', 'replay', 'user'],
    'analyst':   ['replay', 'stats', 'user']
};

/** 允许操作仿真控制（左侧面板）的角色 */
var OPERATOR_ROLES = ['operator'];

/** 用于防闪烁 — 缓存上次机器人数据快照 */
var _lastCarSnapshot = '';
var mouseState = { inside: false, x: 0, y: 0, col: -1, row: -1 };
/** 趋势图覆盖率历史（本地记录，与 coverageHistory 互补） */
var _trendData = [];

var layout = null;
var ws = null;
var wsReconnectCount = 0;
var wsReconnectTimer = null;
var wsConnected = false;
var animFrameId = null;
var lastDomUpdate = 0;
var DOM_UPDATE_INTERVAL = 100;
var stateDirty = false;
var _lastStepsSnapshot = '';

// ====================  初始化====================

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

    // 启动时显示登录界面，未登录不可进入操作界面
    showInitialLogin();
}

function cacheDomReferences() {
    DOM.systemTime = document.getElementById('systemTime');
    DOM.connectionStatus = document.getElementById('connectionStatus');

    //  导航
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
    DOM.btnApplyConfig = document.getElementById('btnApplyConfig');
    DOM.btnRandomObs = document.getElementById('btnRandomObs');
    DOM.btnClearObs = document.getElementById('btnClearObs');
    DOM.manualToggle = document.getElementById('manualToggle');
    DOM.manualHint = document.getElementById('manualHint');
    DOM.btnRandomCars = document.getElementById('btnRandomCars');
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
    DOM.loginCard = document.querySelector('.login-card');
    DOM.loginModeTabs = document.getElementById('loginModeTabs');
    DOM.loginCardTitle = document.getElementById('loginCardTitle');
    DOM.loginNickname = document.getElementById('loginNickname');
    DOM.loginPassword = document.getElementById('loginPassword');
    DOM.loginConfirmPassword = document.getElementById('loginConfirmPassword');
    DOM.loginRole = document.getElementById('loginRole');
    DOM.confirmPasswordField = document.getElementById('confirmPasswordField');
    DOM.roleField = document.getElementById('roleField');
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

    // 统计分析
    DOM.statsPanel = document.getElementById('statsPanel');
    DOM.statsSessionSelect = document.getElementById('statsSessionSelect');
    DOM.statsOverviewCards = document.getElementById('statsOverviewCards');
    DOM.statsOperatorId = document.getElementById('statsOperatorId');
    DOM.statsCompareSelect = document.getElementById('statsCompareSelect');
    DOM.statsCompareBtn = document.getElementById('statsCompareBtn');
    DOM.statsCompareCanvas = document.getElementById('statsCompareCanvas');
    DOM.statsCompareWrap = document.getElementById('statsCompareWrap');
    DOM.statsHeatmapBtn = document.getElementById('statsHeatmapBtn');
    DOM.statsHeatmapCanvas = document.getElementById('statsHeatmapCanvas');
    DOM.statsHeatmapWrap = document.getElementById('statsHeatmapWrap');
    DOM.statsHeatmapTooltip = document.getElementById('statsHeatmapTooltip');
    DOM.statsCoverage = document.getElementById('statsCoverage');
    DOM.statsTotalMoves = document.getElementById('statsTotalMoves');
    DOM.statsTotalBlocked = document.getElementById('statsTotalBlocked');
    DOM.statsBlockedRate = document.getElementById('statsBlockedRate');
    DOM.statsDuration = document.getElementById('statsDuration');
    DOM.statsNavEfficiency = document.getElementById('statsNavEfficiency');
    DOM.statsPredictConfidence = document.getElementById('statsPredictConfidence');
    DOM.statsCoverageCanvas = document.getElementById('statsCoverageCanvas');
    DOM.statsCoverageWrap = document.getElementById('statsCoverageWrap');
    DOM.statsCarCanvas = document.getElementById('statsCarCanvas');
    DOM.statsCarWrap = document.getElementById('statsCarWrap');

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
    DOM.ucRoleBadge = document.getElementById('ucRoleBadge');
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

    // 搴曢儴
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

    // 鑳屾櫙缃戞牸
    ctx.strokeStyle = 'rgba(30,58,95,0.2)';
    ctx.lineWidth = 0.5;
    var gridLines = 5;
    for (var g = 0; g <= gridLines; g++) {
        var gy = padTop + (ph / gridLines) * g;
        ctx.beginPath(); ctx.moveTo(padLeft, gy); ctx.lineTo(w - padRight, gy); ctx.stroke();
        //  Y 标签
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

    //绘制折线
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

// ==================== 状态规范化  ====================

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

    // 璁板綍瓒嬪娍鏁版嵁
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
            // 命令响应（含 success 字段）vs 仿真状态广播
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
        } catch (e) { addLog('error', 'JSON解析失败: ' + e.message); }
    };
    ws.onclose = function () { wsConnected = false; updateConnectionUI(false); addLog('warn', 'WebSocket 连接已断开'); scheduleReconnect(); };
    ws.onerror = function () { wsConnected = false; updateConnectionUI(false); };

    // 不再自动启动演示模式，保持静默等待后端连接
}

function scheduleReconnect() {
    if (wsReconnectCount >= WS_RECONNECT_MAX) { addLog('warn', 'WebSocket 重连已达最大次数，请检查后端是否启动'); return; }
    var delay = Math.min(1000 * Math.pow(2, wsReconnectCount), 30000);
    wsReconnectCount++;
    addLog('info', '将在 ' + (delay/1000) + ' 秒后尝试重连 (' + wsReconnectCount + '/' + WS_RECONNECT_MAX + ')');
    if (wsReconnectTimer) clearTimeout(wsReconnectTimer);
    wsReconnectTimer = setTimeout(tryConnectWebSocket, delay);
}

function onWsFail() { wsConnected = false; updateConnectionUI(false); addLog('warn', '无法创建 WebSocket 连接，请检查后端是否启动'); }

function updateConnectionUI(connected) {
    var el = DOM.connectionStatus; if (!el) return;
    el.className = 'connection-status ' + (connected ? 'connected' : 'disconnected');
    el.querySelector('.status-text').textContent = connected ? '已连接' : '未连接';
}

function sendCommand(cmd, data) {
    var payload = { command: cmd }; if (data) payload.data = data;
    if (ws && ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify(payload)); }
    else { addLog('warn', 'WebSocket 未连接，无法发送命令 ' + cmd); }
}

/** 处理后端返回的命令响应（用户操作等） */
function handleCommandResponse(raw) {
    if (raw.success) {
        var data = raw.data || {};

        // LOGIN / REGISTER 响应
        if (data.userId && data.nickname) {
            currentUser.userId = data.userId;
            currentUser.nickname = data.nickname;
            // 角色：优先使用后端返回的 role，其次保留注册时暂存的值
            currentUser.role = data.role || currentUser.role || '';
            currentUser.preferences = data.preferences || {};
            currentUser.createdAt = data.createdAt || null;
            currentUser.lastLogin = data.lastLogin || null;
            currentUser.sessionCount = data.sessionCount || 0;
            currentUser.replayCount = data.replayCount || 0;
            DOM.headerUserName.textContent = data.nickname;
            DOM.loginBtn.disabled = false;
            DOM.loginBtn.textContent = '登 录';
            DOM.loginError.textContent = '';
            if (DOM.ucPwdMsg) DOM.ucPwdMsg.textContent = '';
            // 登录/注册成功，关闭登录弹窗，显示主界面
            DOM.loginOverlay.style.display = 'none';
            DOM.loginClose.style.display = '';
            filterNavTabsByRole(currentUser.role);
            filterLeftPanelByRole(currentUser.role);
            addLog('success', data.message || ('登录成功: ' + data.nickname));
        }
        // LOGOUT 响应
        else if (data.message && data.message.indexOf('鐧诲嚭') >= 0) {
            currentUser.userId = null;
            currentUser.nickname = '未登录';
            currentUser.role = '';
            currentUser.preferences = {};
            DOM.headerUserName.textContent = '未登录';
            filterLeftPanelByRole('');
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
        // 障碍物通用命令响应
        else if (raw.message) {
            addLog('success', raw.message);
        }
        else {
            addLog('info', raw.data ? JSON.stringify(raw.data) : '操作完成');
        }
    } else {
        addLog('error', raw.error || '操作失败');
        DOM.loginBtn.disabled = false;
        DOM.loginBtn.textContent = getLoginMode() === 'register' ? '注 册' : '登 录';
        DOM.loginError.textContent = raw.error || '操作失败';
        // 仅在密码修改/密码相关错误时显示到用户中心
        if (raw.error && raw.error.indexOf('密码') >= 0) {
            if (DOM.ucPwdMsg) { DOM.ucPwdMsg.textContent = raw.error; DOM.ucPwdMsg.style.color = 'var(--accent-red)'; }
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

// ---- 机器人状态卡片----
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

// ----步数统计----
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

// ----趋势图----
function updateTrendChart() {
    if (DOM.chartCoverageValue) DOM.chartCoverageValue.textContent = '覆盖率 ' + state.exploredPercent + '%';
    drawTrendChart();
}

// ---- 底部状态栏----
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

// ====================事件处理 ====================

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
                case 'stats':    showStatsPanel(); break;
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
        // 未登录时不允许点击遮罩关闭
        if (e.target === DOM.loginOverlay && currentUser.userId) hideLoginModal();
    });
    // 登录/注册 模式切换
    DOM.loginModeTabs.addEventListener('click', function (e) {
        var tab = e.target.closest('.login-mode-tab');
        if (!tab) return;
        var mode = tab.getAttribute('data-mode');
        switchLoginMode(mode);
    });
    DOM.loginBtn.addEventListener('click', doLogin);
    DOM.loginNickname.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { DOM.loginPassword.focus(); e.preventDefault(); }
        DOM.loginError.textContent = '';
    });
    DOM.loginPassword.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            // 注册模式下跳转到确认密码，登录模式下直接提交
            if (getLoginMode() === 'register') {
                DOM.loginConfirmPassword.focus(); e.preventDefault();
            } else {
                doLogin();
            }
        }
        DOM.loginError.textContent = '';
    });
    DOM.loginConfirmPassword.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { DOM.loginRole.focus(); e.preventDefault(); }
        DOM.loginError.textContent = '';
    });

    // 用户中心事件
    DOM.ucLogoutBtn.addEventListener('click', doLogout);
    DOM.ucPrefSaveBtn.addEventListener('click', saveUserPreference);

    DOM.btnStart.addEventListener('click', function () {
        if (!wsConnected) {
            addLog('warn', 'WebSocket 未连接，无法启动仿真');
            updateControlButtons(); updateStatusBar(); updateSystemInfo();
            return;
        }
        var carCount = Object.keys(state.cars).length;
        if (carCount === 0) {
            addLog('warn', '没有部署小车，无法启动仿真。请先部署小车（随机部署或手动部署）。');
            return;
        }
        state.status = STATUS_RUNNING;
        state.startTimestamp = Date.now();
        state.elapsedMs = 0;
        sendCommand('START', { operatorId: currentUser.userId || '' });
        addLog('success', '仿真已启动（' + carCount + ' 辆小车）');
        updateControlButtons(); updateStatusBar(); updateSystemInfo();
    });

    if (DOM.btnApplyConfig) {
        DOM.btnApplyConfig.addEventListener('click', applySimulationConfig);
    }

    DOM.btnPause.addEventListener('click', function () {
        state.status = STATUS_PAUSED;
        if (wsConnected) {
            sendCommand('PAUSE');
            addLog('info', '仿真已暂停');
        }
        updateControlButtons(); updateStatusBar(); updateSystemInfo();
    });

    DOM.btnReset.addEventListener('click', function () {
        if (wsConnected) sendCommand('RESET');
        state.tick = 0; state.elapsedMs = 0; state.exploredPercent = 0; state.status = STATUS_IDLE;
        state.startTimestamp = null; state.cars = {}; state.mapView = []; state.dynamicBlock = [];
        state.staticBlock = []; state.obstacleCount = 0; state.connectedRobots = 0; state.logs = [];
        _trendData = []; _lastCarSnapshot = ''; _lastStepsSnapshot = '';
        clearRobotColorCache(); clearMapBackground();
        if (DOM.mapImageInfo) DOM.mapImageInfo.style.display = 'none';
        if (DOM.imageFileName) DOM.imageFileName.textContent = '';
        if (DOM.mapImageInput) DOM.mapImageInput.value = '';
        updateControlButtons(); updateUI(); updateStatusBar(); updateSystemInfo();
        addLog('info', '仿真已重置');
    });

    DOM.speedSlider.addEventListener('input', function () {
        DOM.speedValue.textContent = this.value + 'x';
        if (wsConnected) {
            sendCommand('SET_SPEED', { speed: parseInt(this.value) });
        }
    });
    DOM.obstacleDensity.addEventListener('input', function () { DOM.densityValue.textContent = this.value + '%'; });
    DOM.robotCount.addEventListener('input', function () {
        var v = parseInt(this.value, 10);
        if (Number.isNaN(v) || v < 1) { this.value = 1; }
    });
    DOM.robotCount.addEventListener('change', function () {
        var v = parseInt(this.value, 10);
        if (Number.isNaN(v) || v < 1) { this.value = 1; }
    });

    DOM.btnRandomObs.addEventListener('click', function () {
        var density = parseInt(DOM.obstacleDensity.value, 10);
        if (Number.isNaN(density)) density = 5;
        if (density > 20) {
            addLog('warn', '障碍物密度不能超过 20%');
            density = 20;
            DOM.obstacleDensity.value = 20;
            DOM.densityValue.textContent = '20%';
        }
        if (wsConnected) {
            sendCommand('RANDOM_OBSTACLE', { density: density });
            addLog('info', '已发送随机障碍物命令（密度 ' + density + '%）');
        } else {
            addLog('warn', 'WebSocket 未连接，无法生成障碍物');
        }
        updateStatistics(); updateSystemInfo();
    });

    if (DOM.btnRandomCars) {
        DOM.btnRandomCars.addEventListener('click', addRandomCars);
    }

    DOM.btnClearObs.addEventListener('click', function () {
        if (wsConnected) {
            sendCommand('CLEAR_OBSTACLE');
            addLog('info', '已发送清除障碍物命令');
        } else {
            addLog('warn', 'WebSocket 未连接，无法清除障碍物');
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
                addLog('success', '已加载地图背景 ' + file.name + ' (' + img.width + '×' + img.height + 'px)');
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

    //画布悬停
    DOM.mapCanvas.addEventListener('mousemove', function (e) {
        var rect = DOM.mapCanvas.getBoundingClientRect();
        //用于更新鼠标位置和网格坐标
        var mx = e.clientX - rect.left;
        var my = e.clientY - rect.top;
        mouseState.inside = true;
        mouseState.x = mx;
        mouseState.y = my;
        var cell = getCellAt(DOM.mapCanvas, mx, my, layout, state.mapWidth, state.mapHeight);
        if (cell) {
            mouseState.col = cell.col;
            mouseState.row = cell.row;
        } else {
            mouseState.col = -1;
            mouseState.row = -1;
        }
        //
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
    DOM.mapCanvas.addEventListener('mouseleave', function () {
        DOM.cellTooltip.classList.remove('visible');
        mouseState.inside = false;
        mouseState.col = -1;
        mouseState.row = -1;
    });
    // 地图点击——障碍物编辑 / 小车部署
    DOM.mapCanvas.addEventListener('click', function (e) {
        var rect = DOM.mapCanvas.getBoundingClientRect();
        var cell = getCellAt(DOM.mapCanvas, e.clientX - rect.left, e.clientY - rect.top, layout, state.mapWidth, state.mapHeight);
        if (!cell) return;
        var idx = cell.row * state.mapWidth + cell.col;

        // 小车部署
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
                if (wsConnected) sendCommand('SET_OBSTACLE', { row: cell.row, col: cell.col, value: state.dynamicBlock[idx] });
            } else {
                state.staticBlock[idx] = !state.staticBlock[idx];
                state.obstacleCount += state.staticBlock[idx] ? 1 : -1;
                state.obstacleCount = Math.max(0, state.obstacleCount);
                addLog('debug', (state.staticBlock[idx] ? '添加' : '移除') + ' 障碍物 (' + cell.col + ', ' + cell.row + ')');
                if (wsConnected) sendCommand('SET_OBSTACLE', { row: cell.row, col: cell.col, value: state.staticBlock[idx] });
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

/** 启动时显示登录界面 —— 未登录状态下不可关闭 */
function showInitialLogin() {
    DOM.loginOverlay.style.display = 'flex';
    DOM.loginClose.style.display = 'none';
    resetLoginForm();
    switchLoginMode('login');
    DOM.loginHint.textContent = '请先登录或注册，注册请切换上方标签';
    setTimeout(function () { DOM.loginNickname.focus(); }, 100);
}

function showLoginModal() {
    DOM.loginOverlay.style.display = 'flex';
    // 已登录用户可关闭弹窗，未登录则隐藏关闭按钮
    DOM.loginClose.style.display = currentUser.userId ? '' : 'none';
    resetLoginForm();
    switchLoginMode('login');
    DOM.loginHint.textContent = '已有账号输入昵称+密码登录，新用户请切换至注册';
    setTimeout(function () { DOM.loginNickname.focus(); }, 100);
}

/** 重置登录表单 */
function resetLoginForm() {
    DOM.loginNickname.value = '';
    DOM.loginPassword.value = '';
    DOM.loginConfirmPassword.value = '';
    DOM.loginRole.value = 'analyst';
    DOM.loginError.textContent = '';
    DOM.loginBtn.disabled = false;
    DOM.loginBtn.textContent = '登 录';
}

function hideLoginModal() {
    // 未登录状态下不允许关闭登录弹窗
    if (!currentUser.userId) return;
    DOM.loginOverlay.style.display = 'none';
    DOM.loginClose.style.display = '';
}

/** 切换登录/注册模式 */
function switchLoginMode(mode) {
    var tabs = DOM.loginModeTabs.querySelectorAll('.login-mode-tab');
    tabs.forEach(function (t) {
        t.classList.toggle('active', t.getAttribute('data-mode') === mode);
    });
    if (mode === 'register') {
        DOM.loginCard.classList.add('register-mode');
        DOM.loginBtn.textContent = '注 册';
        DOM.loginHint.textContent = '填写昵称和密码完成注册，密码至少6位';
    } else {
        DOM.loginCard.classList.remove('register-mode');
        DOM.loginBtn.textContent = '登 录';
        DOM.loginHint.textContent = '已有账号？输入昵称+密码登录。新用户请切换至注册';
    }
    DOM.loginError.textContent = '';
}

/** 获取当前登录模式 */
function getLoginMode() {
    var activeTab = DOM.loginModeTabs.querySelector('.login-mode-tab.active');
    return activeTab ? activeTab.getAttribute('data-mode') : 'login';
}

/** 根据角色过滤导航标签 */
function filterNavTabsByRole(role) {
    var tabs = DOM.navTabs.querySelectorAll('.nav-tab');
    var allowed = ROLE_TABS[role] || [];
    if (allowed.length === 0) return; // 无角色不过滤
    var firstVisible = null;

    tabs.forEach(function (tab) {
        var name = tab.getAttribute('data-tab');
        if (allowed.indexOf(name) >= 0) {
            tab.style.display = '';
            if (!firstVisible) firstVisible = tab;
        } else {
            tab.style.display = 'none';
        }
    });

    // 如果当前激活标签被隐藏，切换到第一个可见标签
    var active = DOM.navTabs.querySelector('.nav-tab.active');
    if (!active || active.style.display === 'none') {
        if (firstVisible) firstVisible.click();
    }
}

/** 根据角色控制左侧仿真操作面板的显示 */
function filterLeftPanelByRole(role) {
    var leftPanel = document.getElementById('leftPanel');
    if (!leftPanel) return;
    if (OPERATOR_ROLES.indexOf(role) >= 0) {
        leftPanel.style.display = '';
    } else {
        leftPanel.style.display = 'none';
    }
}

function doLogin() {
    var nickname = DOM.loginNickname.value.trim();
    var password = DOM.loginPassword.value;
    var mode = getLoginMode();

    if (!nickname) {
        DOM.loginError.textContent = '请输入昵称';
        return;
    }
    if (!password) {
        DOM.loginError.textContent = '请输入密码';
        return;
    }
    if (password.length < 6) {
        DOM.loginError.textContent = '密码至少需要 6 位';
        return;
    }

    // 注册模式：校验确认密码 + 密码强度
    if (mode === 'register') {
        var confirmPassword = DOM.loginConfirmPassword.value;
        if (!confirmPassword) {
            DOM.loginError.textContent = '请再次输入密码进行确认';
            return;
        }
        if (password !== confirmPassword) {
            DOM.loginError.textContent = '两次输入的密码不一致，请重新输入';
            return;
        }
        if (!isStrongPassword(password)) {
            DOM.loginError.textContent = '密码强度不足：需包含大写字母、小写字母、数字、特殊字符中的至少三种';
            return;
        }
    }

    if (!wsConnected) {
        DOM.loginError.textContent = 'WebSocket 未连接，无法操作';
        return;
    }

    DOM.loginBtn.disabled = true;
    DOM.loginBtn.textContent = mode === 'register' ? '注册中...' : '登录中...';

    if (mode === 'register') {
        var selectedRole = DOM.loginRole.value;
        currentUser.role = selectedRole;
        sendCommand('REGISTER', { nickname: nickname, password: password, role: selectedRole });
    } else {
        sendCommand('LOGIN', { nickname: nickname, password: password });
    }
}

/** 密码强度校验：需包含大写、小写、数字、特殊字符中的至少三种 */
function isStrongPassword(pwd) {
    var types = 0;
    if (/[A-Z]/.test(pwd)) types++;
    if (/[a-z]/.test(pwd)) types++;
    if (/[0-9]/.test(pwd)) types++;
    if (/[^A-Za-z0-9]/.test(pwd)) types++;
    return types >= 3;
}

// ==================== 用户中心 ====================

function hideAllCenterPanels() {
    // 直接用 getElementById 避免 DOM 缓存问题
    var panels = ['mapArea', 'bottomPanels', 'userCenterPanel', 'replayPanel', 'statsPanel', 'settingsPanel'];
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
        // 显示角色徽章
        if (currentUser.role && DOM.ucRoleBadge) {
            DOM.ucRoleBadge.textContent = getRoleLabel(currentUser.role);
            DOM.ucRoleBadge.className = 'user-center-role role-' + currentUser.role;
            DOM.ucRoleBadge.style.display = 'inline-block';
        } else if (DOM.ucRoleBadge) {
            DOM.ucRoleBadge.style.display = 'none';
        }

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
        if (DOM.ucRoleBadge) DOM.ucRoleBadge.style.display = 'none';
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
        html += '<button class="uc-pref-remove" data-key="' + escapeHtml(k) + '" title="删除">×</button>';
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
    currentUser.role = '';
    currentUser.preferences = {};
    currentUser.createdAt = null;
    currentUser.lastLogin = null;
    currentUser.sessionCount = 0;
    currentUser.replayCount = 0;
    DOM.headerUserName.textContent = '未登录';
    DOM.ucNickname.textContent = '未登录';
    DOM.ucUserId.textContent = 'ID: --';
    filterLeftPanelByRole('');
    showMapView();
    addLog('info', '已退出登录');
    // 退出后显示登录弹窗
    showInitialLogin();
}

// ==================== 回放分析 ====================

/** 回放快照缓存 */
var replaySnapshots = [];
var replayPlaying = false;
var replayInterval = null;
var replayFrameIdx = 0;
var replayLayout = null;
var REPLAY_API_BASE = window.location.protocol + '//' + window.location.hostname + ':8084/api/replay';
var REPLAY_CHUNK_SIZE = 200;
var REPLAY_PREFETCH_THRESHOLD = 40;
var replaySessionId = '';
var replaySessionMode = 'current';
var replayNextFromTick = 0;
var replayLoadingChunk = false;
var replayReachedEnd = false;
var replayLoadToken = 0;
var replaySessionMeta = {};  // sessionId → {operatorId, carCount, ...}

function showReplayPanel() {
    DOM.replayPanel.style.display = 'flex';
    initReplayEvents();
    refreshReplaySessions();
    if (!replayLayout) initReplayCanvas();
}

function refreshReplaySessions() {
    var sel = DOM.replaySessionSelect;
    sel.innerHTML = '<option value="">-- 选择会话 --</option>';
    sel.innerHTML += '<option value="current">当前会话（Redis 实时快照）</option>';

    fetch(REPLAY_API_BASE + '/sessions?page=0&size=20')
        .then(function (res) { return res.json(); })
        .then(function (data) {
            var sessions = data.data || [];
            replaySessionMeta = {};
            sessions.forEach(function (s) {
                replaySessionMeta[s.sessionId] = s;
                var label = formatReplaySessionLabel(s);
                sel.innerHTML += '<option value="' + escapeHtml(s.sessionId) + '">' + escapeHtml(label) + '</option>';
            });
        })
        .catch(function () {
            addLog('warn', '未连接到回放服务 8084，仅可尝试当前会话快照');
        });
}

function formatReplaySessionLabel(session) {
    var id = (session.sessionId || '').slice(0, 8);
    var started = session.startTime ? formatDateTime(session.startTime) : id;
    var cars = (session.carCount || 0) + '车';
    var duration = session.endTime ? formatDuration(session.endTime - session.startTime) : '进行中';
    var operator = session.operatorId ? ' | 操作员: ' + session.operatorId : '';
    return started + ' · ' + cars + ' · ' + duration + operator;
}

function formatDateTime(ms) {
    var d = new Date(ms);
    var pad = function (n) { return String(n).padStart(2, '0'); };
    return pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
}

function formatDuration(ms) {
    ms = Math.max(0, ms || 0);
    var seconds = Math.floor(ms / 1000);
    var minutes = Math.floor(seconds / 60);
    seconds = seconds % 60;
    if (minutes <= 0) return seconds + '秒';
    return minutes + '分' + seconds + '秒';
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
    replayLoadToken++;
    if (!sessionId) { replaySnapshots = []; replayFrameIdx = 0; updateReplayFrame(); return; }
    stopReplay();
    var token = replayLoadToken;
    replaySnapshots = [];
    replayFrameIdx = 0;
    replaySessionId = sessionId;
    replaySessionMode = sessionId === 'current' ? 'current' : 'history';
    replayNextFromTick = 0;
    replayLoadingChunk = false;
    replayReachedEnd = false;
    // 显示操作员
    var meta = replaySessionMeta[sessionId];
    DOM.replaySessionId.textContent = (meta && meta.operatorId) ? meta.operatorId : '--';
    updateReplayFrame();

    if (replaySessionMode === 'history') {
        loadReplayChunk(0, token);
        return;
    }

    fetch(REPLAY_API_BASE + '/current/snapshots')
        .then(function (res) { return res.json(); })
        .then(function (data) {
            if (token !== replayLoadToken || replaySessionId !== sessionId) return;
            var rawSnapshots = data.snapshots || [];
            var snaps = rawSnapshots.map(normalizeReplaySnapshot).filter(Boolean);
            if (snaps.length === 0) {
                addLog('warn', '暂无可用回放快照，请确认 replay-logger 在仿真开始前已启动');
                replaySnapshots = [];
                replayFrameIdx = 0;
                updateReplayFrame();
                return;
            }
            replaySnapshots = snaps.sort(compareReplaySnapshots);
            replayFrameIdx = 0;
            updateReplayFrame();
            addLog('success', '已加载 ' + snaps.length + ' 帧真实回放快照');
        })
        .catch(function (err) {
            if (token !== replayLoadToken || replaySessionId !== sessionId) return;
            addLog('error', '加载回放失败：' + err.message);
        });
}

function loadReplayChunk(fromTick, token) {
    if (replaySessionMode !== 'history' || !replaySessionId || replayLoadingChunk || replayReachedEnd) return;
    token = token || replayLoadToken;
    replayLoadingChunk = true;
    var requestedSessionId = replaySessionId;

    var toTick = fromTick + REPLAY_CHUNK_SIZE - 1;
    var url = REPLAY_API_BASE
        + '/sessions/' + encodeURIComponent(requestedSessionId)
        + '/snapshots?from=' + encodeURIComponent(fromTick)
        + '&to=' + encodeURIComponent(toTick);

    fetch(url)
        .then(function (res) { return res.json(); })
        .then(function (data) {
            if (token !== replayLoadToken || requestedSessionId !== replaySessionId) return;
            var rawSnapshots = data.snapshots || [];
            var snaps = rawSnapshots.map(normalizeReplaySnapshot).filter(Boolean);
            appendReplaySnapshots(snaps);

            if (snaps.length === 0) {
                replayReachedEnd = true;
                if (replaySnapshots.length === 0) {
                    addLog('warn', '暂无可用回放快照，请确认 replay-logger 在仿真开始前已启动');
                    updateReplayFrame();
                }
                return;
            }

            replayNextFromTick = getLastReplayTick() + 1;

            if (replaySnapshots.length === snaps.length) {
                replayFrameIdx = 0;
                updateReplayFrame();
                addLog('success', '已加载历史回放首段 ' + snaps.length + ' 帧，后续将边播边加载');
            } else {
                addLog('info', '已追加回放帧至 tick ' + getLastReplayTick());
            }
        })
        .catch(function (err) {
            if (token !== replayLoadToken || requestedSessionId !== replaySessionId) return;
            addLog('error', '加载回放分段失败：' + err.message);
        })
        .finally(function () {
            if (token === replayLoadToken && requestedSessionId === replaySessionId) {
                replayLoadingChunk = false;
            }
        });
}

function appendReplaySnapshots(snaps) {
    if (!snaps || snaps.length === 0) return;
    var seen = {};
    replaySnapshots.forEach(function (s) { seen[String(s.tick)] = true; });
    snaps.forEach(function (s) {
        var key = String(s.tick);
        if (!seen[key]) {
            replaySnapshots.push(s);
            seen[key] = true;
        }
    });
    replaySnapshots.sort(compareReplaySnapshots);
}

function getLastReplayTick() {
    if (replaySnapshots.length === 0) return 0;
    return replaySnapshots[replaySnapshots.length - 1].tick || 0;
}

function compareReplaySnapshots(a, b) {
    return (a.tick || 0) - (b.tick || 0);
}

function ensureReplayBuffer() {
    if (replaySessionMode !== 'history' || replayReachedEnd || replayLoadingChunk) return;
    if (replaySnapshots.length - replayFrameIdx <= REPLAY_PREFETCH_THRESHOLD) {
        loadReplayChunk(replayNextFromTick, replayLoadToken);
    }
}

function normalizeReplaySnapshot(item) {
    if (!item) return null;

    var rawState = item.stateJson || item.state_json || item;
    if (typeof rawState === 'string') {
        try {
            rawState = JSON.parse(rawState);
        } catch (e) {
            return null;
        }
    }

    var cars = {};
    if (rawState.cars) {
        if (Array.isArray(rawState.cars)) {
            rawState.cars.forEach(function (c, i) {
                var cid = c.id || c.carId || ('Car' + String(i + 1).padStart(3, '0'));
                cars[cid] = buildCarInfo(cid, c);
            });
        } else {
            Object.keys(rawState.cars).forEach(function (cid) {
                cars[cid] = buildCarInfo(cid, rawState.cars[cid]);
            });
        }
    }

    return {
        tick: rawState.tick !== undefined ? rawState.tick : item.tick,
        coverage: rawState.exploredPercent !== undefined
            ? rawState.exploredPercent
            : ((item.coverage || 0) <= 1 ? (item.coverage || 0) * 100 : item.coverage || 0),
        mapWidth: rawState.mapWidth || state.mapWidth || 26,
        mapHeight: rawState.mapHeight || state.mapHeight || 16,
        mapView: rawState.mapView || state.mapView,
        staticBlock: rawState.staticBlock || state.staticBlock,
        dynamicBlock: rawState.dynamicBlock || [],
        cars: cars
    };
}

function startReplay() {
    if (replayLoadingChunk && replaySnapshots.length === 0) { addLog('warn', '回放正在加载，请稍后'); return; }
    if (replaySnapshots.length === 0) { addLog('warn', '璇峰厛閫夋嫨浼氳瘽'); return; }
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
    if (delta > 0 && replayFrameIdx >= replaySnapshots.length - 1) {
        ensureReplayBuffer();
        if (replayReachedEnd && !replayLoadingChunk) {
            pauseReplay();
            DOM.replayBtnStop.disabled = false;
        }
        return;
    }
    replayFrameIdx = Math.max(0, Math.min(replaySnapshots.length - 1, replayFrameIdx + delta));
    updateReplayFrame();
    ensureReplayBuffer();
    if (replayFrameIdx >= replaySnapshots.length - 1 && replayReachedEnd) {
        pauseReplay();
        DOM.replayBtnStop.disabled = false;
    }
}

function updateReplayFrame() {
    if (replaySnapshots.length === 0 || !replayLayout) {
        DOM.replayFrameInfo.textContent = '0/0';
        return;
    }
    DOM.replayFrameInfo.textContent = (replayFrameIdx + 1) + '/' + replaySnapshots.length
        + (replaySessionMode === 'history' && !replayReachedEnd ? '+' : '');
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
        mapWidth: frame.mapWidth || state.mapWidth || 26,
        mapHeight: frame.mapHeight || state.mapHeight || 16,
        mapView: frame.mapView || state.mapView,
        staticBlock: frame.staticBlock || state.staticBlock,
        dynamicBlock: frame.dynamicBlock || state.dynamicBlock,
        exploredPercent: frame.coverage || 0,
        cars: frame.cars || {}
    };
    replayLayout = calcLayout(canvas.clientWidth || canvas.width / dpr, canvas.clientHeight || canvas.height / dpr, simState.mapWidth, simState.mapHeight);
    renderAll(canvas, simState, replayLayout);
}

// ==================== 系统设置 ====================

/** 默认设置 */
var defaultSettings = {
    redisHost: 'localhost', redisPort: 6379,
    mqHost: 'localhost', mqPort: 5672,
    mapWidth: 26, mapHeight: 16,
    robotCount: 5, density: 5,
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

function buildConfigParams() {
    var robotCount = parseInt(DOM.robotCount.value, 10);
    var mapWidth = parseInt(DOM.mapWidth.value, 10);
    var mapHeight = parseInt(DOM.mapHeight.value, 10);
    var density = parseInt(DOM.obstacleDensity.value, 10);
    return {
        robotCount: Number.isNaN(robotCount) ? 5 : robotCount,
        carCount: Number.isNaN(robotCount) ? 5 : robotCount,
        mapWidth: Number.isNaN(mapWidth) ? 26 : mapWidth,
        mapHeight: Number.isNaN(mapHeight) ? 16 : mapHeight,
        density: Number.isNaN(density) ? 5 : density,
        obstacleDensity: Number.isNaN(density) ? 5 : density,
        algorithm: document.querySelector('input[name="algorithm"]:checked')?.value || 'BFS'
    };
}

function applySimulationConfig() {
    if (!wsConnected) {
        addLog('warn', 'WebSocket 未连接，无法应用地图配置');
        return;
    }
    var params = buildConfigParams();
    if (params.density > 20) {
        addLog('warn', '障碍物密度不能超过 20%，已自动调整为 20%');
        params.density = 20;
        params.obstacleDensity = 20;
        DOM.obstacleDensity.value = 20;
        DOM.densityValue.textContent = '20%';
    }
    state.mapWidth = params.mapWidth;
    state.mapHeight = params.mapHeight;
    state.tick = 0;
    state.elapsedMs = 0;
    state.exploredPercent = 0;
    state.status = STATUS_IDLE;
    state.startTimestamp = null;
    state.cars = {};
    state.mapView = [];
    state.dynamicBlock = [];
    state.staticBlock = [];
    state.obstacleCount = 0;
    state.connectedRobots = 0;
    _trendData = [];
    _lastCarSnapshot = '';
    _lastStepsSnapshot = '';
    clearRobotColorCache();
    recalcMapLayout();
    sendCommand('SET_CONFIG', params);
    addLog('success', '已应用地图配置（' + params.mapWidth + '×' + params.mapHeight + '，预设小车 ' + params.robotCount + ' 辆）');
    updateControlButtons();
    updateUI();
    updateStatusBar();
    updateSystemInfo();
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
        density: parseInt(document.getElementById('setDensity').value) || 5,
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
        if (!newPwd || newPwd.length < 6) { DOM.ucPwdMsg.textContent = '新密码至少 6 位'; return; }
        if (!isStrongPassword(newPwd)) { DOM.ucPwdMsg.textContent = '密码强度不足：需包含大写字母、小写字母、数字、特殊字符中的至少三种'; return; }
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
    ensureMapArrays();
    if (!wsConnected) {
        addLog('warn', 'WebSocket 未连接，无法部署小车');
        return;
    }
    // 检查数量上限
    var maxCars = parseInt(DOM.robotCount.value) || 5;
    var currentCount = Object.keys(state.cars).length;
    if (currentCount >= maxCars) {
        addLog('warn', '已达到机器人数量上限 ' + maxCars + ' 辆，请先移除或调大数量');
        return;
    }
    var idx = row * state.mapWidth + col;
    // 不能放在障碍物或动态占用上
    if (state.staticBlock[idx] || state.dynamicBlock[idx]) { addLog('warn', '不能放在障碍物或已占用格上 (' + col + ', ' + row + ')'); return; }
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

    // state.cars[carId] = {
    //     carId: carId,
    //     position: { x: col, y: row },
    //     target: null,
    //     routeList: [],
    //     status: 'IDLE',
    //     stepsWalked: 0,
    //     battery: 100
    // };
    // state.connectedRobots = Object.keys(state.cars).length;
    // state.dynamicBlock[idx] = true;
    // stateDirty = true;
    // _lastCarSnapshot = '';
    // _lastStepsSnapshot = '';
    // updateRobotCards();
    // updateStepsStats();
    // addLog('success', '已部署小车 ' + carId + ' 位置 (' + col + ', ' + row + ')');
    // // 同步到后端
    // if (wsConnected) {
    //     sendCommand('ADD_CAR', { carId: carId, row: row, col: col });
    // }
    sendCommand('ADD_CAR', { carId: carId, row: row, col: col });
    addLog('info', '已请求部署小车 ' + carId + ' 位置 (' + col + ', ' + row + ')');
}

function addRandomCars() {
    if (!wsConnected) {
        addLog('warn', 'WebSocket 未连接，无法随机部署小车');
        return;
    }

    var targetCount = parseInt(DOM.robotCount.value, 10);
    if (Number.isNaN(targetCount) || targetCount <= 0) targetCount = 1;

    ensureMapArrays();
    var existing = Object.keys(state.cars).length;
    var toAdd = Math.max(0, targetCount - existing);
    if (toAdd === 0) {
        addLog('info', '当前小车数量已达到设置值，无需随机添加');
        return;
    }

    sendCommand('ADD_CARS_BATCH', {
        count: toAdd,
        targetCount: targetCount,
        mapWidth: state.mapWidth,
        mapHeight: state.mapHeight
    });
    addLog('info', '已请求后端随机部署 ' + toAdd + ' 辆小车');
}

function ensureMapArrays() {
    var size = (state.mapWidth || 26) * (state.mapHeight || 16);
    if (!Array.isArray(state.staticBlock) || state.staticBlock.length !== size) state.staticBlock = new Array(size).fill(false);
    if (!Array.isArray(state.dynamicBlock) || state.dynamicBlock.length !== size) state.dynamicBlock = new Array(size).fill(false);
    if (!Array.isArray(state.mapView) || state.mapView.length !== size) state.mapView = new Array(size).fill(false);
}

function isFreeForCar(col, row) {
    if (col < 0 || col >= state.mapWidth || row < 0 || row >= state.mapHeight) return false;
    var idx = row * state.mapWidth + col;
    if (state.staticBlock[idx] || state.dynamicBlock[idx]) return false;
    return !Object.keys(state.cars).some(function (cid) {
        var p = state.cars[cid].position;
        return p && p.x === col && p.y === row;
    });
}

function nextCarId() {
    var n = 1;
    var carId = 'Car' + String(n).padStart(3, '0');
    while (state.cars[carId]) {
        n++;
        carId = 'Car' + String(n).padStart(3, '0');
    }
    return carId;
}

function removeCarAt(col, row) {
    var found = null;
    Object.keys(state.cars).forEach(function (cid) {
        var c = state.cars[cid];
        if (c.position.x === col && c.position.y === row) found = cid;
    });
    if (!found) { addLog('warn', '该位置没有小车(' + col + ', ' + row + ')'); return; }
    var removed = state.cars[found];
    delete state.cars[found];
    ensureMapArrays();
    state.dynamicBlock[row * state.mapWidth + col] = false;
    state.connectedRobots = Object.keys(state.cars).length;
    stateDirty = true;
    _lastCarSnapshot = '';
    _lastStepsSnapshot = '';
    updateRobotCards();
    updateStepsStats();
    addLog('info', '已移除小车' + found + ' 位置 (' + col + ', ' + row + ')');
    if (wsConnected) {
        sendCommand('REMOVE_CAR', { carId: found });
    }
}

// ==================== 渲染循环 ====================

function startRenderLoop() {
    function loop() {
        animFrameId = requestAnimationFrame(loop);
        if (state.status === STATUS_RUNNING) {
            if (state.startTimestamp) state.elapsedMs = Date.now() - state.startTimestamp;
        }
        if (stateDirty) {
            var now = Date.now();
            if (now - lastDomUpdate >= DOM_UPDATE_INTERVAL) { updateUI(); stateDirty = false; lastDomUpdate = now; }
        }
        if (layout) renderAll(DOM.mapCanvas, state, layout, mouseState);
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

/** 角色值 → 中文显示标签 */
function getRoleLabel(role) {
    var map = { 'admin': '系统配置员', 'operator': '运行人员', 'analyst': '分析人员' };
    return map[role] || role || '未知';
}

function padZero(n) { return n < 10 ? '0' + n : '' + n; }
function formatElapsed(ms) { if (!ms || ms < 0) return '00:00:00'; var s = Math.floor(ms / 1000); return padZero(Math.floor(s/3600)) + ':' + padZero(Math.floor((s%3600)/60)) + ':' + padZero(s%60); }
function escapeHtml(str) { var d = document.createElement('div'); d.appendChild(document.createTextNode(str)); return d.innerHTML; }

window._simState = state;
window._simAddLog = addLog;

// ==================== 统计分析 ====================

var STATS_API_BASE = window.location.protocol + '//' + window.location.hostname + ':8085/api/stats';
var statsSessionMeta = {};  // sessionId → {operatorId, coverageRate, ...}

function showStatsPanel() {
    DOM.statsPanel.style.display = 'flex';
    refreshStatsSessions();
}

function refreshStatsSessions() {
    var sel = DOM.statsSessionSelect;
    sel.innerHTML = '<option value="">-- 选择会话 --</option>';

    fetch(STATS_API_BASE + '/sessions?page=0&size=20')
        .then(function (res) { return res.json(); })
        .then(function (data) {
            var sessions = data.data || [];
            statsSessionMeta = {};
            sessions.forEach(function (s) {
                statsSessionMeta[s.sessionId] = s;
                var label = formatStatsSessionLabel(s);
                sel.innerHTML += '<option value="' + escapeHtml(s.sessionId) + '">' + escapeHtml(label) + '</option>';
            });
            if (sessions.length === 0) {
                sel.innerHTML += '<option value="" disabled>暂无历史会话</option>';
            }
            // 初始化多会话对比选择器（在选项加载完成后）
            initStatsCompareSelect();
        })
        .catch(function () {
            addLog('warn', '未连接到统计分析服务 8085');
            sel.innerHTML += '<option value="" disabled>服务未连接</option>';
        });

    // 绑定 change 事件（仅一次） + 热力图按钮
    if (!sel._statsWired) {
        sel._statsWired = true;
        sel.addEventListener('change', function () {
            var sid = this.value;
            if (sid) loadStatsSession(sid);
        });
    }

    // 热力图按钮
    if (!DOM.statsHeatmapBtn._wired) {
        DOM.statsHeatmapBtn._wired = true;
        DOM.statsHeatmapBtn.addEventListener('click', function () {
            loadHeatmap(sel.value);
        });
    }
}

function formatStatsSessionLabel(session) {
    var started = session.startTime ? formatDateTime(session.startTime) : (session.sessionId || '').slice(0, 8);
    var cr = session.coverageRate != null ? (session.coverageRate * 100).toFixed(1) + '%' : '--';
    var operator = session.operatorId ? ' | 操作员: ' + session.operatorId : '';
    return started + ' | ' + cr + operator;
}

function loadStatsSession(sessionId) {
    // 显示操作员
    var meta = statsSessionMeta[sessionId];
    if (DOM.statsOperatorId) DOM.statsOperatorId.textContent = (meta && meta.operatorId) ? meta.operatorId : '--';

    // 并行拉取四个接口
    fetch(STATS_API_BASE + '/sessions/' + encodeURIComponent(sessionId) + '/overview')
        .then(function (res) { return res.json(); })
        .then(function (data) { renderStatsOverview(data); })
        .catch(function (err) { addLog('error', '加载统计概览失败：' + err.message); });

    fetch(STATS_API_BASE + '/sessions/' + encodeURIComponent(sessionId) + '/coverage-curve')
        .then(function (res) { return res.json(); })
        .then(function (data) { renderCoverageCurve(data); })
        .catch(function (err) { addLog('error', '加载覆盖率曲线失败：' + err.message); });

    fetch(STATS_API_BASE + '/sessions/' + encodeURIComponent(sessionId) + '/car-contribution')
        .then(function (res) { return res.json(); })
        .then(function (data) { renderCarContribution(data); })
        .catch(function (err) { addLog('error', '加载各车贡献失败：' + err.message); });

    fetch(STATS_API_BASE + '/sessions/' + encodeURIComponent(sessionId) + '/predict')
        .then(function (res) { return res.json(); })
        .then(function (data) { renderStatsPrediction(data); })
        .catch(function (err) { addLog('error', '加载预测数据失败：' + err.message); });
}

function renderStatsOverview(data) {
    if (!data || Object.keys(data).length === 0) return;

    var coverageRate = data.coverageRate != null ? data.coverageRate : 0;
    var totalMoves = data.totalMoves != null ? data.totalMoves : 0;
    var totalBlocked = data.totalBlocked != null ? data.totalBlocked : 0;
    var avgNavEfficiency = data.avgNavEfficiency != null ? data.avgNavEfficiency : 0;

    DOM.statsCoverage.textContent = (coverageRate * 100).toFixed(1) + '%';
    DOM.statsTotalMoves.textContent = totalMoves;
    DOM.statsTotalBlocked.textContent = totalBlocked;

    if (totalMoves > 0) {
        DOM.statsBlockedRate.textContent = (totalBlocked / totalMoves * 100).toFixed(1) + '%';
    } else {
        DOM.statsBlockedRate.textContent = '--';
    }

    if (data.durationMs != null) {
        DOM.statsDuration.textContent = (data.durationMs / 1000).toFixed(1) + 's';
    } else {
        DOM.statsDuration.textContent = '进行中';
    }

    if (avgNavEfficiency > 0) {
        DOM.statsNavEfficiency.textContent = avgNavEfficiency.toFixed(2);
    } else {
        DOM.statsNavEfficiency.textContent = '--';
    }
}

function renderStatsPrediction(data) {
    if (!data) return;

    var confidence = data.confidence || 'low';

    var labelMap = { high: '高', medium: '中', low: '低' };
    var colorMap = { high: '#4aff7a', medium: '#ffa500', low: '#888888' };

    DOM.statsPredictConfidence.textContent = labelMap[confidence] || '未知';
    DOM.statsPredictConfidence.style.color = colorMap[confidence] || '#888888';
}

function renderCoverageCurve(data) {
    var canvas = DOM.statsCoverageCanvas;
    var wrap = DOM.statsCoverageWrap;
    var dpr = window.devicePixelRatio || 1;
    var w = wrap.clientWidth;
    var h = wrap.clientHeight;

    canvas.width = w * dpr;
    canvas.height = h * dpr;
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';

    var ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    // 背景
    ctx.fillStyle = '#061529';
    ctx.fillRect(0, 0, w, h);

    var points = data.points || [];
    if (points.length === 0) {
        ctx.fillStyle = '#667788';
        ctx.font = '12px monospace';
        ctx.textAlign = 'center';
        ctx.fillText('暂无数据', w / 2, h / 2);
        return;
    }

    var margin = { left: 48, right: 16, top: 16, bottom: 28 };
    var pw = w - margin.left - margin.right;
    var ph = h - margin.top - margin.bottom;

    // 计算坐标范围
    var minTick = points[0].tick;
    var maxTick = points[points.length - 1].tick;
    var tickRange = maxTick - minTick || 1;
    var maxCoverage = 1.0;

    // 坐标轴
    ctx.strokeStyle = '#334455';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(margin.left, margin.top);
    ctx.lineTo(margin.left, margin.top + ph);
    ctx.lineTo(margin.left + pw, margin.top + ph);
    ctx.stroke();

    // Y轴刻度 (0%, 25%, 50%, 75%, 100%)
    ctx.fillStyle = '#667788';
    ctx.font = '9px monospace';
    ctx.textAlign = 'right';
    for (var pct = 0; pct <= 100; pct += 25) {
        var y = margin.top + ph - (pct / 100) * ph;
        ctx.fillText(pct + '%', margin.left - 6, y + 3);
        ctx.strokeStyle = '#1a2a3a';
        ctx.beginPath();
        ctx.moveTo(margin.left, y);
        ctx.lineTo(margin.left + pw, y);
        ctx.stroke();
    }

    // X轴刻度
    ctx.textAlign = 'center';
    var xSteps = Math.min(5, points.length);
    for (var i = 0; i <= xSteps; i++) {
        var idx = Math.round((i / xSteps) * (points.length - 1));
        if (idx >= points.length) idx = points.length - 1;
        var tickVal = points[idx].tick;
        var x = margin.left + ((tickVal - minTick) / tickRange) * pw;
        ctx.fillText(tickVal, x, margin.top + ph + 14);
    }

    // 折线
    ctx.strokeStyle = '#4a9eff';
    ctx.lineWidth = 2;
    ctx.beginPath();
    for (var j = 0; j < points.length; j++) {
        var px = margin.left + ((points[j].tick - minTick) / tickRange) * pw;
        var py = margin.top + ph - (points[j].coverage / maxCoverage) * ph;
        if (j === 0) ctx.moveTo(px, py);
        else ctx.lineTo(px, py);
    }
    ctx.stroke();

    // 末端圆点
    if (points.length > 0) {
        var lx = margin.left + ((points[points.length - 1].tick - minTick) / tickRange) * pw;
        var ly = margin.top + ph - (points[points.length - 1].coverage / maxCoverage) * ph;
        ctx.fillStyle = '#4a9eff';
        ctx.beginPath();
        ctx.arc(lx, ly, 4, 0, Math.PI * 2);
        ctx.fill();
    }
}

function renderCarContribution(data) {
    var canvas = DOM.statsCarCanvas;
    var wrap = DOM.statsCarWrap;
    var dpr = window.devicePixelRatio || 1;
    var w = wrap.clientWidth;
    var h = wrap.clientHeight;

    canvas.width = w * dpr;
    canvas.height = h * dpr;
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';

    var ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    // 背景
    ctx.fillStyle = '#061529';
    ctx.fillRect(0, 0, w, h);

    var cars = data.cars || [];
    if (cars.length === 0) {
        ctx.fillStyle = '#667788';
        ctx.font = '12px monospace';
        ctx.textAlign = 'center';
        ctx.fillText('暂无数据', w / 2, h / 2);
        return;
    }

    var margin = { left: 42, right: 16, top: 22, bottom: 36 };
    var pw = w - margin.left - margin.right;
    var ph = h - margin.top - margin.bottom;

    // 计算最大值
    var maxVal = 0;
    cars.forEach(function (c) {
        var m = Math.max(c.moves || 0, c.blocked || 0, c.navCount || 0);
        if (m > maxVal) maxVal = m;
    });
    if (maxVal === 0) maxVal = 1;

    var barGroupWidth = Math.min(30, (pw / cars.length) * 0.7);
    var barWidth = Math.max(2, barGroupWidth / 3);
    var groupGap = pw / cars.length;

    // 坐标轴
    ctx.strokeStyle = '#334455';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(margin.left, margin.top);
    ctx.lineTo(margin.left, margin.top + ph);
    ctx.lineTo(margin.left + pw, margin.top + ph);
    ctx.stroke();

    // Y轴刻度
    ctx.fillStyle = '#667788';
    ctx.font = '9px monospace';
    ctx.textAlign = 'right';
    for (var pct = 0; pct <= 100; pct += 25) {
        var yv = (pct / 100) * maxVal;
        var y = margin.top + ph - (pct / 100) * ph;
        ctx.fillText(Math.round(yv), margin.left - 6, y + 3);
        ctx.strokeStyle = '#1a2a3a';
        ctx.beginPath();
        ctx.moveTo(margin.left, y);
        ctx.lineTo(margin.left + pw, y);
        ctx.stroke();
    }

    // 绘制柱子
    var colors = { moves: '#4a9eff', blocked: '#ff4a4a', navCount: '#4aff7a' };
    var keys = ['moves', 'blocked', 'navCount'];

    cars.forEach(function (car, ci) {
        var gx = margin.left + ci * groupGap + (groupGap - barGroupWidth) / 2;
        keys.forEach(function (key, ki) {
            var val = car[key] || 0;
            var barH = (val / maxVal) * ph;
            var bx = gx + ki * barWidth;
            var by = margin.top + ph - barH;
            ctx.fillStyle = colors[key];
            ctx.fillRect(bx, by, barWidth - 1, barH);
        });

        // X轴 carId 标签
        ctx.fillStyle = '#8899aa';
        ctx.font = '8px monospace';
        ctx.textAlign = 'center';
        var shortId = (car.carId || '').length > 6 ? (car.carId || '').slice(-4) : (car.carId || '');
        ctx.fillText(shortId, gx + barGroupWidth / 2, margin.top + ph + 14);
    });

    // 图例
    var legendX = margin.left;
    var legendY = 6;
    var legendItems = [
        { label: 'moves', color: '#4a9eff' },
        { label: 'blocked', color: '#ff4a4a' },
        { label: 'navCount', color: '#4aff7a' }
    ];
    ctx.font = '8px monospace';
    legendItems.forEach(function (item, i) {
        var lx = legendX + i * 70;
        ctx.fillStyle = item.color;
        ctx.fillRect(lx, legendY, 8, 8);
        ctx.fillStyle = '#8899aa';
        ctx.textAlign = 'left';
        ctx.fillText(item.label, lx + 11, legendY + 8);
    });
}

// ==================== 多会话对比 ====================

function initStatsCompareSelect() {
    var src = DOM.statsSessionSelect;
    var dst = DOM.statsCompareSelect;
    dst.innerHTML = '';
    var opts = src.querySelectorAll('option');
    opts.forEach(function (opt) {
        if (opt.value) {
            var clone = document.createElement('option');
            clone.value = opt.value;
            clone.textContent = opt.textContent;
            dst.appendChild(clone);
        }
    });

    // 多选点击切换：单击即可选择/取消，无需按 Ctrl
    if (!dst._toggleWired) {
        dst._toggleWired = true;
        dst.addEventListener('mousedown', function (e) {
            var opt = e.target.closest('option');
            if (!opt) return;
            e.preventDefault();
            opt.selected = !opt.selected;
            dst.focus();
            return false;
        });
    }

    if (!DOM.statsCompareBtn._wired) {
        DOM.statsCompareBtn._wired = true;
        DOM.statsCompareBtn.addEventListener('click', runSessionCompare);
    }
}

function runSessionCompare() {
    var sel = DOM.statsCompareSelect;
    var selected = [];
    for (var i = 0; i < sel.options.length; i++) {
        if (sel.options[i].selected) selected.push(sel.options[i].value);
    }
    if (selected.length < 2) {
        addLog('warn', '请至少选择两个会话');
        return;
    }
    if (selected.length > 5) selected = selected.slice(0, 5);

    var ids = selected.join(',');
    fetch(STATS_API_BASE + '/sessions/compare?ids=' + encodeURIComponent(ids))
        .then(function (res) { return res.json(); })
        .then(function (data) { renderCompareChart(data); })
        .catch(function (e) { addLog('error', '对比接口失败: ' + e.message); });
}

function renderCompareChart(data) {
    var canvas = DOM.statsCompareCanvas;
    var wrap = DOM.statsCompareWrap;
    var dpr = window.devicePixelRatio || 1;
    var w = wrap.clientWidth;
    var h = wrap.clientHeight;

    canvas.width = w * dpr;
    canvas.height = h * dpr;
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';

    var ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    ctx.fillStyle = '#061529';
    ctx.fillRect(0, 0, w, h);

    var sessions = data.sessions || [];
    var curves = data.curves || {};
    if (sessions.length === 0) {
        ctx.fillStyle = '#667788';
        ctx.font = '12px monospace';
        ctx.textAlign = 'center';
        ctx.fillText('暂无数据', w / 2, h / 2);
        return;
    }

    var margin = { left: 50, right: 20, top: 16, bottom: 28 };
    var pw = w - margin.left - margin.right;
    var ph = h - margin.top - margin.bottom;

    var globalMaxTick = 0;
    var offsets = {};
    sessions.forEach(function (s) {
        var pts = curves[s.sessionId] || [];
        if (pts.length > 0) {
            var minT = pts[0].tick;
            var maxT = pts[pts.length - 1].tick;
            offsets[s.sessionId] = minT;
            if (maxT - minT > globalMaxTick) globalMaxTick = maxT - minT;
        }
    });
    if (globalMaxTick === 0) globalMaxTick = 1;

    ctx.strokeStyle = '#334455';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(margin.left, margin.top);
    ctx.lineTo(margin.left, margin.top + ph);
    ctx.lineTo(margin.left + pw, margin.top + ph);
    ctx.stroke();

    ctx.fillStyle = '#667788';
    ctx.font = '9px monospace';
    ctx.textAlign = 'right';
    for (var pct = 0; pct <= 100; pct += 25) {
        var y = margin.top + ph - (pct / 100) * ph;
        ctx.fillText(pct + '%', margin.left - 6, y + 3);
        ctx.strokeStyle = '#1a2a3a';
        ctx.beginPath();
        ctx.moveTo(margin.left, y);
        ctx.lineTo(margin.left + pw, y);
        ctx.stroke();
    }

    ctx.textAlign = 'center';
    var xSteps = Math.min(5, globalMaxTick > 0 ? 5 : 1);
    for (var i2 = 0; i2 <= xSteps; i2++) {
        var tickVal = Math.round((i2 / xSteps) * globalMaxTick);
        var x = margin.left + (tickVal / globalMaxTick) * pw;
        ctx.fillText(tickVal, x, margin.top + ph + 14);
    }

    var palette = ['#4a9eff', '#ff4a4a', '#4aff7a', '#ffaa00', '#cc44ff'];

    sessions.forEach(function (s, si) {
        var color = palette[si % palette.length];
        var pts = curves[s.sessionId] || [];
        if (pts.length === 0) return;
        var offset = offsets[s.sessionId] || 0;

        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        ctx.beginPath();
        for (var j = 0; j < pts.length; j++) {
            var px = margin.left + ((pts[j].tick - offset) / globalMaxTick) * pw;
            var py = margin.top + ph - pts[j].coverage * ph;
            if (j === 0) ctx.moveTo(px, py);
            else ctx.lineTo(px, py);
        }
        ctx.stroke();

        var last = pts[pts.length - 1];
        var lx = margin.left + ((last.tick - offset) / globalMaxTick) * pw;
        var ly = margin.top + ph - last.coverage * ph;
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(lx, ly, 4, 0, Math.PI * 2);
        ctx.fill();
    });

    var legendX = margin.left + pw - 220;
    var legendY = margin.top + 4;
    sessions.forEach(function (s, si) {
        var color = palette[si % palette.length];
        var label = s.label || s.sessionId || '';
        if (label.length > 12) label = label.slice(0, 12);
        var ly = legendY + si * 16;
        ctx.fillStyle = color;
        ctx.fillRect(legendX, ly, 10, 10);
        ctx.fillStyle = '#ccc';
        ctx.font = '9px monospace';
        ctx.textAlign = 'left';
        ctx.fillText(label, legendX + 14, ly + 9);
    });
}

// ==================== 热力图 ====================

function loadHeatmap(sessionId) {
    if (!sessionId) {
        addLog('warn', '请先选择会话');
        return;
    }
    fetch(STATS_API_BASE + '/sessions/' + encodeURIComponent(sessionId) + '/heatmap')
        .then(function (res) { return res.json(); })
        .then(function (data) { renderHeatmap(data); })
        .catch(function (e) { addLog('error', '热力图接口失败: ' + e.message); });
}

function renderHeatmap(data) {
    var canvas = DOM.statsHeatmapCanvas;
    var wrap = DOM.statsHeatmapWrap;
    var dpr = window.devicePixelRatio || 1;
    var w = wrap.clientWidth;
    var h = wrap.clientHeight;

    canvas.width = w * dpr;
    canvas.height = h * dpr;
    canvas.style.width = w + 'px';
    canvas.style.height = h + 'px';

    var ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    ctx.fillStyle = '#061529';
    ctx.fillRect(0, 0, w, h);

    var mapW = data.mapWidth || 1;
    var mapH = data.mapHeight || 1;
    var maxCount = data.maxCount || 1;
    var cells = data.cells || [];

    var cellMap = {};
    cells.forEach(function (c) {
        cellMap[c.x + ',' + c.y] = c.count;
    });

    var cellW = w / mapW;
    var cellH = h / mapH;

    for (var row = 0; row < mapH; row++) {
        for (var col = 0; col < mapW; col++) {
            var count = cellMap[col + ',' + row] || 0;
            if (count > 0) {
                var alpha = count / maxCount;
                ctx.fillStyle = 'rgba(255,74,74,' + alpha.toFixed(2) + ')';
                ctx.fillRect(col * cellW, row * cellH, cellW, cellH);
            }
        }
    }

    ctx.strokeStyle = 'rgba(255,255,255,0.06)';
    ctx.lineWidth = 0.5;
    for (var row2 = 0; row2 <= mapH; row2++) {
        ctx.beginPath();
        ctx.moveTo(0, row2 * cellH);
        ctx.lineTo(w, row2 * cellH);
        ctx.stroke();
    }
    for (var col2 = 0; col2 <= mapW; col2++) {
        ctx.beginPath();
        ctx.moveTo(col2 * cellW, 0);
        ctx.lineTo(col2 * cellW, h);
        ctx.stroke();
    }

    if (!canvas._heatmapWired) {
        canvas._heatmapWired = true;
        var tooltip = DOM.statsHeatmapTooltip;

        canvas.addEventListener('mousemove', function (e) {
            var rect = canvas.getBoundingClientRect();
            var mx = e.clientX - rect.left;
            var my = e.clientY - rect.top;
            var col3 = Math.floor(mx / cellW);
            var row3 = Math.floor(my / cellH);
            if (col3 < 0 || col3 >= mapW || row3 < 0 || row3 >= mapH) {
                tooltip.style.display = 'none';
                return;
            }
            var cnt = cellMap[col3 + ',' + row3] || 0;
            tooltip.textContent = '(' + col3 + ',' + row3 + ') 访问次数:' + cnt;
            tooltip.style.display = 'block';
            tooltip.style.left = (mx + 12) + 'px';
            tooltip.style.top = (my - 20) + 'px';
        });

        canvas.addEventListener('mouseleave', function () {
            tooltip.style.display = 'none';
        });
    }
}
