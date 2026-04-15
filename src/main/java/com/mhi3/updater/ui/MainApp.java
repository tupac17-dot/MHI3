package com.mhi3.updater.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {
    @Override
    public void start(Stage stage) {
        MainController controller = new MainController(stage);
        Scene scene = new Scene(controller.buildUi(), 1400, 850);
        stage.setScene(scene);
        stage.setTitle("MHI3 Audi Update Package Version Manager");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
