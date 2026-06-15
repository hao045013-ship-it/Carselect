package com.blackboard.model;

import java.util.Objects;

import com.alibaba.fastjson2.JSON;

import java.util.Objects;

/**
 * 二维坐标点 —— 所有组件共用
 */
public class Position {
    private int x;
    private int y;

    public Position() {}
    public Position(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() { return x; }
    public void setX(int x) { this.x = x; }
    public int getY() { return y; }
    public void setY(int y) { this.y = y; }

    public String toJson() {
        return JSON.toJSONString(this);
    }

    public static Position fromJson(String json) {
        return JSON.parseObject(json, Position.class);
    }

    public int manhattanDistance(Position other) {
        return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + ")";
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Position)) return false;
        Position position = (Position) o;
        return x == position.x && y == position.y;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

}