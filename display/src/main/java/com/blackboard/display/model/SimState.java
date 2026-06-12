package com.blackboard.display.model;

import java.util.List;

public class SimState {

    private long tick;

    private List<CarView> cars;

    public long getTick() {

        return tick;

    }

    public void setTick(long tick) {

        this.tick = tick;

    }

    public List<CarView> getCars() {

        return cars;

    }

    public void setCars(List<CarView> cars) {

        this.cars = cars;

    }

}