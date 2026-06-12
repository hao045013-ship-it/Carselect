package com.blackboard.display.model;

public class CarView {

    private String id;

    private Position position;

    private int battery;

    public CarView() {
    }

    public CarView(
            String id,
            Position position,
            int battery) {

        this.id = id;
        this.position = position;
        this.battery = battery;

    }

    public String getId() {

        return id;

    }

    public void setId(String id) {

        this.id = id;

    }

    public Position getPosition() {

        return position;

    }

    public void setPosition(Position position) {

        this.position = position;

    }

    public int getBattery() {

        return battery;

    }

    public void setBattery(int battery) {

        this.battery = battery;

    }

}