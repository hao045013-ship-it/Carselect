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
    DOM.mapImageInput = document.getElementById('mapImageInput');
    DOM.btnUploadMap = document.getElementById('btnUploadMap');
    DOM.mapImageInfo = document.getElementById('mapImageInfo');
    DOM.imageFileName = document.getElementById('imageFileName');
    DOM.btnRemoveMap = document.getElementById('btnRemoveMap');
    DOM.userAvatar = document.getElementById('userAvatar');
    DOM.headerUserName = document.getElementById('headerUserName');

    // 中间
    DOM.centerPanel = document.getElementById('centerPanel');
    DOM.mapArea = document.getElementById('mapArea');
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
    if (raw.mapWidth !== undefined) state.mapWidth = raw.mapWidth;
    if (raw.mapHeight !== undefined) state.mapHeight = raw.mapHeight;
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
            normalizeState(raw);
            if (state.status === STATUS_IDLE && state.tick > 0) {
                state.status = STATUS_RUNNING;
                state.startTimestamp = Date.now() - state.elapsedMs;
                updateControlButtons();
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
    // 导航标签
    if (DOM.navTabs) {
        DOM.navTabs.addEventListener('click', function (e) {
            var tab = e.target.closest('.nav-tab');
            if (!tab) return;
            DOM.navTabs.querySelectorAll('.nav-tab').forEach(function (t) { t.classList.remove('active'); });
            tab.classList.add('active');
            var tabName = tab.getAttribute('data-tab');
            addLog('info', '切换到: ' + tab.textContent);
            // 目前只有实时系统有完整 UI；其他标签可后续扩展
        });
    }

    DOM.btnStart.addEventListener('click', function () {
        if (isDemoMode) { state.status = STATUS_RUNNING; demoStartTime = Date.now(); addLog('success', '仿真已启动（演示模式）'); }
        else if (wsConnected) { sendCommand('START'); state.status = STATUS_RUNNING; state.startTimestamp = Date.now() - state.elapsedMs; addLog('success', '仿真已启动'); }
        else { startDemoMode(); }
        updateControlButtons(); updateStatusBar(); updateSystemInfo();
    });

    DOM.btnPause.addEventListener('click', function () {
        state.status = STATUS_PAUSED;
        if (isDemoMode) { demoElapsedBeforePause = state.elapsedMs; demoStartTime = null; addLog('info', '仿真已暂停（演示模式）'); }
        else { sendCommand('PAUSE'); addLog('info', '仿真已暂停'); }
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
        if (isDemoMode && demoInterval) { clearInterval(demoInterval); demoInterval = setInterval(demoTick, Math.max(30, DEMO_TICK_MS - (parseInt(this.value) - 1) * 15)); }
    });
    DOM.obstacleDensity.addEventListener('input', function () { DOM.densityValue.textContent = this.value + '%'; });

    DOM.btnRandomObs.addEventListener('click', function () {
        var density = parseInt(DOM.obstacleDensity.value) || 25;
        if (isDemoMode) {
            var tc = state.mapWidth * state.mapHeight; state.staticBlock = new Array(tc).fill(false);
            var obsTarget = Math.floor(tc * density / 100), obsPlaced = 0;
            while (obsPlaced < obsTarget) { var ri = Math.floor(Math.random() * tc); if (!state.staticBlock[ri]) { state.staticBlock[ri] = true; obsPlaced++; } }
            state.obstacleCount = obsPlaced;
            addLog('success', '已随机生成 ' + obsPlaced + ' 个障碍物（密度 ' + density + '%）'); stateDirty = true;
        } else { sendCommand('RANDOM_OBSTACLE', { density: density }); }
        updateStatistics(); updateSystemInfo();
    });
    DOM.btnClearObs.addEventListener('click', function () {
        if (isDemoMode) { state.staticBlock = new Array(state.mapWidth * state.mapHeight).fill(false); state.obstacleCount = 0; addLog('success', '已清除全部障碍物'); stateDirty = true; }
        else { sendCommand('CLEAR_OBSTACLE'); }
        updateStatistics(); updateSystemInfo();
    });
    DOM.manualToggle.addEventListener('change', function () {
        DOM.manualHint.textContent = this.checked ? '点击地图格子可添加/移除障碍物（按住 Shift 添加动态障碍）' : '开启后可点击地图格子添加/移除障碍物';
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

    DOM.userAvatar.addEventListener('click', function () {
        var currentName = DOM.headerUserName.textContent;
        var nickname = prompt('请输入您的昵称：', currentName !== '未登录' ? currentName : '');
        if (nickname && nickname.trim()) {
            DOM.headerUserName.textContent = nickname.trim();
            addLog('info', '已切换用户: ' + nickname.trim());
            if (wsConnected) sendCommand('LOGIN', { nickname: nickname.trim() });
        }
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

    // 手动编辑障碍物
    DOM.mapCanvas.addEventListener('click', function (e) {
        if (!DOM.manualToggle.checked) return;
        var rect = DOM.mapCanvas.getBoundingClientRect();
        var cell = getCellAt(DOM.mapCanvas, e.clientX - rect.left, e.clientY - rect.top, layout, state.mapWidth, state.mapHeight);
        if (!cell) return;
        var idx = cell.row * state.mapWidth + cell.col;
        if (e.shiftKey) { state.dynamicBlock[idx] = !state.dynamicBlock[idx]; addLog('debug', (state.dynamicBlock[idx] ? '添加' : '移除') + ' 动态障碍物 (' + cell.col + ', ' + cell.row + ')'); }
        else { state.staticBlock[idx] = !state.staticBlock[idx]; state.obstacleCount += state.staticBlock[idx] ? 1 : -1; state.obstacleCount = Math.max(0, state.obstacleCount); addLog('debug', (state.staticBlock[idx] ? '添加' : '移除') + ' 障碍物 (' + cell.col + ', ' + cell.row + ')'); }
        stateDirty = true;
        updateStatistics(); updateSystemInfo();
    });

    window.addEventListener('resize', function () {
        if (layout) { var rect = DOM.canvasWrapper.getBoundingClientRect(); layout = calcLayout(rect.width, rect.height, state.mapWidth, state.mapHeight); }
    });
    window.addEventListener('keydown', function (e) {
        if (e.key === ' ') { e.preventDefault(); if (state.status === STATUS_RUNNING) DOM.btnPause.click(); else DOM.btnStart.click(); }
        if ((e.key === 'r' || e.key === 'R') && e.ctrlKey) { e.preventDefault(); DOM.btnReset.click(); }
    });
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
