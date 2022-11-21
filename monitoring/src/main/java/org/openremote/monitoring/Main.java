package org.openremote.monitoring;

import org.openremote.container.Container;

public class Main {

    public static void main(String[] args) throws Exception {

        Container container = new Container();

        try {
            container.startBackground();
        } catch (Exception e) {
            container.stop();
            System.exit(1);
        }
    }
}