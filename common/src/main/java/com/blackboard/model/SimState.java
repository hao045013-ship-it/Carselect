package com.blackboard.model;

import com.alibaba.fastjson2.JSON;
import java.util.List;
import java.util.Map;

/**
 * 仿真全量状态快照 —— 推送给前端的完整数据
 */
public class SimState {
    private int mapWidth;
    private int mapHeight;
    private boolean[] mapView;
    private boolean[] staticBlock;
    private boolean[] dynamicBlock;
    private double exploredPercent;
    private long tick;
    private String status;
    private Map<String, CarInfo> cars;
    private String statsReport;
    private List<String> coverageHistory;

    public String getStatsReport() { return statsReport; }
    public void setStatsReport(String statsReport) { this.statsReport = statsReport; }
    public List<String> getCoverageHistory() { return coverageHistory; }
    public void setCoverageHistory(List<String> coverageHistory) { this.coverageHistory = coverageHistory; }
    public int getMapWidth() { return mapWidth; }
    public void setMapWidth(int mapWidth) { this.mapWidth = mapWidth; }
    public int getMapHeight() { return mapHeight; }
    public void setMapHeight(int mapHeight) { this.mapHeight = mapHeight; }
    public boolean[] getMapView() { return mapView; }
    public void setMapView(boolean[] mapView) { this.mapView = mapView; }
    public boolean[] getStaticBlock() {
        return staticBlock;
    }

    public void setStaticBlock(boolean[] staticBlock) {
        this.staticBlock = staticBlock;
    }

    public boolean[] getDynamicBlock() {
        return dynamicBlock;
    }

    public void setDynamicBlock(boolean[] dynamicBlock) {
        this.dynamicBlock = dynamicBlock;
    }
    public double getExploredPercent() { return exploredPercent; }
    public void setExploredPercent(double exploredPercent) { this.exploredPercent = exploredPercent; }
    public long getTick() { return tick; }
    public void setTick(long tick) { this.tick = tick; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, CarInfo> getCars() { return cars; }
    public void setCars(Map<String, CarInfo> cars) { this.cars = cars; }

    public String toJson() {
        return JSON.toJSONString(this);
    }

    /**
     * 小车信息
     */
    public static class CarInfo {
        private String carId;
        private Position position;
        private Position target;
        private List<Position> routeList;
        private String status;
        private int stepsWalked;

        public String getCarId() { return carId; }
        public void setCarId(String carId) { this.carId = carId; }
        public Position getPosition() { return position; }
        public void setPosition(Position position) { this.position = position; }
        public Position getTarget() { return target; }
        public void setTarget(Position target) { this.target = target; }
        public List<Position> getRouteList() { return routeList; }
        public void setRouteList(List<Position> routeList) { this.routeList = routeList; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getStepsWalked() { return stepsWalked; }
        public void setStepsWalked(int stepsWalked) { this.stepsWalked = stepsWalked; }
    }
}
