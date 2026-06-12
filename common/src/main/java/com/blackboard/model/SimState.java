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
    private boolean[] mapBlock;
    private double exploredPercent;
    private long tick;
    private Map<String, CarInfo> cars;

    public int getMapWidth() { return mapWidth; }
    public void setMapWidth(int mapWidth) { this.mapWidth = mapWidth; }
    public int getMapHeight() { return mapHeight; }
    public void setMapHeight(int mapHeight) { this.mapHeight = mapHeight; }
    public boolean[] getMapView() { return mapView; }
    public void setMapView(boolean[] mapView) { this.mapView = mapView; }
    public boolean[] getMapBlock() { return mapBlock; }
    public void setMapBlock(boolean[] mapBlock) { this.mapBlock = mapBlock; }
    public double getExploredPercent() { return exploredPercent; }
    public void setExploredPercent(double exploredPercent) { this.exploredPercent = exploredPercent; }
    public long getTick() { return tick; }
    public void setTick(long tick) { this.tick = tick; }
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