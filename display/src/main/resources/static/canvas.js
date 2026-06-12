/**
 * canvas.js — Canvas 渲染引擎
 * 多机器人协作巡检仿真系统
 *
 * 所有渲染函数接受 state 作为参数，不依赖全局变量。
 * 坐标约定：x = 列 (column), y = 行 (row)
 * 地图数组为行优先: index = row * mapWidth + col
 */

// ==================== 常量 ====================

/** 机器人颜色调色板 — 8种易于区分的颜色 */
var ROBOT_COLORS = [
    '#1ec4ff', // 青色
    '#ff9900', // 橙色
    '#ff4444', // 红色
    '#00e676', // 绿色
    '#e040fb', // 紫色
    '#ffeb3b', // 黄色
    '#00bcd4', // 蓝绿色
    '#ff6e40'  // 深橙
];

/** 用于已分配颜色的缓存 */
var robotColorCache = {};

/** 地图背景图片 */
var _mapBgImage = null;

/**
 * 设置地图背景图片
 * @param {HTMLImageElement|null} image
 */
function setMapBackground(image) {
    _mapBgImage = image;
}

/**
 * 清除地图背景图片
 */
function clearMapBackground() {
    _mapBgImage = null;
}

/**
 * 获取当前地图背景图片
 * @returns {HTMLImageElement|null}
 */
function getMapBackground() {
    return _mapBgImage;
}

// ==================== 颜色分配 ====================

/**
 * 为机器人 ID 分配一致的颜色
 * @param {string} carId
 * @returns {string} 颜色 hex 值
 */
function getRobotColor(carId) {
    if (robotColorCache[carId]) {
        return robotColorCache[carId];
    }
    // 从 ID 提取数字作为索引
    var match = carId.match(/\d+/);
    var idx = match ? (parseInt(match[0], 10) - 1) % ROBOT_COLORS.length : 0;
    var color = ROBOT_COLORS[idx] || ROBOT_COLORS[0];
    robotColorCache[carId] = color;
    return color;
}

/**
 * 清除颜色缓存（重置时调用）
 */
function clearRobotColorCache() {
    robotColorCache = {};
}

// ==================== 画布初始化 ====================

/**
 * 初始化画布并设置 ResizeObserver
 * @param {HTMLCanvasElement} canvas
 * @param {HTMLElement} wrapper — 画布的容器元素
 * @param {number} mapWidth — 地图列数
 * @param {number} mapHeight — 地图行数
 * @returns {object} { cellSize, offsetX, offsetY }
 */
function initCanvas(canvas, wrapper, mapWidth, mapHeight) {
    var dpr = window.devicePixelRatio || 1;
    var rect = wrapper.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    canvas.style.width = rect.width + 'px';
    canvas.style.height = rect.height + 'px';

    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    // 监听容器尺寸变化
    if (window._canvasResizeObserver) {
        window._canvasResizeObserver.disconnect();
    }
    window._canvasResizeObserver = new ResizeObserver(function () {
        resizeCanvas(canvas, wrapper, mapWidth, mapHeight);
    });
    window._canvasResizeObserver.observe(wrapper);

    return calcLayout(rect.width, rect.height, mapWidth, mapHeight);
}

/**
 * 重新计算画布尺寸
 */
function resizeCanvas(canvas, wrapper, mapWidth, mapHeight) {
    var dpr = window.devicePixelRatio || 1;
    var rect = wrapper.getBoundingClientRect();
    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    canvas.style.width = rect.width + 'px';
    canvas.style.height = rect.height + 'px';
    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);
}

/**
 * 计算网格布局参数
 * @returns {{ cellSize: number, offsetX: number, offsetY: number, gridW: number, gridH: number }}
 */
function calcLayout(cw, ch, mapWidth, mapHeight) {
    // 为坐标标签留出边距
    var marginLeft = 32;
    var marginTop = 24;
    var marginRight = 8;
    var marginBottom = 8;

    var availW = cw - marginLeft - marginRight;
    var availH = ch - marginTop - marginBottom;

    // 保持正方形单元格
    var cellW = Math.floor(availW / mapWidth);
    var cellH = Math.floor(availH / mapHeight);
    var cellSize = Math.max(8, Math.min(cellW, cellH));

    var gridW = cellSize * mapWidth;
    var gridH = cellSize * mapHeight;

    // 网格居中于可用空间
    var offsetX = marginLeft + Math.floor((availW - gridW) / 2);
    var offsetY = marginTop + Math.floor((availH - gridH) / 2);

    return {
        cellSize: cellSize,
        offsetX: offsetX,
        offsetY: offsetY,
        gridW: gridW,
        gridH: gridH
    };
}

// ==================== 网格与标签渲染 ====================

/**
 * 绘制网格线和坐标标签
 * @param {CanvasRenderingContext2D} ctx
 * @param {object} layout — { cellSize, offsetX, offsetY, gridW, gridH }
 * @param {number} mapWidth
 * @param {number} mapHeight
 */
function renderGrid(ctx, layout, mapWidth, mapHeight) {
    var cs = layout.cellSize;
    var ox = layout.offsetX;
    var oy = layout.offsetY;

    // 网格线
    ctx.strokeStyle = 'rgba(30, 58, 95, 0.25)';
    ctx.lineWidth = 0.5;

    var i;
    // 垂直线
    for (i = 0; i <= mapWidth; i++) {
        var x = ox + i * cs;
        ctx.beginPath();
        ctx.moveTo(x, oy);
        ctx.lineTo(x, oy + mapHeight * cs);
        ctx.stroke();
    }
    // 水平线
    for (i = 0; i <= mapHeight; i++) {
        var y = oy + i * cs;
        ctx.beginPath();
        ctx.moveTo(ox, y);
        ctx.lineTo(ox + mapWidth * cs, y);
        ctx.stroke();
    }

    // 坐标标签 — 列号（上边缘）
    ctx.fillStyle = '#556677';
    ctx.font = (Math.max(9, cs * 0.35)) + 'px ' + '"Microsoft YaHei", sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'bottom';
    for (i = 0; i < mapWidth; i++) {
        // 每隔一定间隔绘制标签以避免拥挤
        var interval = mapWidth <= 20 ? 1 : mapWidth <= 30 ? 2 : mapWidth <= 40 ? 3 : 5;
        if (i % interval === 0) {
            ctx.fillText('' + i, ox + i * cs + cs / 2, oy - 3);
        }
    }

    // 坐标标签 — 行号（左边缘）
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    for (i = 0; i < mapHeight; i++) {
        var intervalH = mapHeight <= 15 ? 1 : mapHeight <= 25 ? 2 : 3;
        if (i % intervalH === 0) {
            ctx.fillText('' + i, ox - 5, oy + i * cs + cs / 2);
        }
    }
}

// ==================== 地图单元格渲染 ====================

/**
 * 绘制地图单元格（障碍物、已探索、未探索）
 * @param {CanvasRenderingContext2D} ctx
 * @param {object} layout
 * @param {object} state — 规范化后的状态对象
 */
function renderMap(ctx, layout, state) {
    var cs = layout.cellSize;
    var ox = layout.offsetX;
    var oy = layout.offsetY;
    var mw = state.mapWidth;
    var mh = state.mapHeight;

    var mapView = state.mapView || [];
    var staticBlock = state.staticBlock || [];
    var dynamicBlock = state.dynamicBlock || [];

    var hasMapData = mapView.length > 0 || staticBlock.length > 0;

    var row, col, idx;
    for (row = 0; row < mh; row++) {
        for (col = 0; col < mw; col++) {
            idx = row * mw + col;
            var px = ox + col * cs;
            var py = oy + row * cs;

            if (!hasMapData) {
                // 无地图数据：所有单元格使用默认未探索颜色
                ctx.fillStyle = '#0a1628';
                ctx.fillRect(px + 0.5, py + 0.5, cs - 1, cs - 1);
                continue;
            }

            var isStaticObs = staticBlock[idx] === true;
            var isDynamicObs = dynamicBlock[idx] === true;
            var isExplored = mapView[idx] === true;

            if (isStaticObs) {
                // 静态障碍物 — 深红色
                ctx.fillStyle = '#662222';
                ctx.fillRect(px + 0.5, py + 0.5, cs - 1, cs - 1);
                // 内部高亮线条
                if (cs > 12) {
                    ctx.fillStyle = 'rgba(255, 100, 100, 0.15)';
                    ctx.fillRect(px + 2, py + 2, cs - 4, cs - 4);
                }
            } else if (isDynamicObs) {
                // 动态障碍物 — 棕橙色
                ctx.fillStyle = '#553322';
                ctx.fillRect(px + 0.5, py + 0.5, cs - 1, cs - 1);
                if (cs > 12) {
                    ctx.fillStyle = 'rgba(255, 150, 50, 0.12)';
                    ctx.fillRect(px + 2, py + 2, cs - 4, cs - 4);
                }
            } else if (isExplored) {
                // 已探索 — 半透明青色
                ctx.fillStyle = 'rgba(30, 196, 255, 0.15)';
                ctx.fillRect(px + 0.5, py + 0.5, cs - 1, cs - 1);
            } else {
                // 未探索 — 深色
                ctx.fillStyle = '#0a1628';
                ctx.fillRect(px + 0.5, py + 0.5, cs - 1, cs - 1);
            }
        }
    }

    // 绘制地图边界
    ctx.strokeStyle = 'rgba(30, 196, 255, 0.5)';
    ctx.lineWidth = 1.5;
    ctx.strokeRect(ox, oy, mw * cs, mh * cs);
}

// ==================== 机器人渲染 ====================

/**
 * 绘制所有机器人
 * @param {CanvasRenderingContext2D} ctx
 * @param {object} layout
 * @param {object} state
 */
function renderRobots(ctx, layout, state) {
    var cs = layout.cellSize;
    var ox = layout.offsetX;
    var oy = layout.offsetY;

    var cars = state.cars || {};
    var carIds = Object.keys(cars);

    carIds.forEach(function (carId) {
        var car = cars[carId];
        if (!car || !car.position) return;

        var cx = ox + car.position.x * cs + cs / 2;
        var cy = oy + car.position.y * cs + cs / 2;
        var radius = Math.max(4, cs * 0.35);
        var color = getRobotColor(carId);

        // 绘制路径线
        if (car.routeList && car.routeList.length > 1) {
            drawRouteLine(ctx, ox, oy, cs, car, color);
        }

        // 发光光晕
        ctx.save();
        ctx.beginPath();
        ctx.arc(cx, cy, radius + 3, 0, Math.PI * 2);
        var glowGrad = ctx.createRadialGradient(cx, cy, radius * 0.5, cx, cy, radius + 3);
        glowGrad.addColorStop(0, color);
        glowGrad.addColorStop(1, 'transparent');
        ctx.fillStyle = glowGrad;
        ctx.globalAlpha = 0.4;
        ctx.fill();
        ctx.restore();

        // 主体圆形
        ctx.beginPath();
        ctx.arc(cx, cy, radius, 0, Math.PI * 2);

        // 渐变填充
        var bodyGrad = ctx.createRadialGradient(cx - radius * 0.3, cy - radius * 0.3, radius * 0.1, cx, cy, radius);
        bodyGrad.addColorStop(0, lightenColor(color, 0.4));
        bodyGrad.addColorStop(0.7, color);
        bodyGrad.addColorStop(1, darkenColor(color, 0.3));
        ctx.fillStyle = bodyGrad;
        ctx.fill();

        // 圆形边框
        ctx.strokeStyle = 'rgba(255,255,255,0.7)';
        ctx.lineWidth = Math.max(1, cs * 0.06);
        ctx.stroke();

        // 方向指示器（小三角形）
        var dir = getRobotDirection(car);
        drawDirectionArrow(ctx, cx, cy, radius, dir, color);

        // BLOCKED 指示器
        if (car.status === 'BLOCKED') {
            ctx.beginPath();
            ctx.arc(cx, cy - radius - 6, 4, 0, Math.PI * 2);
            ctx.fillStyle = '#ff9900';
            ctx.fill();
            ctx.strokeStyle = '#fff';
            ctx.lineWidth = 1;
            ctx.stroke();

            // 感叹号
            ctx.fillStyle = '#000';
            ctx.font = 'bold 6px sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('!', cx, cy - radius - 6);
        }

        // ID 标签
        var labelY = cy - radius - (car.status === 'BLOCKED' ? 14 : 6);
        ctx.fillStyle = 'rgba(0, 0, 0, 0.7)';
        var labelW = ctx.measureText(carId).width + 8;
        var labelH = 16;
        var fontSize = Math.max(9, cs * 0.32);
        ctx.font = 'bold ' + fontSize + 'px ' + '"Microsoft YaHei", sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'bottom';

        // 标签背景
        var textW = ctx.measureText(carId).width;
        ctx.fillStyle = 'rgba(0, 0, 0, 0.75)';
        var bx = cx - textW / 2 - 4;
        var by = labelY - labelH + 4;
        roundRect(ctx, bx, by, textW + 8, labelH, 3);
        ctx.fill();

        // 标签文字（使用机器人颜色）
        ctx.fillStyle = color;
        ctx.fillText(carId, cx, labelY);

        // 目标线（如果有目标）
        if (car.target && car.target.x !== undefined) {
            var tx = ox + car.target.x * cs + cs / 2;
            var ty = oy + car.target.y * cs + cs / 2;
            ctx.save();
            ctx.setLineDash([3, 5]);
            ctx.strokeStyle = 'rgba(255, 153, 0, 0.4)';
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.moveTo(cx, cy);
            ctx.lineTo(tx, ty);
            ctx.stroke();
            ctx.setLineDash([]);

            // 目标标记 X
            var targetMarkSize = Math.max(3, cs * 0.18);
            ctx.strokeStyle = 'rgba(255, 153, 0, 0.7)';
            ctx.lineWidth = 1.5;
            ctx.beginPath();
            ctx.moveTo(tx - targetMarkSize, ty - targetMarkSize);
            ctx.lineTo(tx + targetMarkSize, ty + targetMarkSize);
            ctx.moveTo(tx + targetMarkSize, ty - targetMarkSize);
            ctx.lineTo(tx - targetMarkSize, ty + targetMarkSize);
            ctx.stroke();
            ctx.restore();
        }
    });
}

/**
 * 绘制机器人路径线
 */
function drawRouteLine(ctx, ox, oy, cs, car, color) {
    if (!car.routeList || car.routeList.length < 2) return;

    ctx.save();
    ctx.setLineDash([2, 3]);
    ctx.strokeStyle = hexToRgba(color, 0.3);
    ctx.lineWidth = 1;
    ctx.beginPath();

    var startX = ox + car.position.x * cs + cs / 2;
    var startY = oy + car.position.y * cs + cs / 2;
    ctx.moveTo(startX, startY);

    car.routeList.forEach(function (pt) {
        var px = ox + pt.x * cs + cs / 2;
        var py = oy + pt.y * cs + cs / 2;
        ctx.lineTo(px, py);
    });

    ctx.stroke();
    ctx.setLineDash([]);
    ctx.restore();
}

/**
 * 获取机器人朝向角度
 * @param {object} car
 * @returns {number} 弧度
 */
function getRobotDirection(car) {
    // 优先使用 routeList 的第一个点
    if (car.routeList && car.routeList.length > 0) {
        var next = car.routeList[0];
        return Math.atan2(next.y - car.position.y, next.x - car.position.x);
    }
    // 其次使用 target
    if (car.target && car.target.x !== undefined) {
        return Math.atan2(car.target.y - car.position.y, car.target.x - car.position.x);
    }
    // 默认朝右
    return 0;
}

/**
 * 绘制方向箭头（三角形）
 */
function drawDirectionArrow(ctx, cx, cy, radius, angle, color) {
    var arrowSize = radius * 0.7;
    var tipX = cx + Math.cos(angle) * radius;
    var tipY = cy + Math.sin(angle) * radius;

    var leftX = cx + Math.cos(angle + 2.4) * arrowSize;
    var leftY = cy + Math.sin(angle + 2.4) * arrowSize;

    var rightX = cx + Math.cos(angle - 2.4) * arrowSize;
    var rightY = cy + Math.sin(angle - 2.4) * arrowSize;

    ctx.beginPath();
    ctx.moveTo(tipX, tipY);
    ctx.lineTo(leftX, leftY);
    ctx.lineTo(rightX, rightY);
    ctx.closePath();
    ctx.fillStyle = '#ffffff';
    ctx.fill();
    ctx.strokeStyle = darkenColor(color, 0.2);
    ctx.lineWidth = 0.8;
    ctx.stroke();
}

// ==================== 坐标转换 ====================

/**
 * 将像素坐标转换为网格坐标
 * @param {HTMLCanvasElement} canvas
 * @param {number} mouseX — 相对于画布的像素 x
 * @param {number} mouseY — 相对于画布的像素 y
 * @param {object} layout
 * @param {number} mapWidth
 * @param {number} mapHeight
 * @returns {{ row: number, col: number } | null}
 */
function getCellAt(canvas, mouseX, mouseY, layout, mapWidth, mapHeight) {
    var ox = layout.offsetX;
    var oy = layout.offsetY;
    var cs = layout.cellSize;

    var col = Math.floor((mouseX - ox) / cs);
    var row = Math.floor((mouseY - oy) / cs);

    if (col >= 0 && col < mapWidth && row >= 0 && row < mapHeight) {
        return { row: row, col: col };
    }
    return null;
}

/**
 * 将像素坐标转换为网格坐标（考虑 DPR）
 */
function getCellAtPixel(canvas, pixelX, pixelY, layout, mapWidth, mapHeight) {
    var dpr = window.devicePixelRatio || 1;
    return getCellAt(canvas, pixelX / dpr, pixelY / dpr, layout, mapWidth, mapHeight);
}

// ==================== 渲染主入口 ====================

/**
 * 执行完整渲染（由 app.js 每帧调用）
 * @param {HTMLCanvasElement} canvas
 * @param {object} state — 规范化后的状态
 * @param {object} layout — 来自 calcLayout 的布局参数
 */
function renderAll(canvas, state, layout) {
    var dpr = window.devicePixelRatio || 1;
    var ctx = canvas.getContext('2d');
    var rect = canvas.getBoundingClientRect();

    // 清除画布
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.restore();

    // 背景
    ctx.fillStyle = '#061529';
    ctx.fillRect(0, 0, rect.width, rect.height);

    // 绘制背景图片（如果有）
    if (_mapBgImage) {
        ctx.drawImage(_mapBgImage, layout.offsetX, layout.offsetY, layout.gridW, layout.gridH);
    }

    // 绘制地图（半透明叠加在背景图上）
    if (_mapBgImage) {
        ctx.save();
        ctx.globalAlpha = 0.55;
        renderMap(ctx, layout, state);
        ctx.restore();
    } else {
        renderMap(ctx, layout, state);
    }

    // 绘制网格
    renderGrid(ctx, layout, state.mapWidth, state.mapHeight);

    // 绘制机器人
    renderRobots(ctx, layout, state);
}

// ==================== 辅助函数 ====================

/**
 * 将 hex 颜色字符串转换为 rgba
 */
function hexToRgba(hex, alpha) {
    var r, g, b;
    hex = hex.replace('#', '');
    if (hex.length === 3) {
        r = parseInt(hex[0] + hex[0], 16);
        g = parseInt(hex[1] + hex[1], 16);
        b = parseInt(hex[2] + hex[2], 16);
    } else {
        r = parseInt(hex.substring(0, 2), 16);
        g = parseInt(hex.substring(2, 4), 16);
        b = parseInt(hex.substring(4, 6), 16);
    }
    return 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
}

/**
 * 颜色变亮
 */
function lightenColor(hex, amount) {
    var r, g, b;
    hex = hex.replace('#', '');
    if (hex.length === 3) {
        r = parseInt(hex[0] + hex[0], 16);
        g = parseInt(hex[1] + hex[1], 16);
        b = parseInt(hex[2] + hex[2], 16);
    } else {
        r = parseInt(hex.substring(0, 2), 16);
        g = parseInt(hex.substring(2, 4), 16);
        b = parseInt(hex.substring(4, 6), 16);
    }
    r = Math.min(255, Math.floor(r + (255 - r) * amount));
    g = Math.min(255, Math.floor(g + (255 - g) * amount));
    b = Math.min(255, Math.floor(b + (255 - b) * amount));
    return 'rgb(' + r + ',' + g + ',' + b + ')';
}

/**
 * 绘制圆角矩形路径
 */
function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
}

/**
 * 颜色变暗
 */
function darkenColor(hex, amount) {
    var r, g, b;
    hex = hex.replace('#', '');
    if (hex.length === 3) {
        r = parseInt(hex[0] + hex[0], 16);
        g = parseInt(hex[1] + hex[1], 16);
        b = parseInt(hex[2] + hex[2], 16);
    } else {
        r = parseInt(hex.substring(0, 2), 16);
        g = parseInt(hex.substring(2, 4), 16);
        b = parseInt(hex.substring(4, 6), 16);
    }
    r = Math.max(0, Math.floor(r * (1 - amount)));
    g = Math.max(0, Math.floor(g * (1 - amount)));
    b = Math.max(0, Math.floor(b * (1 - amount)));
    return 'rgb(' + r + ',' + g + ',' + b + ')';
}
