package com.blackboard.display;

import com.alibaba.fastjson2.JSON;
import com.blackboard.display.model.CarView;
import com.blackboard.display.model.Position;
import com.blackboard.display.model.SimState;
import com.blackboard.display.websocket.WebSocketBridge;

import java.util.List;

public class DisplayApplication {

    public static void main(String[] args)
            throws Exception {

        WebSocketBridge.init();

        Thread.sleep(3000);

        long tick = 0;

        while (true) {

            SimState state = new SimState();

            state.setTick(tick);

            state.setCars(
    List.of(
        new CarView("car1", new Position(50 + (int)(tick % 300), 100), 90),
        new CarView("car2", new Position(300, 50 + (int)(tick % 300)), 75),
        new CarView("car3", new Position(500, 300), 60)
    )
);

            String json =
                    JSON.toJSONString(
                            state);

            WebSocketBridge.broadcast(
                    json);

            tick++;

            Thread.sleep(
                    100);

        }

    }

}